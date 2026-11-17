# Atoms Demo project contract

Status: implementation in progress, 2026-11-16. `apps/web` and `apps/api` include anonymous
projects, template cloning, immutable previews, local slide generation, provider-backed web/video
flows and GAME/source revisions. Version history, exact-version preview, immutable
restore and WEB/GAME ZIP export are implemented. Full S1-S4 acceptance remains open.
Model account readiness is separate from code availability; local provider fixtures are not
evidence of successful paid production generation.
The root Chinese product and implementation documents are the product source of truth.
Preserve Chinese product documentation; code identifiers and layer guidelines use English.

## Scope

React/TypeScript workbench; Java 21/Spring Boot API; MySQL 8.4; isolated Node builder
and trusted document exporter. Both platform and generated apps have anonymous identity,
never account registration. WEB, GAME, SLIDES and VIDEO are all required outputs.
Eight independently invoked agent roles: Mike, Emma, Bob, Alex, David, Iris, Sarah, Adrian.
Iris uses supplied materials only; Adrian creates plans, never purchases or places ads.
Exclude scheduled jobs as a product feature, independent web deep research, model racing,
payments, subscriptions and native mobile apps.

## Required invariants

- Real providers or explicit failure; never pretend examples or timers are generated results.
- Planned implementation waits for approval; all mutations produce immutable versions.
- Published and working versions are separate; source rollback never silently rolls back data.
- Per-project modification tasks are serial; queued work binds its base version on execution.
- Cancelled or stale lease holders cannot commit. External ambiguous submissions are reconciled,
  never blindly resubmitted.
- Anonymous visitor records are scoped to app and preview/live data space. Shared records
  are public-readable but only their creator (or project owner via workbench) may mutate them.
- Global model budget is CNY 300, reserved before each paid request; no automatic paid video retry.
- Source, generated assets and export files persist server-side; secrets never enter generated code.

## Traceability contract

R01 anonymous ownership; R02 real web generation; R03 actual events; R04 interactive preview;
R05 iterative changes; R06 persistence; R07 history; R08 failure/retry; R09 public acceptance;
R10 publish/update; R11 source editor/export; R12 homepage/project management; R13 games;
R14 queue/cancel; R15 eight agents; R16 plan/outline approval; R17 app data API;
R18 data isolation; R19 visual selection/edit; R20 templates; R21 assets;
R22 slide generation/edit/play; R23 PPTX/PDF; R24 text/image video; R25 async video durability;
R26 budget; R27 diagnostics/repair; R28 isolation/limits/responsiveness.
Test Tnn corresponds to Rnn. Use S1-S4 for stages, P01-P11 for pages.

## Authorized work and evidence

The user approved the full document revision plan and requested Trellis initialization and
starting implementation. Do not request the same task/planning approval again.
Do not mark application requirements complete because a specification or skeleton exists.
Task status must distinguish documentation/setup, first runnable foundation and full S1-S4.
Use official source links and distinguish observed site behavior from proposed Demo decisions.
Private Atoms workbench behavior has not been exercised on the empty user account.

## Local tooling

Official Trellis 0.6.17. Local portable Python lives at `.tools/python/python.exe`;
it is ignored, as are `.cache/` downloads. System Python 3.8 is insufficient.
No credentials or model-paid generation are needed for setup. No git commit is authorized
by the product plan; preserve changes for review and disable automatic journal commits.
