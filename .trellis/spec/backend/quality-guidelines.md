# Backend quality
Compile on Java 21 with a pinned supported Spring Boot release and Maven Wrapper.
Test state transitions, permissions, reservations and real MySQL transactions where changed.
Use provider fakes only in tests; acceptance R02/R24 requires actual provider evidence.
Check stale workers, duplicate requests, queue chaining and unknown video submission states.
This is a new codebase: label proposed conventions and never invent existing examples.
