# Contract and change boundary

Behavior gap lives in version APIs and project detail UI, not generation buttons.
Reuse immutable project_versions/project_files, owner sessions, idempotency service
and project row lock. No provider calls or dependency additions.

## HTTP

- GET `/api/projects/{id}/versions?page=0&pageSize=10`:
  `{items:[{id,number,kind,summary,createdAt,parentVersionId,restoredFromVersionId,entryPath}],
  page,pageSize,total,currentVersionId}`.
- POST `/api/projects/{id}/versions/{versionId}/restore`, UUID Idempotency-Key,
  JSON `{expectedCurrentVersionId}` -> 201 `{project}` (standard Project).
  Replay same operation returns original receipt, not a new restore.
- POST `/api/projects/{id}/preview-sessions`: optional JSON `{versionId}`.
  Empty/absent body retains current-version compatibility.
- GET `/api/projects/{id}/versions/{versionId}/source.zip`: WEB/GAME only;
  `application/zip`, attachment with server-generated ASCII filename, no-store.

Missing/foreign/deleted project or version -> 404. Invalid input -> 400.
Stale base -> 409 VERSION_CONFLICT; queued/running work -> 409 PROJECT_BUSY.
Restore same current version -> 409 VERSION_ALREADY_CURRENT. Restore corrupt/missing
entry -> 409 VERSION_CONTENT_UNAVAILABLE. ZIP other types -> 400 UNSUPPORTED_PROJECT_TYPE.

## Storage and concurrency

V8 adds nullable restored_from_version_id to project_versions with self FK.
Restore is one transaction: existing idempotency receipt/session serialization,
then project row lock, fresh current/base and active-run checks, insert RESTORE,
copy exact stored files (including binary), move current pointer, save receipt.
Do not use an earlier repeatable-read snapshot to authorize/check current pointer
after waiting for lock. Use READ_COMMITTED for restore transaction if needed.
Parent is previous working version; restoredFrom identifies selected version.
Copy source hash and entry path; require source files and entry to exist. Do not
materialize video content in application heap during restore (INSERT SELECT).
History uses stable version_no DESC paging. Export bounded to 100 files/20MiB
and safe relative paths using existing MediaTypes; this accommodates inline image
HTML while avoiding unconstrained ZIP memory use. No generated code execution.

## Ownership

Backend worker: new history controller/service/repository, V8, preview service/controller,
backend tests. Avoid changing existing run code unless proven necessary.
Frontend worker: versions API decoder, history component, ProjectDetail/ProjectPreview,
project API optional preview-version argument, CSS, browser fixture tests.
Main: task/spec/docs, integration review, real browser smoke and service restart.

No broad refactoring. Current generation and current-version preview must remain compatible.
Historical preview and restore are server-authorized; source download cannot render HTML
inside the workbench. Immutable version ID is the UI selection identity.
