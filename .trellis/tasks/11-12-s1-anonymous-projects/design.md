# Design

Add JDBC/Flyway and MySQL configuration to the existing Java API. Build anonymous owner
sessions, project ownership checks, migrations and a repository layer. Cookie secrets
stay out of browser storage and are stored as hashes server-side.

Follow the v3 contract for TTL, CSRF, Origin checks, soft deletion and request idempotency.
Local HTTP development may use an explicitly local cookie profile; production requires
Secure host-only cookies and independent preview/application origins.

Define draft-only project creation explicitly until real task submission exists. The
frontend must show that draft state; never return fabricated run IDs or enable generation.
Keep the existing capability endpoint truthful. Use real MySQL for ownership and persistence
integration checks; an in-memory substitute cannot prove these contracts.

## Change boundary and verified environment (2026-11-12)

Currently the homepage is an unsaved composer; the API has only health/capabilities.
Implement actual ownership/persistence in apps/api (JDBC repositories, Flyway migration,
session and project HTTP services) and consume it in apps/web (API boundary, project
list/details, create/rename/delete). Root scripts and README provide local configuration.
No generation, model calls, published content or application-record database in this slice.

The user started an existing `mysql8` container: MySQL 8.0.36 at 127.0.0.1:3310.
Use new `atoms_demo_dev` and `atoms_demo_test` schemas and separate least-privilege
application/test users. Preserve all unrelated databases and container configuration.
Local verification is explicitly 8.0.36; target MySQL 8.4 deployment compatibility remains
to be verified before public acceptance. SQL uses common MySQL 8 InnoDB capabilities.

## HTTP contract (frontend/backend shared)

- `POST /api/session`: bootstrap or renew anonymous identity; validates Origin; no login.
  Returns `{sessionId, csrfToken, expiresAt}`. HttpOnly host-only owner Cookie (30-day sliding
  lifetime); frontend keeps csrfToken in memory. GET /api/session returns same shape or 401.
- All project APIs require the cookie. Mutations additionally require allowed Origin,
  `X-CSRF-Token`, `Idempotency-Key` (UUID), and application/json.
- `POST /api/projects`: `{name,type,prompt,mode}` -> 201 `{project}`.
  type WEB/GAME/SLIDES/VIDEO; mode BUILD/PLAN; trimmed name 1–80 and prompt 1–8000 characters.
  This explicitly creates a DRAFT, with no run ID and no generation.
- `GET /api/projects?page=0&pageSize=20`: `{items,page,pageSize,total}`, pageSize 1–50.
  Order updatedAt descending then id; only active projects of the current owner.
- `GET /api/projects/{id}`: `{project}`.
- `PATCH /api/projects/{id}`: `{name,expectedRevision}` -> `{project}`.
- `DELETE /api/projects/{id}`: JSON `{expectedRevision}` -> 204.
- Project shape: `{id,name,type,prompt,mode,status:"DRAFT",revision,createdAt,updatedAt}`.
  Dates are ISO-8601 UTC strings. IDs are UUID strings.
- Error shape: `{code,message,retryable,requestId}`. 401 SESSION_REQUIRED/SESSION_EXPIRED,
  403 ORIGIN_REJECTED/CSRF_REJECTED, 404 PROJECT_NOT_FOUND (also other owners/deleted IDs),
  409 VERSION_CONFLICT/IDEMPOTENCY_CONFLICT, 400 VALIDATION_ERROR, 503 STORAGE_UNAVAILABLE.
- A mutation locks its owning session in a short transaction, validates/loads its
  idempotency receipt and project revision, mutates and writes the receipt atomically.
  Identical repeats replay status/body while the referenced project remains accessible;
  after deletion, create/rename receipt replay returns 404 without old content and cannot
  recreate the project. Delete receipt replay remains 204. Different payload/route with
  the same key gives 409.
  Delete is soft and immediately removes private access; no production runs exist yet.
- Capabilities adds `projectManagementAvailable:true` while retaining
  `generationAvailable:false`. Health now requires the configured database.

Production cookies default to Secure + __Host- prefix; only explicit local configuration
disables Secure and uses `atoms_owner`. Server validates an explicit Origin allowlist;
there is no wildcard CORS or client-controlled owner ID.

## Frontend interactions

Retain observed homepage styling, type selectors, service/error states and mobile navigation.
Replace disabled generation submit with a clearly labelled “保存项目草稿” action; explain
that this saves the requirement and does not generate. Open saved draft details after creation,
including original requirement, type/mode and saved state. Provide project list/pagination,
detail open/refresh, explicit rename form and delete confirmation. Refresh must reload the
current project (hash routing is sufficient). Conflicts preserve input and offer refresh.
Prevent duplicate in-flight mutations; retries reuse a key only for the same intended request.
Session bootstrap must be shared so React StrictMode cannot race two identities.
