# S1 slides workbench, template peek and observable generation run

Requirement scope: R02 (real generation, no faked results), R13 (versioned output and preview), R16 (cancellation), R20 (template catalogue), R21 (imported material), R28 (isolation).

Source of truth: root product/implementation v3 documents and `.trellis/spec/project-contract.md`.

Goal: the slides entry on the home page becomes a working loop — pick a template (with a live carousel preview) or import a .pptx/.pdf, describe the deck, watch every step as it actually executes, stop if needed, then open the generated version with a thumbnail rail and fullscreen view.

Hard constraints carried from the contract:

- No fabricated output. The deck is produced by a deterministic layout engine that only regroups the visitor's own words; there is no model call, no invented statistic, no stock imagery. The UI, the version summary and this record say so.
- Every progressive step is a server-side fact first: a step is written to `run_events` after it ran, and the SSE stream replays that log before following it. A reloaded page therefore resumes at the true state.
- Cancellation must be real: `cancel_requested` is checked between slices, and a cancelled run publishes no version.
- Secrets stay server-side; the pipeline currently costs nothing.
- Out of scope for this task: model planning, outline approval, per-slide element editing, PPTX/PDF export, budget reservation. Those stay in `11-12-s3-slides` and must fail explicitly, not silently.

Acceptance (verified by `apps/api/src/test/java/com/deepwistom/atoms/SlidesRunApiTest.java` and `.cache/slides-check.mjs`):

1. A slides run reaches `SUCCEEDED` with a monotonic event log containing status/step/progress/artifact, and publishes `project_versions` v1 with `kind=GENERATED`.
2. The published version renders as a self-contained `index.html` (no external CSS/JS) whose cover title comes from the visitor's words, not a truncated prompt.
3. Cancel ends the run as `CANCELLED`, emits no artifact and leaves `currentVersion` empty; the project can start a new run afterwards.
4. Runs on non-SLIDES projects and overlapping runs are rejected with a stable error code; another visitor sees 404.
5. Imports reject unsupported extensions with `VALIDATION_ERROR`, require a session, and a parsed pptx outline can drive a run.
6. Browser check covers: four template cards plus the import tile, hover carousel showing a real slide, chip injection into the composer, streaming steps with a stop button, the finished artifact card, the thumbnail rail and the fullscreen overlay.
