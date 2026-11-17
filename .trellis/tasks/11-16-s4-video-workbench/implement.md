# Implementation and verification

## What was built

Backend (`apps/api`):

- `V5__video_runs_and_budget.sql` — `runs.model_key/spec_json/provider_task_id`, `provider_spend`.
- `video/VideoCatalog` — server-driven model catalog + spec validation (ratio, resolution 4–30 s).
- `video/VideoPlanner` — punctuation-only storyboard beats and the compiled prompt (original text +
  technical parameters).
- `video/BudgetService` — reserve before the paid call, settle on success, release on failure/cancel.
- `run/RunService` — video branch of the run pipeline (six steps, polling, cancel, download, version).
- `provider/ArkClient` — per-call model id, media download with scheme/host/size guards.
- `project/ProjectVersionWriter` — shared version writer; `SlideDeckWriter` now delegates to it.
- `preview/PreviewController` — byte-range responses for `video/*` and `audio/*`.
- `video/VideoController` — `GET /api/video/options`.

Frontend (`apps/web`):

- `api/videos.ts`, extended `api/runs.ts` (spec/artifact union, `startVideoRun`).
- `features/video/` — `VideoWorkbench`, `VideoComposer` (model + spec menus), `VideoGallery`,
  `videoExamples.ts`.
- `features/chat/` — `VideoPreview`, video artifact card, video skeleton, failed-step rendering.
- `HomePage` routes VIDEO to the new workbench; `styles.css` gained the workbench/menu/gallery
  styles; demo clips live in `public/videos/`.

## Verification

- `scripts/api.ps1 -Action verify`: **39 tests pass**, including
  - `VideoRunApiTest` (3): stub provider success path with `artifact.kind=VIDEO` and a `GENERATED`
    v1, provider payload carrying model/duration/resolution/ratio, preview `200 video/mp4` +
    `Range: bytes=0-99` → `206` with the exact slice; cancel publishes nothing; invalid spec and
    unavailable model rejected; unknown model 400.
  - `VideoBudgetApiTest` (1): exhausted ceiling fails the run with `BUDGET_EXCEEDED` and no artifact.
  - Existing slides tests still pass against the shared version writer and run service.
- `npx tsc --noEmit` and `npm run build` pass.
- `.cache/video-check.mjs` walkthrough (1440×960 headless Chrome):
  1. hero with a playing background video (readyState 4, 1344 px wide),
  2. model menu (Seedance 2.0 selected; 2.5/3.0 disabled with 未接入),
  3. spec menu, then 9:16 · 1080p · 8 s applied and reflected on the chip,
  4. example hover playing plus a visible 使用提示词 button, prompt filled on click,
  5. chat route with live steps and a stop button,
  6. real-provider run ends 生成失败 with `PROVIDER_MODEL_NOT_ACTIVATED` and the activation hint.

## Evidence files

`evidence/01..08` come from the real provider (08 is the honest failure). `evidence/09-succeeded`
and `evidence/10-fullscreen` were captured against a **local stub provider** (`.cache/stub-ark.mjs`,
a real MP4 served as the finished clip) purely to verify the success UI: artifact card
`8 秒 · 9:16 · 1080p · 1.0 MB · 版本 v1`, preview video decoding at 1344×768, fullscreen autoplay.
The stub-backed projects were deleted and their budget rows removed afterwards.

## Gaps

- Image-to-video upload is not exposed: Ark fetches reference images from a public URL, which a
  local-only demo cannot provide.
- Model activation is an operator action in the Ark console; until then every real run fails
  honestly instead of producing a placeholder.
- Per-project spend is a reservation ceiling, not a provider quote; actual invoicing is not read back.
