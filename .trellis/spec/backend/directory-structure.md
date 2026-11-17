# Backend directory structure
Status: approved greenfield convention; implementation examples are added as code lands.
Use apps/api with Java package com.deepwistom.atoms. Organize by feature (session, project,
run, agent, version, publication, asset, appdata, slides, video, budget). Keep HTTP DTOs,
application services and JDBC repositories separate inside each feature. Flyway migrations
belong in src/main/resources/db/migration. Never run generated code in the API process.

Actual foundation examples:
- `apps/api/src/main/java/com/deepwistom/atoms/AtomsApplication.java`: application entry point.
- `apps/api/src/main/java/com/deepwistom/atoms/system/CapabilitiesController.java`: read-only DTO boundary.
- `apps/api/src/test/java/com/deepwistom/atoms/SystemBoundaryTest.java`: real HTTP boundary tests.
Implemented feature packages now include session/project, template/preview, importer/slides,
run, video and web. JDBC repositories and numbered Flyway migrations are present.
`RunService` coordinates the current in-process workers; this is not a durable leased worker
system. Read [generation contracts](./project-generation-contract.md) before changing dispatch
or version commits. Full agent orchestration, appdata and public publication remain planned.
The `version` package implements history, restore and source ZIPs; V8 adds restore
lineage. Read [version contracts](./version-history-contract.md) for shared lock order.
