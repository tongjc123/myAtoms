import { invalidResponse, isRecord, isUtcDate, isUuid, requestJson } from './http'

interface AnonymousSession {
  sessionId: string
  csrfToken: string
  expiresAt: string
}

let currentSession: AnonymousSession | undefined
let initialization: Promise<AnonymousSession> | undefined

export function invalidateSession(session?: AnonymousSession) {
  if (!session || currentSession === session) currentSession = undefined
}

export function ensureSession(): Promise<AnonymousSession> {
  if (initialization) return initialization
  if (currentSession && Date.parse(currentSession.expiresAt) > Date.now() + 60_000) {
    return Promise.resolve(currentSession)
  }

  // Deliberately independent of component cancellation: StrictMode subscribers
  // must share one bootstrap and cannot race two Set-Cookie responses.
  initialization = requestJson('/api/session', {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: '{}',
  }).then((body) => {
    if (
      !isRecord(body) || !isUuid(body.sessionId)
      || typeof body.csrfToken !== 'string' || !body.csrfToken.trim()
      || !isUtcDate(body.expiresAt) || Date.parse(body.expiresAt) <= Date.now()
    ) throw invalidResponse()
    currentSession = {
      sessionId: body.sessionId,
      csrfToken: body.csrfToken,
      expiresAt: body.expiresAt,
    }
    return currentSession
  }).finally(() => {
    initialization = undefined
  })
  return initialization
}
