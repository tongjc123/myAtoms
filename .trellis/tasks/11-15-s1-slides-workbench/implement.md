# Implementation

- [x] `V4__runs_and_imports.sql`: `runs`, `run_events` (`UNIQUE(run_id, seq)`), `template_imports` (content MEDIUMBLOB, outline JSON, parse_state).
- [x] `SlideDeck` / `DeckRenderer`: single-file `index.html` with inlined style, engine and spec, because a sandboxed opaque-origin iframe cannot load external CSS/JS under `default-src 'none'`.
- [x] Shared engine `slide-engine/deck.css|deck.js`: 1280×720 stage with transform scaling, eight layouts, four themes, `?peek=1` auto-advancing carousel, cover title size ladder by headline length.
- [x] `SlidePlanner`: deterministic regrouping of the visitor's prompt or imported outline; claim selection skips logistics sentences; metrics regex ignores bare years; `prettyTitle` strips instruction shells.
- [x] `SlideEngine` / `SlideDeckWriter`: version, files and project pointer written in one transaction; failure keeps the previous version.
- [x] `POST /api/projects/{id}/runs` (202) with single-active-run guard, type check, template theme inheritance and import outline loading.
- [x] Six-step pipeline (Mike/Alex/Emma/Iris/Sarah/David) emitting `step`, `progress`, `artifact`, `status` events; 120ms slices check `cancel_requested`.
- [x] `GET /api/projects/{id}/runs/{runId}/events` SSE: replay stored events by `seq` cursor, then follow new rows every 250ms until terminal.
- [x] `POST /api/projects/{id}/runs/{runId}/cancel`: idempotent, publishes nothing, project stays retryable.
- [x] `POST /api/templates/imports` + `GET /api/templates/imports`: ≤8MB, `.pptx`/`.pdf` only, pptx text outline via `ppt/slides/slideN.xml`, pdf stored with page count.
- [x] Four self-built slide templates (`slide-quarterly-review`, `slide-brand-launch`, `slide-portfolio-minimal`, `slide-tech-vision`) seeded from `deck.json` with `"engine": "slide-deck"`.
- [x] Frontend: `SlidesWorkbench`, `SlideGallery` (category filters), `SlideTemplateCard` (hover peek, 140ms intent delay), `SlideImportTile`, `SlidePreviewDialog`, `SlideComposer` (chip + seeded editable request + `deriveName`), `ChatPage` + `useRunStream` (snapshot then follow, stop, step timeline, artifact card, thumbnail rail, fullscreen overlay).
- [x] `SlidesRunApiTest`: 4 tests covering success path and event-log invariants, cancel leaves no version, type/overlap/isolation guards, import validation and a pptx-driven run.

## Verification

- `scripts/api.ps1 -Action verify`: **35 tests, 0 failures** (includes the 4 new slides/run tests).
- `apps/web`: `tsc --noEmit` clean.
- `.cache/slides-check.mjs` (headless Chrome, 1440×960): 4 template cards + import tile, hover carousel rendering a real slide, chip injection, streaming steps with a stop button, `已完成` artifact card `7 页 · 169 KB · v1`, thumbnail rail with 7 thumbnails, fullscreen overlay — screenshots in `./evidence/`.

## Observed during verification (fixed in this task)

- The cover title was the raw prompt truncated at 34 chars, which overflowed the 1280px stage in three lines. Title now comes from the visitor's label through `prettyTitle`, and the engine lowers the cover font in two steps when the headline is long.
- The chip injected `使用此幻灯片模板来帮我创建演示：` — a dangling fragment. The composer now seeds a complete, editable request mentioning the template name, and an untouched seed names the project after the template instead of the seed sentence.

## Open items (not in this task)

- Model-based planning, outline approval and per-slide editing remain in `11-12-s3-slides`.
- PPTX/PDF export and budget reservation remain unimplemented; they must fail explicitly until built.
- pdf imports are stored only; text extraction of pdf is not implemented.
