# Design / shared contract

Behavior belongs in apps/api run/web/project services and apps/web project/chat views,
not a cosmetic enabled flag. Reuse the existing provider, sandbox, version and event flow.

## HTTP
- POST /api/projects/{id}/runs continues to start all four types.
- StartRequest gains optional instruction:string (trimmed 1–8000) and baseVersionId:UUID.
  Both required together for WEB/GAME modification. Others reject modification fields.
- WEB/GAME no instruction => fresh generation; instruction => edit the exact current version.
- POST uses optional Idempotency-Key UUID for compatibility with existing clients;
  new UI must send one and reuse it on uncertain retry. Response remains {runId,status}.
- Run snapshot adds instruction and baseVersionId (nullable). Artifact.kind supports GAME;
  GAME/WEB artifacts share entryPath and version fields.
- 409 VERSION_CONFLICT on stale base; 409 RUN_IN_PROGRESS for overlapping run.
  Persist accepted instruction/base, do not replace the original project prompt.
- Project-level lock serializes starts. Enqueue only after commit. Final version writer
  locks project/run and checks cancellation, deletion and base version in one transaction.
- Initial generation uses the current version (possibly null) as a captured base too.
- Existing model failure mappings and ownership/CSRF checks remain enforced.

## Generation
Game-specific prompts require genuine input, score/win or loss, restart, mobile controls,
inline JS/CSS, no external assets. Editing includes actual current HTML, project request and
the new instruction; overlarge context gets an explicit error. Do not silently truncate code.
No automatic hero generation for games or edits. Keep previous successful version on failure.

## Budget
Global reservation lock row prevents concurrent SUM+INSERT overspend. Validate positive
per-run reservation. Retain the reservation on ambiguous failure/cancel after submission;
settle records the configured reservation, not an exact supplier invoice. Explain this limit.
No claims that fixed reservation is a verified monetary quote. Tests use local model stubs.

## Boundaries
Backend changes: run types/submission/context, web/game prompts, version commit guard,
Flyway append-only migrations and tests. Frontend changes: project run composer, game artifact,
follow-up edit composer/history fields and truthful copy. Main owns budget changes/docs.
No provider calls or build work inside database transactions.
