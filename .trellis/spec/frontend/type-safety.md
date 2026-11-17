# Type safety
Enable TypeScript strict; avoid any and unchecked assertions on API payloads.
Use discriminated project/run/asset types. Validate network and iframe messages at boundaries.
postMessage requires exact source, origin, nonce and schema checks. Do not trust client-supplied
owner IDs, project scope or resource paths. Keep OpenAPI and client contracts synchronized.

Actual boundary example: `apps/web/src/api/capabilities.ts` parses response JSON as `unknown`
and checks field types and the project-type union before constructing `Capabilities`.
