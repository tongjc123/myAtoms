# Local development tooling

## 1. Scope / Trigger

Windows setup and Trellis task management. This repository started with product documents,
so layer conventions distinguish planned features from actual source examples.

## 2. Signatures

- `powershell -NoProfile -ExecutionPolicy Bypass -File scripts/setup-trellis.ps1 [-Developer admin]`
- `powershell -NoProfile -ExecutionPolicy Bypass -File scripts/trellis.ps1 <action> [task]`
- Actions: `status`, `tasks`, `current`, `start`, `validate`, `finish`, `doctor`.
- Direct CLI: `.tools/python/python.exe .trellis/scripts/task.py --help`.

## 3. Contracts

Setup downloads pinned Python 3.14.10 x64 from python.org and checks the SHA-256 before
extraction. `.tools/python/python314._pth` includes `../../.trellis/scripts` because the
embedded runtime otherwise cannot import Trellis's local modules.
Existing developer identity is preserved. Tools and caches remain inside the repository
and are ignored by Git. Do not change global Python or Codex settings.

The wrapper preserves an existing `TRELLIS_CONTEXT_ID`, otherwise uses `CODEX_THREAD_ID`
or `atoms-local`, and restores the prior value on exit. Independent terminals may explicitly
set different `TRELLIS_CONTEXT_ID` values. `finish` clears only the current pointer; it
does not complete a task. Archive only verified work and pass `--no-commit`.

Context limits are 64 KiB per file and 128 KiB total. The Chinese v3 PRD exceeds the
upstream 32 KiB default; truncating it would omit acceptance requirements.

## 4. Validation & Error Matrix

| Condition | Behavior |
| --- | --- |
| Missing local Python | Task wrapper explains setup command and stops |
| Download hash mismatch | Setup stops before extraction |
| Existing identity | Setup prints it without replacement |
| Invalid context file | Official task validation fails |
| Invalid document/task mapping | `doctor` exits 1 with affected paths |
| Historical audit screenshots absent on another machine | `doctor` reports an informational note; missing checkout-owned links still fail |
| New Git repository without commits | Trellis may warn the named branch has no ref; never fabricate commit evidence |

## 5. Good / Base / Bad Cases

Good: repeat setup offline once the runtime exists; task listing and validation work.
Base: a fresh clone downloads the runtime and initializes a local identity.
Bad: using system Python 3.8 or installing large caches on the nearly full system drive.

## 6. Checks Required

Run setup idempotently, `tasks`, `doctor`, and official `task.py validate` for changed
task contexts. Parse changed PowerShell scripts. These checks do not verify application
behavior or external model availability.

## 7. Wrong vs Correct

Wrong: mark S1 complete after creating the homepage and health endpoint.
Correct: archive only the bounded foundation task after its own checks; leave S1's
generation, persistence, identity and publication acceptance open.

Actual examples: `scripts/setup-trellis.ps1`, `scripts/trellis.ps1`,
`scripts/verify-project.py`. No automatic staging, commits, publication or model calls.
