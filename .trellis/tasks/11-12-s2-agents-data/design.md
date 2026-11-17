# Design: S2 complete editing and collaboration

Follow root implementation document sections 2-9 and the Rnn/page/API/data/test matrix.
Keep Java/Spring and MySQL as the sole product business backend. Generated code stays isolated.
Reuse project/run/version/asset/event/budget contracts across types. Keep working and published versions separate.
Start queued edits from the actual previous successful version; enforce stale lease and cancelled commit guards.
Changing a public contract requires updating both v3 documents and the relevant layer specification.
Roll back deployment/artifact pointers to the prior successful version; never automatically roll back user records.
