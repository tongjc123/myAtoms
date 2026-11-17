# Error handling
Use {code,message,retryable,requestId,details?}; details are sanitized validation data.
Provider unconfigured/unavailable is an explicit failure, never a mock success.
Preserve previous successful artifacts. Retry creates a linked run; external ambiguous
submission requires reconciliation, not automatic paid resubmission. Keep cancellation distinct
from success and failure. VERSION_CONFLICT and PROJECT_BUSY must be visible to clients.
