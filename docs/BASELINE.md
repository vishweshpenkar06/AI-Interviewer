# Baseline — before PlacementOS

Recorded on the `feat/placementos` branch, starting from `main` (`27628ea`).

## Toolchain

| Tool | Version used | Notes |
|---|---|---|
| Java | Temurin 21.0.13 | The host default is JDK 25; Mockito in Spring Boot 3.4 cannot instrument classes on 25, so the backend is verified with JDK 21 (`JAVA_HOME=/tmp/jdk21`). CI already uses 21. |
| Maven | 3.9.9 | No wrapper in the repo. |
| Node | 22.23.1 | `npm install` in `frontend/`. |
| Python | 3.11.18 (uv venv) | Host 3.14 lacks wheels for several AI dependencies. |

## Test results

| Suite | Command | Result |
|---|---|---|
| Backend | `cd backend && mvn test` (JDK 21) | 27 passed, 0 failed |
| Frontend | `cd frontend && npm test` | 77 passed, 0 failed (17 files) |
| AI service | `cd ai-service && python -m pytest` | 21 passed, 0 failed |
| Frontend build | `cd frontend && npm run build` | passes |

## What already works

- JWT auth (email/phone + optional Google), stateless security, rate limiting.
- Single-user interview lifecycle: JD, resume, question bank, evaluation, report, practice.
- Evidence-grounded rubric (`relevance .15 / technical_depth .30 / correctness .30 / clarity .15 / evidence .10`)
  with quote grounding, injection sanitization and fail-closed malformed output.
- Voice via edge-tts and optional Deepgram; code via Judge0.
- 20 role question banks and 5 coding problems.

## Known gaps this work closes

1. No organizations, cohorts, memberships, assignments or per-tenant scoping.
2. No TPO dashboard, CSV import or cohort analytics.
3. Company-specific tracks are not modelled.
4. Voice requires a paid STT key unless the browser Web Speech API works.
5. Code execution has no fallback when Judge0 is unavailable.
6. The rubric has no human-validated gold set.
7. No consent, retention or data export/delete flow.
8. No usage/billing surface or pilot package.

## Environment caveats

- No paid keys are required for the baseline suites; tests use fakes or heuristics.
- `faster-whisper` is optional at runtime. When it is not installed and no
  `DEEPGRAM_API_KEY` is set, transcription returns a mock provider so the rest of
  the app still boots.
