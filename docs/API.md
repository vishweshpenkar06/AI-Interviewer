# API Documentation

Two services expose HTTP APIs:

| Service | Base URL | Who calls it |
|---|---|---|
| Backend (Spring Boot) | `http://localhost:8080/api` (`8082` with the `local` profile) | Browser |
| AI service (FastAPI) | `http://localhost:8001/ai` | Backend only |

Everything below the AI-service heading is internal. The browser never talks to the
AI service.

## Authentication

All backend endpoints except `/api/auth/**` and `/api/health` require a JWT:

```
Authorization: Bearer <token>
```

The token comes from register or login and contains the user id. Every
resource-scoped endpoint resolves the owner from that id; asking for somebody
else's interview, report, resume or job description returns **404**, not 403, so
the API does not confirm that the resource exists.

A missing or invalid token is rejected by the security filter, before any
controller runs:

```json
{ "status": 401, "error": "Unauthorized", "message": "Authentication required" }
```

### Rate limiting

`POST /api/auth/login` and `POST /api/auth/register` are limited to 60 requests
per minute per client IP. Over the limit:

```
HTTP 429
Retry-After: 60
{ "status": 429, "error": "Too Many Requests", "message": "Too many attempts. Try again in a minute." }
```

## Error Responses

Errors raised inside a controller share one shape:

```json
{
  "timestamp": "2026-10-27T12:37:59.233",
  "status": 404,
  "error": "Not Found",
  "message": "Interview not found",
  "path": "/api/interviews/42"
}
```

Validation errors add a `fields` map of `field -> message`.

| Status | Meaning |
|---|---|
| 400 | Malformed body, failed validation, or a bad value |
| 401 | Missing, expired or invalid token; bad credentials |
| 403 | Authenticated but not allowed (role) |
| 404 | Not found, including another user's resource |
| 405 | Wrong HTTP method for the path |
| 409 | Wrong interview state, duplicate action, unsupported upload type |
| 413 | Upload too large (5 MB) |
| 429 | Rate limited |
| 500 | Unexpected server error; the message never contains internals |

## Auth Endpoints

### Register

`POST /api/auth/register`

```json
{ "email": "ada@example.com", "password": "Str0ngPassw0rd!", "fullName": "Ada Lovelace" }
```

`phone`, `profileSummary`, `linkedinUrl` and `githubUrl` are optional. The
password must be at least 8 characters.

```json
{
  "token": "eyJhbGciOiJIUzI1NiJ9...",
  "tokenType": "Bearer",
  "userId": 7,
  "email": "ada@example.com",
  "fullName": "Ada Lovelace",
  "role": "CANDIDATE"
}
```

### Login

`POST /api/auth/login`

```json
{ "email": "ada@example.com", "password": "Str0ngPassw0rd!" }
```

Returns the same body as register. Wrong credentials are **401**
`Invalid email or password`.

## Job Descriptions

### Create

`POST /api/job-descriptions` — returns **201**

```json
{
  "title": "Backend Developer",
  "company": "Acme",
  "description": "Build and operate Java services with Spring Boot, SQL and REST APIs.",
  "experienceLevel": "MID"
}
```

`location` and `employmentType` are optional. `title`, `company` and
`description` are required.

```json
{
  "id": 1,
  "title": "Backend Developer",
  "company": "Acme",
  "description": "Build and operate Java services...",
  "experienceLevel": "MID",
  "location": null,
  "employmentType": null,
  "createdAt": "2026-10-27T12:37:58.901"
}
```

### List / get / delete

- `GET /api/job-descriptions` — the caller's job descriptions
- `GET /api/job-descriptions/{id}` — one, or 404
- `DELETE /api/job-descriptions/{id}` — 404 if it belongs to someone else

## Resumes

### Upload

`POST /api/resumes/upload` (multipart)

| Field | Type | Notes |
|---|---|---|
| `file` | file | `.txt` or `.md`, max 5 MB |
| `text` | string | optional pasted text instead of a file |

PDF and DOCX are rejected with **409** and a message asking for text or one of
the supported formats; no PDF parser is bundled. The stored filename is
sanitised and cannot escape the upload directory.

### Paste text

`POST /api/resumes/parse-text` — `{ "text": "..." }`

### Others

- `GET /api/resumes` — the caller's resumes
- `GET /api/resumes/{resumeId}` — one, or 404
- `DELETE /api/resumes/{resumeId}` — 404 if it belongs to someone else

## Interview Endpoints

An interview moves `SCHEDULED -> IN_PROGRESS -> COMPLETED`. Calling `begin` or
`end` twice is safe: the second call returns the current state instead of
re-running the transition. Every other transition in the wrong state is **409**.

### Start

`POST /api/interviews/start`

```json
{
  "jobDescriptionId": 1,
  "title": "Backend screen",
  "totalQuestions": 2,
  "mode": "TEXT",
  "durationMinutes": 60
}
```

Only `jobDescriptionId` is required. The questions come from the bundled
question bank for that role; the AI service is only asked when the bank has too
few entries. Unknown question `type`/`difficulty` values in the bank fall back to
`TECHNICAL`/`MEDIUM` instead of failing the request.

```json
{
  "id": 2,
  "title": "Backend screen",
  "status": "SCHEDULED",
  "mode": "TEXT",
  "totalQuestions": 2,
  "currentQuestionIndex": 0,
  "durationMinutes": 60,
  "createdAt": "2026-10-27T12:37:59.233",
  "startedAt": null,
  "completedAt": null,
  "overallScore": null,
  "overallGrade": null,
  "jobDescriptionTitle": "Backend Developer",
  "companyName": "Acme",
  "sessions": [
    {
      "id": 3,
      "questionOrder": 1,
      "questionText": "Tell me about a time you mentored a junior developer...",
      "questionType": "BEHAVIORAL",
      "difficulty": "MEDIUM",
      "status": "PENDING"
    }
  ]
}
```

### Begin

`POST /api/interviews/{interviewId}/begin` — `SCHEDULED -> IN_PROGRESS`.

### Next question

`GET /api/interviews/{interviewId}/next-question` — 409 when the interview is not
in progress or there is nothing left.

```json
{
  "id": 3,
  "questionOrder": 1,
  "questionText": "Tell me about a time you mentored a junior developer...",
  "questionType": "BEHAVIORAL",
  "difficulty": "MEDIUM",
  "status": "PENDING"
}
```

`expectedAnswer` is **omitted until the interview is completed**, so the answer
key cannot be read from the browser during the interview.

### Submit answer

`POST /api/interviews/{interviewId}/answer`

```json
{
  "sessionId": 3,
  "answer": "An index keeps keys sorted so the planner can seek instead of scanning."
}
```

Answers must be submitted in order. Re-submitting the same `sessionId` is
idempotent: it returns the stored evaluation and never advances the question
index again.

```json
{
  "id": 3,
  "questionOrder": 1,
  "questionText": "Tell me about a time you mentored a junior developer...",
  "questionType": "BEHAVIORAL",
  "difficulty": "MEDIUM",
  "candidateAnswer": "An index keeps keys sorted...",
  "status": "ANSWERED",
  "evaluation": {
    "score": 72,
    "grade": "B+",
    "feedback": "Relevant and correct; the trade-offs are missing.",
    "strengths": "Concrete explanation of the seek vs scan cost",
    "weaknesses": "Does not mention write amplification",
    "improvementSuggestions": "Mention when low selectivity makes the index useless",
    "sampleResponse": "...",
    "confidenceLevel": "HIGH",
    "communicationScore": "75/100",
    "technicalDepth": "70/100"
  }
}
```

`evaluation` is `null` when the AI service is unavailable. The answer is still
saved and can be evaluated later; the report will say that nothing was scored
rather than inventing a number.

After a strong answer (score >= 80) the next planned question is swapped for a
HARD one from the same role; after a weak answer (<= 50) for an EASY one.

### End

`POST /api/interviews/{interviewId}/end` — `IN_PROGRESS -> COMPLETED`, builds the
report, and returns the interview.

### List / get

- `GET /api/interviews` — the caller's interviews, newest first
- `GET /api/interviews/{interviewId}` — one, or 404

## Report Endpoints

### Get report

`GET /api/reports/{interviewId}`

```json
{
  "id": 2,
  "interviewId": 2,
  "overallScore": 72,
  "overallGrade": "B+",
  "summary": "Score computed from 1 evaluated answer(s) out of 2 question(s).",
  "skillRadarData": [
    { "skill": "Relevance", "score": 80 },
    { "skill": "Technical Depth", "score": 70 }
  ],
  "categoryScores": [{ "category": "Technical", "score": 72 }],
  "strengths": ["..."],
  "weaknesses": ["..."],
  "recommendations": "Review the per-question feedback for concrete next steps.",
  "recommendationLevel": "RECOMMENDED",
  "questionBreakdown": [
    { "question": "...", "questionOrder": 1, "status": "EVALUATED", "score": 72, "grade": "B+", "feedback": "..." }
  ],
  "createdAt": "2026-10-27T12:37:59.465"
}
```

The score, grade, radar and category charts are computed from the real
per-answer evaluations. If nothing was evaluated:

```json
{
  "overallScore": 0,
  "overallGrade": "N/A",
  "recommendationLevel": "INSUFFICIENT_DATA",
  "summary": "No answers were evaluated for this interview, so no score can be calculated. This is not a judgement of the candidate.",
  "skillRadarData": [],
  "categoryScores": []
}
```

`N/A` with `INSUFFICIENT_DATA` is deliberately not a passing grade.

### Get full report

`GET /api/reports/{interviewId}/full` — the report plus the interview and its
sessions.

## Coding Endpoints

- `GET /api/coding/languages` — languages the Judge0 instance accepts
- `GET /api/coding/problems` — bundled problems
- `GET /api/coding/problems/{id}` — one problem
- `POST /api/coding/execute` — run against sample tests
- `POST /api/coding/submit` — run against all tests

`execute` and `submit` take a free-form body (`language`, `source_code`,
`problemId`, `stdin`). Execution is delegated to `JUDGE0_API_URL`; when Judge0 is
unreachable the endpoint reports that rather than pretending the code ran.

## Voice Endpoints

- `POST /api/voice/transcribe` — multipart `audio`
- `POST /api/voice/synthesize` — `{ "text": "...", "voice": "en-US-AriaNeural" }`, returns audio bytes
- `GET /api/voice/voices` — voice names

Both proxy the AI service, so the AI service URL and its token stay server-side.
`synthesize` returns `audio/mpeg`; with the default `TTS_PROVIDER=mock` it returns
an empty body, and the frontend then falls back to the browser's own speech
synthesis.

## User Endpoints

- `GET /api/users/profile` — the caller's profile
- `PUT /api/users/profile` — update `fullName`, `phone`, `profileSummary`, `linkedinUrl`, `githubUrl`

## Health

`GET /api/health` — no token, safe for uptime checks:

```json
{ "status": "UP", "timestamp": "2026-10-27T07:06:59.134Z", "database": "UP", "aiService": "DOWN" }
```

`aiService: "DOWN"` is expected when the AI service is not running; the app still
works with the heuristic fallback and the bundled question bank.

## AI Service (Internal)

Base URL `http://localhost:8001/ai`.

If `AI_SERVICE_TOKEN` is set, every request must carry the same value:

```
X-AI-Service-Token: <token>
```

Missing or wrong token → **401**. When the variable is empty the service is open,
which is only appropriate on localhost. CORS is limited to `ALLOWED_ORIGINS`.

| Endpoint | Body |
|---|---|
| `POST /generate-questions` | `job_description`, `resume_text`, `count` (1-30), `experience_level` |
| `POST /generate-followup` | `question`, `answer` |
| `POST /evaluate-answer` | `question`, `answer`, `expected_answer` |
| `POST /parse-resume` | `resume_text` |
| `POST /analyze-jd` | `job_description` |
| `POST /generate-report` | `interview_id`, `evaluations`, `average_score`, ... |
| `POST /transcribe` | multipart audio |
| `POST /synthesize` | `text`, `voice`, `rate` |
| `POST /analyze-emotion` | `audio_features`, `transcript` |
| `GET /voices` | — |

Field lengths are bounded (answers 20 000 chars, job descriptions 20 000, resume
text 100 000). Oversized input is **422**. Internal failures return
`{"detail":"AI service error"}` with **500**; provider messages and stack traces
are never returned.

### Evaluation contract

`EVALUATOR_MODE=rubric` (default) asks the model for five criteria scored 0-10
(`relevance`, `technical_depth`, `correctness`, `clarity`, `evidence`), each with
a verbatim quote from the answer. The 0-100 score is computed in code:

```
score = 100 * (0.15*relevance + 0.30*technical_depth + 0.30*correctness
             + 0.15*clarity + 0.10*evidence) / 10
```

A quote that cannot be found in the answer caps that criterion at 5, lowers the
confidence to `MEDIUM` at most, and is reported as `ungrounded_evidence`. An
unparseable model reply is retried once and then fails closed (no score) instead
of returning a default.

```json
{
  "score": 72,
  "grade": "B+",
  "feedback": "Relevant and correct; the trade-offs are missing.",
  "strengths": "Concrete explanation",
  "weaknesses": "No write-amplification cost",
  "improvement_suggestions": "Mention selectivity",
  "sample_response": "...",
  "confidence_level": "HIGH",
  "communication_score": "75/100",
  "technical_depth": "70/100",
  "skill_scores": { "relevance": 80, "technicalDepth": 70, "correctness": 75, "clarity": 70, "evidence": 60 },
  "evaluation_mode": "rubric",
  "flags": ["evidence_ok:relevance"]
}
```

`evaluation_mode` is `rubric`, `naive` (`EVALUATOR_MODE=naive`, the legacy
multi-prompt path) or `heuristic` (no LLM key or the model failed). Only `rubric`
is measured; see [bench/RESULTS.md](../bench/RESULTS.md) for the current numbers
and their limits.

## PlacementOS endpoints (multi-tenant)

All org-scoped routes require a JWT. The path `orgId` is validated against a
`Membership` row; TPO routes additionally require the TPO or ADMIN role. The
`X-Organization-Id` header is accepted and validated by `TenantContext`, but the
path is authoritative.

| Method | Path | Role |
|---|---|---|
| POST | `/api/orgs` | any authenticated user (creator becomes ADMIN) |
| GET | `/api/orgs/{orgId}` | member |
| GET | `/api/orgs/{orgId}/tracks` | member |
| GET | `/api/orgs/{orgId}/analytics` | TPO/ADMIN |
| GET | `/api/orgs/{orgId}/usage` | member |
| GET | `/api/orgs/{orgId}/reports/export` | TPO/ADMIN |
| POST/GET | `/api/orgs/{orgId}/cohorts` | TPO/ADMIN |
| POST | `/api/orgs/{orgId}/cohorts/{cohortId}/students/import` | TPO/ADMIN, multipart `file` |
| GET | `/api/orgs/{orgId}/cohorts/{cohortId}/students` | TPO/ADMIN |
| POST/GET | `/api/orgs/{orgId}/assignments` | TPO/ADMIN |
| GET | `/api/me/assignments` | student |
| POST | `/api/orgs/{orgId}/assignments/{assignmentId}/start` | student member |
| GET | `/api/me/organizations` | any authenticated |
| GET/DELETE | `/api/me/data` | any authenticated |
| GET/POST | `/api/me/consent` | any authenticated |

### CSV format

```csv
fullName,email,phone,department,graduationYear
Asha Rao,asha@example.com,9876543210,CSE,2027
```

Response: `{ "created": 2, "skipped": 1, "errors": ["row 4: invalid email: ..."], "students": [...] }`.

### Track config

`GET /api/orgs/{orgId}/tracks` returns the 5 seeded global tracks (TCS NQT,
Infosys, Wipro, Product SDE, Data Analyst) with their parsed `config`:
`durationMinutes`, `questionCount`, `difficulty`, `sections`, and
`rubricWeights` (which must sum to 1.0). Assignment start copies the config into
`Interview.interviewConfig`.
