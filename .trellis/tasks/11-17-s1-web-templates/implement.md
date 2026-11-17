# Implementation

- [x] Four self-built WEB templates under `template-seed/web-*/`: `web-portfolio-minimal` (作品集), `web-product-launch` (营销), `web-event-signup` (活动), `web-doc-site` (内容). Each is a single `index.html` with inlined style and script, system fonts only, no external requests — the preview CSP is `default-src 'none'; script-src 'unsafe-inline'`.
- [x] Templates are real pages, not mockups: work filtering, scroll reveal and copy-email fallback; monthly/yearly price recompute and FAQ accordion; a real countdown plus a form that validates locally and says it is not connected to a server; sidebar search, scrollspy and a persisted light/dark theme.
- [x] Seeding reuses the existing store unchanged: `manifest.json` with `"type": "WEB"`, `entry: index.html`, `files: ["index.html"]`. Changing any template publishes a new immutable version (portfolio is already v2 after its line-height fix).
- [x] `WebFrame`: renders a page at a fixed 1280×800 design viewport and scales it to the container width measured by `ResizeObserver`, so a card cover is the live page rather than a screenshot; the same component serves the dialog at 390×780 for the phone view.
- [x] `WebTemplateCard`: cover iframe runs scripts (counters and themes stay truthful) but ignores pointer events; on hover (140ms intent) an overlay loads the same page with `?peek=1` and the page scrolls itself up and down, which is the web equivalent of the slide carousel.
- [x] `WebPreviewDialog`: desktop/phone viewport switch, real interaction inside the frame, clone form (name defaults to the template name) posting to `POST /api/templates/{slug}/clone`, then `openProject(id)`.
- [x] `WebTemplateGallery` + `.web-*` styles: category filters, `4 套可直接克隆的模板` count, honest footnote about the pipeline status (superseded by 11-18-s1-web-generation, which wired real generation).
- [x] `useHoverPeek` moved to `features/templates/` so slides and web share one intent-delay implementation.
- [x] `TemplateApiTest#webTemplatesArePlayableAndCloneIntoARunnableProject`: catalogue is playable, every bundle serves `text/html` with `<!doctype html>`, clone is typed `WEB` with `kind=TEMPLATE`, and the cloned project's preview body equals the template body byte for byte.

## Verification

- `scripts/api.ps1 -Action verify`: **40 tests, 0 failures** (TemplateApiTest now 5).
- `.cache/web-check.mjs` (headless Chrome 1440×1000): 4 cards with scaled live covers (`scale(0.413)` at 529px), hover peek scrolled the page to the works list within 2.6s, dialog desktop/phone, clone → `#projects/...` detail page with a working preview iframe — screenshots in `./evidence/`.
- Frontend `tsc --noEmit` and `vite build` clean.

## Observed during verification (fixed in this task)

- The first covers ran no scripts, so the event card showed `-- -- -- --` where the countdown should be. Covers now execute the page (still no pointer events), and the card shows real numbers (126 天 22 时 24 分 …).
- At 390px the portfolio headline overlapped between the two lines (`line-height: .96` for CJK). Raised to `1.04` and republished the template as v2; the phone view is now clean.
- Vite's dev server again served a stale transform for `HomePage.tsx` (import present, JSX body missing) which made the gallery invisible; restarting the dev server after clearing `node_modules/.vite` fixed it.

## Open items (not in this task)

- WEB generation from a prompt (model planning, structured `create/update/delete`, build and browser smoke) remains unimplemented in `11-12-s1-web-workbench`; the UI keeps stating it is not open.
- Templates have no `cover.svg`: the catalogue relies on live rendering, so a fallback image is only needed if a page ever fails to render.
- R20's todo tool and kanban web templates are still missing.
