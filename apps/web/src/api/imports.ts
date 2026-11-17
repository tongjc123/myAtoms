import { ApiError, invalidResponse, isRecord, isUtcDate, isUuid, requestJson } from './http'
import { ensureSession, invalidateSession } from './session'

export interface ImportedTemplate {
  id: string
  name: string
  filename: string
  slideCount: number
  parseState: 'PARSED' | 'STORED_ONLY'
  outlineTitles: string[]
  note: string
  createdAt: string
}

function parseImport(value: unknown): ImportedTemplate {
  if (
    !isRecord(value) || !isUuid(value.id)
    || typeof value.name !== 'string' || !value.name.trim()
    || typeof value.filename !== 'string'
    || typeof value.slideCount !== 'number'
    || (value.parseState !== 'PARSED' && value.parseState !== 'STORED_ONLY')
    || !Array.isArray(value.outlineTitles) || typeof value.note !== 'string'
    || !isUtcDate(value.createdAt)
  ) throw invalidResponse()
  return {
    id: value.id, name: value.name, filename: value.filename, slideCount: value.slideCount,
    parseState: value.parseState, outlineTitles: value.outlineTitles.filter((item): item is string => typeof item === 'string'),
    note: value.note, createdAt: value.createdAt,
  }
}

export async function listImports(signal: AbortSignal): Promise<ImportedTemplate[]> {
  const body = await requestJson('/api/templates/imports', { signal })
  if (!isRecord(body) || !Array.isArray(body.items) || body.items.length > 20) throw invalidResponse()
  return body.items.map(parseImport)
}

export async function uploadImport(file: File, name: string, signal: AbortSignal): Promise<ImportedTemplate> {
  signal.throwIfAborted()
  const session = await ensureSession()
  signal.throwIfAborted()
  const form = new FormData()
  form.append('file', file)
  if (name.trim()) form.append('name', name.trim())
  try {
    const body = await requestJson('/api/templates/imports', {
      signal,
      method: 'POST',
      headers: { 'X-CSRF-Token': session.csrfToken },
      body: form,
      timeoutMs: 60_000,
    })
    return parseImport(body)
  } catch (error: unknown) {
    if (error instanceof ApiError && (error.status === 401 || error.code === 'CSRF_REJECTED')) invalidateSession(session)
    throw error
  }
}
