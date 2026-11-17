# Backend development guidelines

These are approved initial conventions for a greenfield project. Existing foundation examples
are explicitly identified; database, generation and worker conventions remain prospective.
Read the project contract and implementation document before feature work.

## Pre-Development Checklist
- Read [structure](./directory-structure.md), [database](./database-guidelines.md) and [errors](./error-handling.md).
- Read [logging](./logging-guidelines.md) for provider/worker changes.
- Confirm task requirements and the appropriate Rnn/Tnn contract.
- For the existing health/capability boundary, read [the executable contract](./capabilities-contract.md).
- For generation, source revisions and budget reservation, read [the project contract](./project-generation-contract.md).
- For historical previews, restoration and source ZIPs, read [the version contract](./version-history-contract.md).

## Quality Check
Follow [quality](./quality-guidelines.md). Verify permissions, bounded costs, true states and restart behavior.
Add references to real examples when implementations land; do not fabricate examples to fill a checklist.
