# Logging
Record requestId, projectId, runId, agentTaskId and providerTaskId when applicable.
Never log Cookie, Authorization, API keys, signed URLs or entire confidential prompts.
Emit persisted domain events for user progress; ordinary log lines are not the source of truth.
Log unexpected errors with server-side stack traces and return only sanitized codes to users.
