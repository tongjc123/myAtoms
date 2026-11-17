# Project generation and revision contract

## 1. Scope / Trigger

R02/R05/R13: project-page WEB/GAME generation and instruction edits.
SLIDES/VIDEO use their existing generation implementations from the same project page.
The task is `11-16-s1-project-generation`; full eight-agent collaboration, exports,
application data and public publication remain separate.

## 2. Signatures

- `POST /api/projects/{id}/runs`, optional UUID `Idempotency-Key`.
- Request retains `templateSlug/importId/model/ratio/resolution/durationSeconds`;
  adds nullable `instruction` and `baseVersionId`.
- `GET /api/projects/{id}/runs/latest` and `GET .../{runId}` return persisted snapshots.
- `POST .../{runId}/cancel` requests cancellation.
- `BudgetService.reserveText/reserve`, `markSubmitted`, `settle`, `release`.
- V7 adds the singleton `provider_budget_lock` and `provider_spend.submitted`.

## 3. Contracts

WEB/GAME edit sends both a trimmed nonempty instruction (maximum 8000 characters) and
the current version UUID. The provider receives the actual saved source and edit instruction.
An edit must not substitute a fresh template or truncate the previous source silently.
No instruction means fresh generation. Media types reject source-edit fields.

Project starts are serialized; network work runs outside transactions after the accepted
run is committed. Final commit revalidates run cancellation, live project and captured base.
Starts lock owner session before project, matching restore/idempotency lock order;
the run's owner foreign key otherwise introduces an implicit lock inversion.
Old versions stay intact. GAME artifacts use `kind:GAME`, `entryPath:index.html`.
Run snapshots expose nullable instruction/baseVersionId so a refreshed view can show the request.

Budget reservations use a global row lock before reading the sum and inserting a reservation.
Reservations use a new transaction so they cannot inherit a stale repeatable-read snapshot.
Amounts must be positive and exactly representable in cents; sub-cent reservations are rejected.
`markSubmitted` commits before the first paid network request. `release` only frees a reservation
whose submitted flag is false. Ambiguous failed/cancelled submissions stay RESERVED.
Settlement currently records the configured reservation, not the supplier's exact bill;
fixed amounts must not be described as verified worst-case prices or proof of an exact cash cap.
Historical supplier charges still require reconciliation.

## 4. Validation & Error Matrix

| Condition | Outcome |
| --- | --- |
| Other owner's/deleted project | 404 |
| Base no longer current | 409 VERSION_CONFLICT |
| Another run active | 409 RUN_IN_PROGRESS |
| Invalid/partial edit fields | 400 VALIDATION_ERROR |
| Same request key, different operation | 409 IDEMPOTENCY_CONFLICT |
| Missing model setup or provider rejection | Explicit provider failure, no fabricated artifact |
| No positive per-run reserve | BUDGET_NOT_CONFIGURED before calling provider |
| Deployment reservation limit exhausted | BUDGET_EXCEEDED before calling provider |
| Cancel accepted before version commit | No new successful version |

## 5. Good / Base / Bad Cases

Good: clone the space game, request a different speed/colour, persist a new version using
that clone as base, then issue a second edit from the updated version.
Base: create a new WEB/GAME project and generate its first standalone HTML version.
Bad: refreshing an old editor and overwriting a newer version, or calling a model without
budget reservation and marking a local failure as zero supplier cost.

## 6. Tests Required

Real MySQL plus local provider fixtures: GAME output, edit source inclusion, successive
versions, stale base, isolation, duplicate submission, cancellation near commit, failure
preserving the old version. `BudgetReservationTest` races two reservations for the last
available unit and verifies submitted failure retention. Frontend builds and browser checks
cover all four entries, preserved edit input, provider failures and mobile layout.
Fixture success is not evidence that a live provider account can generate.

## 7. Wrong vs Correct

Wrong: enable a GAME button while the backend still rejects GAME; label media as unavailable
when only their project-page entry is missing; release money after an ambiguous paid attempt.
Correct: dispatch each output to its real implementation, show its actual engine/readiness,
and retain uncertain costs until reconciled.
