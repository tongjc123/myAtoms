# Anonymous sessions and persistent projects

Next S1 slice after the runnable foundation. Covers the initial portions of R01/R06/R12/R28.

Acceptance:
- A visitor can create, list, open, rename and delete their own project without login.
- MySQL persistence survives refresh and API restart.
- A second browser cannot access the first visitor's private project or mutate it by ID.
- Empty names, malformed project types, duplicate requests and deleted IDs have explicit outcomes.
- Project creation saves a draft only in this slice; it does not fake a generation run.
- The homepage and project screen use actual API data and handle loading/error/empty states.
- No paid calls or account login. Full generation and publication remain separate S1 work.

Depends on the foundation. Requires a reachable development MySQL 8.4 instance; no account
or server purchase is authorized by this task. Initial environment had Docker installed
but its daemon unavailable.
