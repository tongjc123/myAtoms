# Implementation

- [x] Bind provider settings server-side (`ProviderProperties`, `application.yaml`, `.env.example`).
- [x] Allow-list provider variables in `scripts/api.ps1` and pin the local API port to 8080.
- [x] Add `ArkClient` for create/query/delete with a public error vocabulary and no key logging.
- [x] Add cached readiness probe and `GET /api/system/provider` (`no-store`, no secrets).
- [x] Surface the real model state on the homepage for the video output only.
- [x] Stub-server tests for request shape, image mode, task query and every error mapping (9 cases).
- [ ] Activate a Seedance model for the Ark account, then re-probe and record the evidence.
- [ ] Budget reservation and settlement before the first paid call (R26).
- [ ] Video run persistence, download-to-storage and version creation (R24/R25).

Verification performed on 2026-11-13: `scripts/api.ps1 -Action verify` -> 27 tests pass;
`npm run build` (type check + bundle) passes; live `GET /api/system/provider` returns
`state=NOT_ACTIVATED` for `doubao-seedance-2-0-260128`. No billable provider task was created.
