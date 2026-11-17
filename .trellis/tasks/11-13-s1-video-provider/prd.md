# S1 Volcengine Ark video provider integration

Requirement scope: preparation for R24/R25 (real text/image video, async durability) and R26/R28
(paid calls only after budget reservation; secrets stay server-side). Does not deliver R24 itself.

Dependency: S1 anonymous projects and the v3 documents. Budget ledger must exist before the first
paid call; this task deliberately stops before creating any billable provider task.

Deliver:

- Server-side provider configuration (API key, base URL, model, timeouts, video specification)
  loaded from the ignored `.env` only; never exposed to the browser, generated projects or logs.
- An async adapter for the Ark content-generation task API (create/query/delete) with a stable
  error vocabulary (`PROVIDER_NOT_CONFIGURED`, `PROVIDER_MODEL_NOT_ACTIVATED`,
  `PROVIDER_AUTH_FAILED`, `PROVIDER_RATE_LIMITED`, `PROVIDER_UNREACHABLE`).
- An honest readiness endpoint `GET /api/system/provider` that probes the model with a free
  validation request and reports activation without creating billable work.
- Frontend copy that states the real state instead of implying generation is available.

Not delivered here: run/message persistence for video, budget reservation and settlement, file
download and versioning, publication. Until those exist the workbench must keep saying that
generation is not open.
