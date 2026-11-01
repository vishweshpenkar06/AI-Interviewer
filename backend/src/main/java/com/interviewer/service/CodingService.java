package com.interviewer.service;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.*;
import org.springframework.stereotype.Service;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.RestTemplate;

import java.util.*;
import java.util.concurrent.*;

/**
 * Execution provider chain: Judge0 (when configured and reachable) →
 * self-hosted Piston (free) → an explicit "execution unavailable" result.
 * The UI can tell which provider ran the code, and failures never look like a
 * wrong answer.
 */
@Service
public class CodingService {

    @Value("${judge0.api.url:https://ce.judge0.com}")
    private String judge0Url;

    @Value("${judge0.api.key:}")
    private String judge0ApiKey;

    @Value("${piston.url:http://localhost:2000}")
    private String pistonUrl;

    private final RestTemplate restTemplate;

    public CodingService() {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(5000);
        factory.setReadTimeout(30000);
        this.restTemplate = new RestTemplate(factory);
    }

    private final ExecutorService executor = Executors.newFixedThreadPool(4);
    private final Map<String, String> pistonVersions = new ConcurrentHashMap<>();

    private static final Map<Integer, String> PISTON_LANGUAGES = Map.of(
            71, "python", 62, "java", 63, "javascript", 54, "c++", 50, "c",
            51, "csharp", 60, "go", 73, "rust", 74, "typescript");

    private static final Map<Integer, String> PISTON_FILENAMES = Map.of(
            71, "main.py", 62, "Main.java", 63, "main.js", 54, "main.cpp", 50, "main.c",
            51, "main.cs", 60, "main.go", 73, "main.rs", 74, "main.ts");

    private record TestCase(String name, String stdin, String expectedOutput) {}

    private record ProblemTests(List<TestCase> cases, boolean caseSensitive) {}

    private static final Map<Integer, ProblemTests> PROBLEM_TEST_CASES = new LinkedHashMap<>();

    static {
        PROBLEM_TEST_CASES.put(1, new ProblemTests(List.of(
            new TestCase("Example 1", "2 7 11 15\n9", "0 1"),
            new TestCase("Example 2", "3 2 4\n6", "1 2"),
            new TestCase("Single pair", "3 3\n6", "0 1")
        ), true));

        PROBLEM_TEST_CASES.put(2, new ProblemTests(List.of(
            new TestCase("Example 1", "hello", "olleh"),
            new TestCase("Example 2", "Hannah", "hannaH"),
            new TestCase("Single char", "a", "a")
        ), true));

        PROBLEM_TEST_CASES.put(3, new ProblemTests(List.of(
            new TestCase("Simple pair", "()", "true"),
            new TestCase("Mixed brackets", "()[]{}", "true"),
            new TestCase("Nested", "{[]}", "true"),
            new TestCase("Invalid order", "(]", "false"),
            new TestCase("Unbalanced", "([)]", "false")
        ), false));

        PROBLEM_TEST_CASES.put(4, new ProblemTests(List.of(
            new TestCase("Example 1", "1 2 4\n1 3 4", "1 1 2 3 4 4"),
            new TestCase("Empty first list", "\n1 2", "1 2"),
            new TestCase("Disjoint ranges", "1 3 5\n2 4 6", "1 2 3 4 5 6")
        ), true));

        PROBLEM_TEST_CASES.put(5, new ProblemTests(List.of(
            new TestCase("Example 1", "3 9 20 null null 15 7", "3;9 20;15 7"),
            new TestCase("Single node", "1", "1"),
            new TestCase("Left skewed", "1 2 null 3", "1;2;3")
        ), true));
    }

    public Map<String, Object> executeCode(Integer languageId, String sourceCode, String stdin) {
        Map<String, Object> judge0 = executeJudge0(languageId, sourceCode, stdin);
        if (judge0 != null) {
            judge0.put("provider", "judge0");
            return judge0;
        }

        Map<String, Object> piston = executePiston(languageId, sourceCode, stdin);
        if (piston != null) {
            piston.put("provider", "piston");
            return piston;
        }

        Map<String, Object> unavailable = new LinkedHashMap<>();
        unavailable.put("provider", "none");
        unavailable.put("status", Map.of("description", "Execution unavailable"));
        unavailable.put("stdout", "");
        unavailable.put("stderr",
                "No code execution provider is reachable. Start Piston with "
                        + "`docker run -p 2000:2000 ghcr.io/engineer-man/piston` and retry.");
        return unavailable;
    }

    /** Returns null when Judge0 is unreachable or rejects the request, so the caller can fall back. */
    private Map<String, Object> executeJudge0(Integer languageId, String sourceCode, String stdin) {
        try {
            Map<String, Object> request = new HashMap<>();
            request.put("language_id", languageId);
            request.put("source_code", sourceCode);
            request.put("stdin", stdin);
            request.put("cpu_time_limit", 5);
            request.put("memory_limit", 262144);

            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.APPLICATION_JSON);
            if (!judge0ApiKey.isEmpty()) {
                headers.set("X-Auth-Token", judge0ApiKey);
            }

            HttpEntity<Map<String, Object>> entity = new HttpEntity<>(request, headers);
            ResponseEntity<Map> submitResponse = restTemplate.exchange(
                judge0Url + "/submissions?base64_encoded=false&wait=true",
                HttpMethod.POST,
                entity,
                Map.class
            );
            if (submitResponse.getBody() != null) {
                return new LinkedHashMap<>(submitResponse.getBody());
            }
        } catch (Exception e) {
            // Fall through to Piston.
        }
        return null;
    }

    private Map<String, Object> executePiston(Integer languageId, String sourceCode, String stdin) {
        String language = PISTON_LANGUAGES.get(languageId);
        if (language == null) {
            return null;
        }
        try {
            Map<String, Object> request = new LinkedHashMap<>();
            request.put("language", language);
            request.put("version", pistonVersion(language));
            request.put("files", List.of(Map.of(
                    "name", PISTON_FILENAMES.getOrDefault(languageId, "main.txt"),
                    "content", sourceCode)));
            request.put("stdin", stdin == null ? "" : stdin);
            request.put("compile_timeout", 10_000);
            request.put("run_timeout", 5_000);
            request.put("run_memory_limit", 134_217_728);

            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.APPLICATION_JSON);
            ResponseEntity<Map> response = restTemplate.exchange(
                    pistonUrl + "/api/v2/execute",
                    HttpMethod.POST,
                    new HttpEntity<>(request, headers),
                    Map.class);

            Map<String, Object> body = response.getBody();
            if (body == null) {
                return null;
            }
            return translatePiston(body);
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * Piston returns compile/run objects; this maps them onto the Judge0-ish
     * fields the rest of the app already understands.
     */
    private Map<String, Object> translatePiston(Map<String, Object> body) {
        Map<String, Object> compile = asMap(body.get("compile"));
        Map<String, Object> run = asMap(body.get("run"));

        String compileOutput = string(compile.get("stderr"));
        String stdout = string(run.get("stdout"));
        String stderr = string(run.get("stderr"));
        Object code = run.get("code");
        String signal = string(run.get("signal"));

        String description = "Accepted";
        if (!compileOutput.isBlank()) {
            description = "Compilation Error";
        } else if ("SIGKILL".equals(signal) || Integer.valueOf(137).equals(code)) {
            description = "Time Limit Exceeded";
        } else if (code instanceof Number number && number.intValue() != 0) {
            description = "Runtime Error";
        }

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("stdout", stdout);
        result.put("stderr", stderr);
        result.put("compile_output", compileOutput);
        result.put("status", Map.of("description", description));
        result.put("time", run.get("cpu_time") != null ? String.valueOf(run.get("cpu_time")) : "");
        result.put("memory", run.get("memory") == null ? "" : String.valueOf(run.get("memory")));
        return result;
    }

    private String pistonVersion(String language) {
        return pistonVersions.computeIfAbsent(language, key -> {
            try {
                ResponseEntity<List> response =
                        restTemplate.getForEntity(pistonUrl + "/api/v2/runtimes", List.class);
                if (response.getBody() != null) {
                    for (Object item : response.getBody()) {
                        if (item instanceof Map<?, ?> runtime && key.equals(runtime.get("language"))) {
                            Object version = runtime.get("version");
                            if (version != null) {
                                return version.toString();
                            }
                        }
                    }
                }
            } catch (Exception ignored) {
                // Fall back to the wildcard; older Piston builds accept it.
            }
            return "*";
        });
    }

    private Map<String, Object> asMap(Object value) {
        if (value instanceof Map<?, ?> map) {
            Map<String, Object> result = new LinkedHashMap<>();
            map.forEach((k, v) -> result.put(String.valueOf(k), v));
            return result;
        }
        return Map.of();
    }

    private String string(Object value) {
        return value == null ? "" : value.toString();
    }

    public Map<String, Object> submitCode(Long problemId, Integer languageId, String language, String sourceCode) {
        int pid = problemId != null ? problemId.intValue() : 0;
        ProblemTests problemTests = PROBLEM_TEST_CASES.get(pid);
        List<TestCase> testCases = problemTests != null ? problemTests.cases() : List.of();
        boolean caseSensitive = problemTests == null || problemTests.caseSensitive();

        List<Map<String, Object>> testResults;
        String provider = "none";

        if (testCases.isEmpty()) {
            Map<String, Object> executionResult = executeCode(languageId, sourceCode, "");
            provider = String.valueOf(executionResult.getOrDefault("provider", "none"));
            boolean passed = executionResult.get("stdout") != null
                && !executionResult.get("stdout").toString().isEmpty()
                && !"none".equals(provider);
            testResults = List.of(Map.of(
                "passed", passed,
                "testName", "Basic Test",
                "error", passed ? "" : "No output produced",
                "provider", provider
            ));
        } else {
            List<Future<Map<String, Object>>> futures = new ArrayList<>();
            for (TestCase tc : testCases) {
                futures.add(executor.submit(() -> runTestCase(languageId, sourceCode, tc, caseSensitive)));
            }

            testResults = new ArrayList<>();
            for (Future<Map<String, Object>> future : futures) {
                try {
                    Map<String, Object> result = future.get(60, TimeUnit.SECONDS);
                    provider = String.valueOf(result.getOrDefault("provider", provider));
                    testResults.add(result);
                } catch (TimeoutException e) {
                    testResults.add(Map.of("passed", false, "testName", "Test",
                            "error", "Execution timed out", "provider", "none"));
                } catch (Exception e) {
                    testResults.add(Map.of("passed", false, "testName", "Test",
                            "error", "Execution failed", "provider", "none"));
                }
            }
            if ("none".equals(provider)) {
                for (Map<String, Object> result : testResults) {
                    Object candidate = result.get("provider");
                    if (candidate != null && !"none".equals(candidate)) {
                        provider = String.valueOf(candidate);
                        break;
                    }
                }
            }
        }

        long passedCount = testResults.stream().filter(r -> Boolean.TRUE.equals(r.get("passed"))).count();
        int score = testResults.isEmpty() ? 0 : (int) Math.round(passedCount * 100.0 / testResults.size());

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("problemId", pid);
        result.put("language", language);
        result.put("provider", provider);
        result.put("testResults", testResults);
        result.put("passedCount", passedCount);
        result.put("totalTests", testResults.size());
        result.put("score", score);
        return result;
    }

    private Map<String, Object> runTestCase(Integer languageId, String sourceCode, TestCase tc, boolean caseSensitive) {
        Map<String, Object> execution = executeCode(languageId, sourceCode, tc.stdin());

        String statusDescription = "";
        Object status = execution.get("status");
        if (status instanceof Map<?, ?> statusMap && statusMap.get("description") != null) {
            statusDescription = statusMap.get("description").toString();
        }

        String stdout = execution.get("stdout") != null ? execution.get("stdout").toString() : "";
        String stderr = execution.get("stderr") != null ? execution.get("stderr").toString() : "";
        String compileOutput = execution.get("compile_output") != null ? execution.get("compile_output").toString() : "";
        String provider = String.valueOf(execution.getOrDefault("provider", "none"));

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("testName", tc.name());
        result.put("provider", provider);

        if ("Execution unavailable".equalsIgnoreCase(statusDescription)) {
            result.put("passed", false);
            result.put("error", stderr.isBlank() ? "Execution unavailable" : truncate(stderr));
            return result;
        }

        if (!compileOutput.isEmpty()) {
            result.put("passed", false);
            result.put("error", "Compilation error: " + truncate(compileOutput));
            return result;
        }

        if (!"Accepted".equalsIgnoreCase(statusDescription) && stdout.isEmpty()) {
            result.put("passed", false);
            result.put("error", statusDescription.isEmpty() ? "Runtime error" : statusDescription
                + (stderr.isEmpty() ? "" : ": " + truncate(stderr)));
            return result;
        }

        String actual = normalize(stdout);
        String expected = normalize(tc.expectedOutput());
        boolean passed = caseSensitive ? actual.equals(expected) : actual.equalsIgnoreCase(expected);
        result.put("passed", passed);
        if (!passed) {
            result.put("expectedOutput", tc.expectedOutput());
            result.put("actualOutput", stdout.trim());
            result.put("error", "Wrong answer");
        } else {
            result.put("error", "");
        }
        return result;
    }

    private String normalize(String output) {
        if (output == null) return "";
        String[] lines = output.trim().split("\\r?\\n");
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < lines.length; i++) {
            if (i > 0) sb.append('\n');
            sb.append(lines[i].strip().replaceAll("\\s+", " "));
        }
        return sb.toString();
    }

    private String truncate(String text) {
        String clean = text == null ? "" : text.trim();
        return clean.length() > 300 ? clean.substring(0, 300) + "..." : clean;
    }

    public List<Map<String, Object>> getSupportedLanguages() {
        return Arrays.asList(
            Map.of("id", 71, "name", "Python", "monacoId", "python"),
            Map.of("id", 62, "name", "Java", "monacoId", "java"),
            Map.of("id", 63, "name", "JavaScript", "monacoId", "javascript"),
            Map.of("id", 54, "name", "C++", "monacoId", "cpp"),
            Map.of("id", 50, "name", "C", "monacoId", "c"),
            Map.of("id", 51, "name", "C#", "monacoId", "csharp"),
            Map.of("id", 60, "name", "Go", "monacoId", "go"),
            Map.of("id", 73, "name", "Rust", "monacoId", "rust"),
            Map.of("id", 74, "name", "TypeScript", "monacoId", "typescript")
        );
    }

    public List<Map<String, Object>> getProblems() {
        return Arrays.asList(
            Map.of(
                "id", 1,
                "title", "Two Sum",
                "difficulty", "EASY",
                "description", "Given an array of integers and a target, print the indices (space-separated) of the two numbers that add up to the target. Input: line 1 = space-separated integers, line 2 = target. Output: two indices separated by a space.",
                "inputFormat", "Line 1: space-separated integers\nLine 2: target",
                "outputFormat", "Two indices separated by a space",
                "examples", List.of(
                    Map.of("input", "2 7 11 15\n9", "output", "0 1"),
                    Map.of("input", "3 2 4\n6", "output", "1 2")
                )
            ),
            Map.of(
                "id", 2,
                "title", "Reverse String",
                "difficulty", "EASY",
                "description", "Read a single line of input and print it reversed.",
                "inputFormat", "A single line of text",
                "outputFormat", "The reversed text",
                "examples", List.of(
                    Map.of("input", "hello", "output", "olleh"),
                    Map.of("input", "Hannah", "output", "hannaH")
                )
            ),
            Map.of(
                "id", 3,
                "title", "Valid Parentheses",
                "difficulty", "MEDIUM",
                "description", "Read a string containing the characters '(', ')', '{', '}', '[' and ']' and print 'true' if the brackets are balanced and correctly nested, otherwise print 'false'.",
                "inputFormat", "A single line containing brackets",
                "outputFormat", "true or false",
                "examples", List.of(
                    Map.of("input", "()[]{}", "output", "true"),
                    Map.of("input", "([)]", "output", "false")
                )
            ),
            Map.of(
                "id", 4,
                "title", "Merge Two Sorted Lists",
                "difficulty", "EASY",
                "description", "Read two sorted lists of integers (one per line, space-separated) and print the merged sorted list, space-separated. An empty line represents an empty list.",
                "inputFormat", "Line 1: first sorted list\nLine 2: second sorted list",
                "outputFormat", "Merged sorted list, space-separated",
                "examples", List.of(
                    Map.of("input", "1 2 4\n1 3 4", "output", "1 1 2 3 4 4"),
                    Map.of("input", "1 3 5\n2 4 6", "output", "1 2 3 4 5 6")
                )
            ),
            Map.of(
                "id", 5,
                "title", "Binary Tree Level Order Traversal",
                "difficulty", "MEDIUM",
                "description", "Read a binary tree given in level order ('null' marks a missing node) and print its level order traversal: values of each level separated by spaces, levels separated by semicolons.",
                "inputFormat", "Level-order node values, 'null' for missing nodes",
                "outputFormat", "Levels separated by ';', values by spaces",
                "examples", List.of(
                    Map.of("input", "3 9 20 null null 15 7", "output", "3;9 20;15 7"),
                    Map.of("input", "1", "output", "1")
                )
            )
        );
    }

    public Map<String, Object> getProblem(Long id) {
        return getProblems().stream()
            .filter(p -> p.get("id").equals(id.intValue()))
            .findFirst()
            .orElse(Map.of("error", "Problem not found"));
    }
}
