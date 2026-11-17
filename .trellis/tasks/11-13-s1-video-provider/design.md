# Design

Provider: Volcengine Ark (Doubao), China mainland endpoint
`https://ark.cn-beijing.volces.com/api/v3`. The same asynchronous endpoint family serves the
account's activated 3D model and the Seedance video models, so one adapter shape covers both.

## Configuration (server only)

| Variable | Meaning |
| --- | --- |
| `ATOMS_ARK_API_KEY` | Bearer key, read by the API process only |
| `ATOMS_ARK_BASE_URL` | API base, default `https://ark.cn-beijing.volces.com/api/v3` |
| `ATOMS_ARK_VIDEO_MODEL` | Model ID, e.g. `doubao-seedance-2-0-260128`; empty disables generation |
| `ATOMS_ARK_CONNECT_TIMEOUT` / `ATOMS_ARK_REQUEST_TIMEOUT` | Network bounds |
| `ATOMS_VIDEO_DURATION` / `ATOMS_VIDEO_RESOLUTION` / `ATOMS_VIDEO_RATIO` / `ATOMS_VIDEO_AUDIO` | Product default 5s / 720p / 16:9 / silent |
| `ATOMS_BUDGET_LIMIT_CNY` | Deployment-wide limit (300) reserved before paid calls |

`scripts/api.ps1` allow-lists these names, so `.env` stays the single local source and nothing
leaks into the Maven process environment beyond the listed keys. Readiness responses contain the
endpoint host and model ID only — never the key, headers or prompt payloads.

## Task lifecycle mapping

Ark returns `{id}` on create, then `queued | running | succeeded | failed`, with
`content.video_url`, `usage.completion_tokens` and `error.{code,message}` on query. A successful
provider result is not yet a platform result: the API must download the file into persistent
storage before creating a version (R25). The temporary URL is never the delivered artifact.

## Readiness probe

`POST /contents/generations/tasks` with an empty `content` array is rejected during validation, so
it proves key validity and model activation without scheduling or billing a task. Observed answers
on 2026-11-13 for account 2134386625:

| Model | Answer |
| --- | --- |
| `doubao-seedance-2-5-260628` | 404 `ModelNotOpen` |
| `doubao-seedance-2-0-260128` | 404 `ModelNotOpen` |
| `doubao-seed3d-2-0-260328` | 400 `InvalidParameter` (activated) |

Therefore the adapter maps `NotOpen -> NOT_ACTIVATED`, `InvalidParameter -> ACTIVATED`,
`401/403 -> AUTH_FAILED`, `404 -> MODEL_NOT_FOUND`, network failure -> `UNREACHABLE`. Results are
cached for two minutes; `POST /api/system/provider/refresh` re-probes after an operator change and
requires an anonymous session plus CSRF.

## 3D model note

`doubao-seed3d-2-0-260328` is image-to-3D (obj/glb), not video. It is outside R01–R28 and is not
wired into the workbench. It may be revisited only with an explicit scope change.
