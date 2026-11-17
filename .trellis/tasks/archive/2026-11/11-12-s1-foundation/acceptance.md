# Foundation acceptance — 2026-11-12

Result: the bounded foundation is verified. S1 and R01-R28 remain open.

## Delivered

- React/TypeScript Chinese homepage, four output selectors, temporary prompt input,
  truthful project empty state and accessible responsive navigation.
- Java 21/Spring Boot API health and capabilities, with generation explicitly unavailable.
- Pinned NPM lockfile, Maven Wrapper with distribution checksum, local-only development
  bindings and README startup commands.
- No model requests, credentials, database setup or public publication.

## Executed checks

| Check | Result |
| --- | --- |
| Frontend `npm run build` | Passed; strict TypeScript and Vite production build |
| API `mvnw.cmd ... verify` | Passed; Java compilation, 3 HTTP integration tests and executable JAR |
| Actual local API | Health UP; capabilities false with explicit reason and no secrets |
| Browser checks | 21 passed; 1440×900 and 390×844; no runtime exceptions |
| Browser interaction | Four type selections, disabled generation, project navigation and no horizontal overflow |
| Mobile accessibility | Initial drawer focus, Tab containment, Escape/focus restoration, desktop resize release |
| Failure/recovery | Blocked capability request and non-JSON response display Chinese errors; explicit retry recovers |
| Independent review | Fixed mobile drawer keyboard/background handling and normalized network/parser errors |

The three API tests cover health without a database, generation remaining unavailable
even with a sentinel provider key, and denial of configuration diagnostic endpoints.
Spring Boot health-group names are permitted; secrets/component details are not.

Evidence: [desktop](./evidence/home-desktop.png), [phone](./evidence/home-phone.png),
[browser results](./evidence/browser-checks.json).
Browser request interception was used only for failure checks, never for successful generation.
Development StrictMode's aborted first request may produce a discarded interception ID;
the evidence records those separately from page runtime exceptions.

## Limits and next slice

The page does not create/save projects, call models or implement any generated-content editor.
There is no anonymous ownership/data persistence yet. An initial homepage is not a visual
or behavioral acceptance of the whole Atoms workbench. Next: `11-12-s1-anonymous-projects`
for real MySQL sessions/project CRUD and cross-browser isolation.
