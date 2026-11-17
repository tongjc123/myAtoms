# Implementation

- [x] `V3__templates_and_versions.sql`: `templates`, `template_versions`, `template_files`, `project_versions`, `project_files`, `preview_sessions`; `projects.current_version_id` / `source_template_version_id`.
- [x] Classpath seeder reads `template-seed/<slug>/manifest.json`, hashes `(path, sha256(content))` and publishes an immutable version only when content changes.
- [x] Four self-built canvas games (star-swarm, neon-drift, sky-hopper, block-2048) with keyboard + pointer input, scoring, restart and SVG covers; no external assets.
- [x] `GET /api/templates?type=` and `GET /api/templates/{slug}` public read-only catalogue.
- [x] `POST /api/templates/{slug}/clone` with session + Origin + CSRF + idempotency key; copies `template_files` into `project_files` and writes `project_versions` v1 inside one transaction.
- [x] Preview surface: `GET /api/template-previews/{slug}/{path}` (5-minute public cache) and `POST /api/projects/{id}/preview-sessions` + `GET /api/previews/{token}/{path}` (no-store, 30-minute token).
- [x] Path hardening in `MediaTypes.normalize` plus fixed CSP (`default-src 'none'`, `connect-src 'none'`, `frame-ancestors 'self'`) on every preview response.
- [x] Frontend gallery with category chips, cover cards, sandboxed play dialog, clone dialog with progress and error states.
- [x] Project detail shows the real version preview, source template and version facts; project list badge reflects `READY`.
- [x] Shareable deep links: `/?type=GAME&template=star-swarm` opens one example directly.
- [x] `TemplateApiTest` (4 cases) covers public catalogue, traversal rejection, mutation guards, clone idempotency, isolation and token preview.
- [ ] WEB todo/board templates required by R20.
- [ ] Real generation producing `GENERATED` versions through the same version/preview path.

Verification on 2026-11-14: `scripts/api.ps1 -Action verify` -> 31 tests pass;
`npm run build` (type check + bundle) passes; headless Chrome walkthrough completed
gallery -> play -> clone -> project preview against the running stack, screenshots kept
in `.cache/ui/`.
