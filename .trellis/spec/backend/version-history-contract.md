# Version history, restore and source export

## 1. Scope / trigger

R07 version history/restore for WEB, GAME, SLIDES and VIDEO; R11 source export
for WEB/GAME. Source editing and public publishing remain separate work.
Read this contract when changing version selection or any current-version mutation.

## 2. Signatures

- GET `/api/projects/{id}/versions?page=0&pageSize=10`.
- POST `/api/projects/{id}/versions/{versionId}/restore`, UUID Idempotency-Key,
  `{expectedCurrentVersionId}` -> 201 `{project}`.
- POST `/api/projects/{id}/preview-sessions`, optional `{versionId}`; omitted
  version retains current-version behavior.
- GET `/api/projects/{id}/versions/{versionId}/source.zip` -> attachment.
- V8 adds `project_versions.restored_from_version_id`.

## 3. Contracts

History response: `{items,page,pageSize,total,currentVersionId}`. Each item has
`id,number,kind,summary,createdAt,parentVersionId,restoredFromVersionId,entryPath`.
Order is descending version number. All routes check the anonymous project owner.
Selecting a historical version never changes the current pointer.

Restore serializes against generation with the same project row lock. Authoritative
reads must see work committed while waiting for that lock; an earlier repeatable-read
snapshot is insufficient. Check active runs and expected current version before writing.
`VersionService.restore` opens READ_COMMITTED before invoking `IdempotencyService`.
Operations that insert owner-referencing rows lock the anonymous session before
the project: restore/idempotency and `RunService.start` share this order. A run
INSERT takes a session foreign-key lock even if application code does not explicitly
read that session; project-first insertion can deadlock against a restore.
Create a RESTORE version: parent is the previous working version, restoredFrom is
the selected historical version; copy files unchanged in the same transaction.
Do not call a provider, rewrite historical rows, or revert business data.
Repeat the same idempotency key and body to recover a lost response.

Preview consumers must send their actual version ID. Merely depending on versionId
in a React effect while requesting the latest preview can show the wrong artifact.
Generated HTML stays in a sandboxed iframe; video uses the existing media player.

ZIP uses exact stored files, safe relative paths, at most 100 files and 20 MiB
uncompressed. Use an ASCII server-generated attachment filename and no-store.
No provider calls, generated-code execution, or new version is needed for export.

## 4. Validation / error matrix

| Condition | Result |
| --- | --- |
| Missing, foreign or deleted project/version | 404 |
| Invalid request/pagination | 400 |
| Stale expected current version | 409 VERSION_CONFLICT |
| Active run | 409 PROJECT_BUSY |
| Selected version already current | 409 VERSION_ALREADY_CURRENT |
| Missing files/entry | 409 VERSION_CONTENT_UNAVAILABLE |
| Same idempotency key, different command | 409 IDEMPOTENCY_CONFLICT |
| Export SLIDES/VIDEO as source | 400 UNSUPPORTED_PROJECT_TYPE |
| ZIP exceeds file/byte limit | 409 SOURCE_EXPORT_TOO_LARGE |

## 5. Good / base / bad cases

Base: list a cloned game's v1 and download its HTML in a ZIP.
Good: restore v1 while v2 is current, resulting in v3 with parent=v2 and source=v1;
refresh and verify all three versions still exist.
Bad: restore from a stale editor, overwrite v2 in place, serve another project's
version via a guessed UUID, or return the latest media for an old chat artifact.

## 6. Tests required

MySQL tests must assert immutable content including binary media; ownership; missing
content; duplicate and conflicting keys; stale bases after lock contention; busy
projects; preview binding; safe ZIP paths, limits and byte-identical entries.
Browser checks must cover confirmation/cancel, current markers, historical preview,
refresh, uncertain retry, download errors, paging, empty history and phone layout.
Real local slides can establish the complete restore flow without paid model calls.

## 7. Wrong vs correct

Wrong: change current_version_id back to an old row and lose the restoration history.
Correct: append a RESTORE version and keep both lineage references.

Wrong: resend a timed-out restore with a new key and a newly fetched base.
Correct: retry the exact frozen operation/key, then explicitly refresh current state.
