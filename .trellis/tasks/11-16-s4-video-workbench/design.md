# Design: video workbench and provider-backed run

## Surfaces

- `GET /api/video/options` — catalog of models (`key|label|description|modelId|badge` from
  `atoms.provider.video-models`), the defaults, the allowed ratios/resolutions/duration window and
  the cached provider readiness. An entry without a model id is returned as `available:false`; the
  menu renders it disabled with a "未接入" tag instead of guessing an identifier.
- `POST /api/projects/{id}/runs` with `{model, ratio, resolution, durationSeconds}` — `ratio: null`
  means "智能" and the field is omitted from the provider request so the provider adapts the frame.
- `GET /api/previews/{token}/video.mp4` — same one-time token as the deck preview, now range-aware.

## Run pipeline

Six steps, each written to `run_events` after it actually ran:

1. **Mike · 需求解析** — subject taken from the visitor's own sentences.
2. **Alex · 分镜脚本** — beats cut on sentence/clause punctuation only; long clauses are split, the
   cap is four beats, nothing is invented.
3. **Emma · 提示词编译** — the original text plus `规格：N 秒，分辨率，画幅`; no rewriting.
4. **Iris · 任务提交** — budget reservation, then `POST /contents/generations/tasks`; the task id is
   stored on the run so a stop can delete the remote job.
5. **Sarah · 模型生成** — polls every `atoms.provider.poll-interval` (default 3 s) until
   `succeeded`/`failed`, emitting a progress event with the elapsed wait; the wait is capped by
   `atoms.provider.video-timeout` (default 10 m) and then deleted as timed out.
6. **David · 下载入库** — downloads the media (http(s) only, private hosts refused unless explicitly
   allowed, 256 MB cap), stores `video.mp4` in one transaction with the version pointer.

Failure mapping is preserved: `ProviderException`/`ApiException` write `error_code` and
`error_message` to the run and emit a `status` event that carries both, so a page that started
before the failure still shows the real reason instead of a generic line.

## Budget

`provider_spend` holds one row per run: `RESERVED` before the paid call, `SETTLED` on success,
`RELEASED` on failure/cancel. The ceiling is `atoms.budget.limit-cny` (default ¥300), the per-task
reservation is `atoms.budget.video-reserve-cny` (default ¥6, a safety ceiling rather than a quote).
A reservation that would cross the ceiling raises `BUDGET_EXCEEDED` inside the run.

## Frontend

- `VideoWorkbench` — hero with a locally hosted MP4 (`public/videos/hero-racetrack.mp4`), soft
  radial scrim, composer card with backdrop blur, and a provider-status note when the model is not
  activated.
- `VideoComposer` — textarea, kind chip, model dropdown, spec popover (6 ratios + 智能 toggle,
  resolution segmented control, 4–30 s range), circular submit. Menus dismiss on outside click or
  Escape.
- `VideoGallery` — two local example clips; hover plays and reveals 「使用提示词」, which writes the
  exact prompt that produced the clip back into the composer.
- `ChatPage` — the same thread/step/stop layout as slides, with a video artifact card
  (`时长 · 画幅 · 分辨率 · 体积 · 版本`), a `<video controls>` preview through the preview token and a
  fullscreen player. Steps still running when the run failed are rendered as 未完成, and failures
  show the run's real message plus the activation hint for `PROVIDER_MODEL_NOT_ACTIVATED`.
