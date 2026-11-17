# Hooks
Use named use-prefixed hooks for lifecycle and shared stateful behavior. Clean up subscriptions,
EventSource and fetches. Treat backend snapshots and event cursors as authoritative; deduplicate
SSE by project sequence and recover from gaps. Never retry paid mutations in render effects.

Actual example: `apps/web/src/features/home/useCapabilities.ts` uses a discriminated loading/
ready/error state, AbortController, a 10-second timeout, unmount cleanup and explicit retry.
It fetches read-only capabilities; it must never start generation as a side effect.
