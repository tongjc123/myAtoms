# S4 video workbench, spec menus, example prompts and provider-backed run

Requirement scope: R24 (text/image to video), R25 (asynchronous job tracking), R26 (budget), R13 (versioned output and preview), R16 (cancellation), R28 (isolation).

Source of truth: root product/implementation v3 documents and `.trellis/spec/project-contract.md`.

Goal: the video entry on the home page becomes a working loop — a motion background, one composer with model and specification menus, example clips whose prompt can be reused, and a conversation page that follows the real provider job, can be stopped, and plays the finished MP4.

Hard constraints carried from the contract:

- No fabricated output. The run submits a real task to Volcengine Ark; when the account cannot serve it, the run fails with a fixed error code and the reason is shown in the page. There is no placeholder video and no "demo result".
- Steps are server facts first: each step is written to `run_events` after it executed, and the SSE stream replays the log before following it.
- Cancellation is real: the polling loop checks `cancel_requested` every 120 ms, deletes the remote task and releases the budget reservation; a cancelled run publishes no version.
- Paid calls are preceded by a budget reservation; secrets stay server-side.
- Out of scope: image-to-video uploads (the provider needs a publicly reachable image URL), multi-turn editing, and auto-activation of models.

Acceptance (verified by `VideoRunApiTest`, `VideoBudgetApiTest` and `.cache/video-check.mjs`):

1. A video run against a stub provider reaches `SUCCEEDED`, stores `video.mp4` as a `GENERATED` version, and the preview endpoint serves it as `video/mp4` with `Range` support (206 + `Content-Range`).
2. The submitted provider payload carries the compiled prompt, chosen model id, duration, resolution and ratio.
3. Cancelling leaves no artifact and no current version.
4. Invalid ratio/resolution/duration and unavailable catalog entries are rejected before a run is queued.
5. An exhausted budget fails the run with `BUDGET_EXCEEDED` before any provider call.
6. With the real account (no Seedance model activated) the run ends `FAILED` with `PROVIDER_MODEL_NOT_ACTIVATED` and the page says so.
