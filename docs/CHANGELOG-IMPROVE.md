# PlacementOS improvement changelog

Every phase keeps the three suites green and the app runnable with zero paid
keys. Backend verification used JDK 21 (see `docs/BASELINE.md`).

## Phase 0 — Baseline

- Recorded toolchain, suite counts and known gaps in `docs/BASELINE.md`.
- Baseline: backend 27, frontend 77, AI 21 tests passing.

## Phase 1 — Multi-tenant schema

- Added `Organization`, `Membership`, `Cohort`, `Enrollment`, `CompanyTrack`,
  `Assignment`, `AssignmentAttempt`.
- Added nullable `organizationId` to `Interview`, `Report`, `Resume`,
  `JobDescription`; legacy single-user rows keep working with `null`.
- `TenantContext` validates the `X-Organization-Id` header against a membership
  row (never trusts the header). `OrgAuthorization` backs `@PreAuthorize` on TPO
  routes.
- JWTs carry a default `organizationId` claim; login/register return it.
- Hibernate `ddl-auto` creates the same schema on H2 (tests) and MySQL.
- New tests: `PlacementOsFlowTest` (tenant isolation, roles) and
  `RecordingStorageTest`.

## Phase 2 — TPO dashboard, CSV import, assignments

- Endpoints: org create/get, cohorts, students list/import, assignments,
  analytics, CSV export, student assignment list and start.
- CSV import validates rows, skips duplicates, reuses existing accounts, makes
  `STUDENT` memberships + enrollments, and reports `created/skipped/errors`.
- Invites are sent only when `RESEND_API_KEY` is set; otherwise logged.
- Analytics: expected vs completed attempts, per-criterion averages, weakest
  criterion, distribution buckets, attempts per student.
- Frontend: `/tpo`, `/tpo/cohorts/:orgId/:cohortId`,
  `/tpo/assignments/new`, import dialog, analytics cards, and assigned
  interviews on the student dashboard.

## Phase 3 — Company tracks

- Seeded 5 classpath tracks: TCS NQT, Infosys, Wipro, Product SDE, Data Analyst.
- `CompanyTrackService` validates duration, count, sections and weights summing
  to 1.0.
- Starting an assignment copies the track config into `Interview` and maps its
  sections to question types (`aptitude → SITUATIONAL`, `hr → BEHAVIORAL`,
  `coding → CODING`, `system_design → SYSTEM_DESIGN`).
- The backend recomputes each answer score from the rubric criterion scores and
  the track weights, so reports honour the track without letting the model pick
  the final number.

## Phase 4 — Free voice stack

- `faster-whisper` added to `requirements.txt` with a lazy import and on-disk
  cache (`ai-service/.cache/whisper`).
- STT chain: local Whisper → Deepgram (if `DEEPGRAM_API_KEY`) → labelled mock.
- `/transcribe` now returns `transcript`, `language`, `duration`, `provider`,
  `confidence`.
- TTS: edge-tts (default voices `en-US-AriaNeural` / `en-IN-NeerjaNeural`) →
  Piper (`PIPER_MODEL`) → optional Gemini.
- `VoiceRecorder` uploads through the backend, offers a file-upload fallback and
  shows provider/confidence.
- Recording consent is enforced server-side before transcription.
- Verified end-to-end with no paid keys: edge-tts synthesised a 6.9 s clip and
  `faster-whisper:tiny` transcribed it back to text ("I added a composite index
  on tenant ID... then measured the P95 latency").

## Phase 5 — Code execution fallback

- `CodingService` chain: Judge0 → Piston (`PISTON_URL`) → explicit “execution
  unavailable”.
- Piston runtimes are resolved from `/api/v2/runtimes`; limits are 5 s CPU and
  128 MB with no network assumed for the sandbox.
- Results carry `provider`; compile, runtime, timeout and unavailable cases are
  distinguishable in the UI.

## Phase 6 — Evaluation harness

- `eval/build_gold_set.py` generates 200 synthetic examples (10 topics × 5
  tiers × 4 variants).
- `eval/run_eval.py` runs the real rubric when a key exists, otherwise a
  deterministic offline probe that returns the same JSON shape (grounding and
  fail-closed paths still run).
- Metrics: Pearson/Spearman, per-criterion MAE, quote grounding, quote coverage,
  3-run variance, and a name/gender/college bias probe.
- `eval/report.md`: Pearson 0.929, Spearman 0.898, grounding 100%, bias delta 0
  for the offline synthetic set. The report explicitly says this is synthetic,
  not human validation.
- CI job `ai-eval` runs the 20-example smoke set.
- New tests: `tests/test_grounding.py`, `tests/test_privacy.py`,
  `tests/test_voice.py`.

## Phase 7 — Privacy and compliance

- `User`: `consentRecording`, `consentProctoring`, `dataRetentionDays`.
- `/api/me/consent`, `/api/me/data` (GET/DELETE), `/api/me/organizations`.
- Delete removes interviews (sessions, evaluations, reports), resumes,
  job descriptions and recordings, then anonymises the profile; only the email
  needed for login remains.
- Recordings are disk-only under `RECORDINGS_DIR`; the retention sweep is a dry
  run by default and logs the files it would delete.
- Consent dialog gates voice and proctoring; voice endpoints return 409 without
  recording consent; the interview shows a “Recording off” indicator.
- Profile → Privacy has download and delete buttons.

## Phase 8 — Billing and pilot

- `Organization.Plan`: `PILOT`, `STARTER`, `GROWTH`.
- `/api/orgs/{orgId}/usage` reports students, cohorts, assignments, interviews,
  reports, storage and plan limits.
- `/pricing` page with Pilot / Starter / Growth copy.
- `docs/PILOT.md` with timeline, deliverables, success criteria, invoice fields.

## Verification commands

```bash
cd backend && JAVA_HOME=/path/to/jdk21 mvn test
cd frontend && npm test && npm run build && npm run lint
cd ai-service && python -m pytest && python eval/run_eval.py --smoke
```

## Final local result

| Suite | Result |
|---|---|
| Backend (`mvn test`, JDK 21) | 38 passed |
| Frontend (`npm test`) | 80 passed |
| Frontend build / lint | build passes, 0 errors (35 pre-existing warnings) |
| AI service (`pytest`) | 35 passed |
| Eval smoke | Pearson 0.955, grounding 100%, bias delta 0 |
| Free voice | edge-tts → faster-whisper transcribed a 6.9 s clip end-to-end |
| Paid keys used | none |
