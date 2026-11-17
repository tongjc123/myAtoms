# Database guidelines
MySQL 8.4, JDBC parameter binding and Flyway migrations. No generated SQL execution.
Transactions cover claiming, permission checks, budget reservations and pointer changes;
never hold locks during provider, build or export calls. Use row versions for edits and
lease epochs for workers. Persist preview/live namespaces and visitor ownership explicitly.
Use unique operation/run identifiers for billing and successful version commits.
