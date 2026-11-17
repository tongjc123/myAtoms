# Foundation service capability contract

## 1. Scope / Trigger

The homepage needs real connection state before any generation pipeline exists.
This boundary is implemented; project persistence and paid generation are not.

## 2. Signatures

`GET /api/system/capabilities`, `GET /actuator/health`.
Both are read-only, anonymous and require no database or provider key.
Vite proxies `/api` and `/actuator` to `http://127.0.0.1:8080`.

## 3. Contracts

```json
{
  "generationAvailable": false,
  "reasonCode": "PIPELINE_NOT_IMPLEMENTED",
  "message": "创作服务已连接。生成能力正在接入，暂时还不能创建作品。",
  "projectTypes": ["WEB", "GAME", "SLIDES", "VIDEO"]
}
```

`projectTypes` lists the planned type vocabulary, not delivered functionality.
Capabilities return `Cache-Control: no-store`. No configuration or key can turn
`generationAvailable` on in this foundation. Frontend submission remains disabled.
Health returns `status=UP` and may include Spring Boot health-group names, with no
component details. Only health is exposed by Actuator.

Actual implementation: `CapabilitiesController.java`, `src/api/capabilities.ts` and
`src/features/home/useCapabilities.ts` under their respective app source roots.
Java 21, Spring Boot 4.1.2, Maven Wrapper 3.3.4 / Maven 3.9.16;
React 19.3.1, TypeScript 5.9.3, Vite 8.3.8. Node must satisfy Vite's engine requirement.
NPM lockfile and Maven distribution checksum are part of the project.

## 4. Validation & Error Matrix

| Condition | Result |
| --- | --- |
| HTTP 200, valid capabilities | Connected; generation unavailable |
| HTTP failure | Visible connection error with explicit retry |
| Invalid JSON/field types | Error; never assume generation is available |
| Request exceeds 10 seconds | Abort and show timeout |
| Unmounted component | Abort and ignore late callbacks |
| Env/configprops endpoint request | 404; configuration is not exposed |

## 5. Good / Base / Bad Cases

Good: change output type, enter a local unsaved prompt, see the true service status.
Base: no database/model setup; health and capabilities still work.
Bad: interpreting a green connection indicator or a provider key as successful generation.

## 6. Required Checks

`npm run build` includes strict type checking. `mvnw.cmd verify` runs real HTTP tests
for health without a database, unavailable generation even with a sentinel key, and
private diagnostic endpoints. Browser checks cover offline/retry, output selection,
desktop/phone overflow and disabled submission.

## 7. Wrong vs Correct

Wrong: synthesize progress, recent projects or successful generation after a timer.
Correct: render the unavailable reason and require a later implemented, verified pipeline
before enabling submission. Model integration must include budget reservation first.
