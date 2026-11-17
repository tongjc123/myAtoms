import { ApiError, invalidResponse, isRecord, isUtcDate, isUuid, requestJson } from './http'
import { ensureSession, invalidateSession } from './session'

export type RunStatus = 'QUEUED' | 'RUNNING' | 'SUCCEEDED' | 'FAILED' | 'CANCELLED'

export interface RunInfo {
  id: string
  projectId: string
  purpose: string | null
  instruction: string | null
  baseVersionId: string | null
  status: RunStatus
  theme: string | null
  slideCount: number | null
  totalSteps: number
  errorCode: string | null
  errorMessage: string | null
  modelKey: string | null
  spec: { ratio: string; resolution: string; durationSeconds: number } | null
  createdAt: string
  startedAt: string | null
  finishedAt: string | null
}

export interface RunStep {
  id: string
  agent: string
  role: string
  state: 'running' | 'done'
  detail: string
}

export interface RunProgress {
  id: string
  done: number
  total: number
  label: string
}

export interface RunArtifact {
  kind: 'SLIDES' | 'VIDEO' | 'WEB' | 'GAME'
  versionId: string
  versionNo: number
  title: string
  slideCount?: number
  theme?: string
  mediaPath?: string
  entryPath?: string
  tagline?: string
  sections?: number
  tokens?: number
  model?: string
  ratio?: string
  resolution?: string
  durationSeconds?: number
  sizeBytes?: number
}

export type RunEvent =
  | { seq: number; type: 'status'; data: { status: RunStatus; label: string; code: string | null; message: string | null } }
  | { seq: number; type: 'step'; data: RunStep }
  | { seq: number; type: 'progress'; data: RunProgress }
  | { seq: number; type: 'artifact'; data: RunArtifact }

export interface RunSnapshot {
  run: RunInfo
  events: RunEvent[]
}

const statuses: RunStatus[] = ['QUEUED', 'RUNNING', 'SUCCEEDED', 'FAILED', 'CANCELLED']

function parseStatus(value: unknown): RunStatus | null {
  return statuses.find((status) => status === value) ?? null
}

function parseRun(value: unknown): RunInfo {
  if (
    !isRecord(value) || !isUuid(value.id) || !isUuid(value.projectId)
    || !parseStatus(value.status) || !isUtcDate(value.createdAt)
    || typeof value.totalSteps !== 'number'
    || !(value.theme === null || typeof value.theme === 'string')
    || !(value.slideCount === null || typeof value.slideCount === 'number')
    || !(value.instruction == null || typeof value.instruction === 'string')
    || !(value.baseVersionId == null || isUuid(value.baseVersionId))
  ) throw invalidResponse()
  let spec: RunInfo['spec'] = null
  if (isRecord(value.spec) && typeof value.spec.ratio === 'string'
    && typeof value.spec.resolution === 'string' && typeof value.spec.durationSeconds === 'number') {
    spec = {
      ratio: value.spec.ratio, resolution: value.spec.resolution,
      durationSeconds: value.spec.durationSeconds,
    }
  }
  return {
    id: value.id, projectId: value.projectId,
    purpose: typeof value.purpose === 'string' ? value.purpose : null,
    instruction: typeof value.instruction === 'string' ? value.instruction : null,
    baseVersionId: isUuid(value.baseVersionId) ? value.baseVersionId : null,
    status: value.status as RunStatus,
    theme: value.theme as string | null, slideCount: value.slideCount as number | null,
    totalSteps: value.totalSteps,
    errorCode: (value.errorCode as string | null) ?? null,
    errorMessage: (value.errorMessage as string | null) ?? null,
    modelKey: typeof value.modelKey === 'string' ? value.modelKey : null,
    spec,
    createdAt: value.createdAt as string,
    startedAt: (value.startedAt as string | null) ?? null,
    finishedAt: (value.finishedAt as string | null) ?? null,
  }
}

function parseEvent(value: unknown): RunEvent | null {
  if (!isRecord(value) || typeof value.seq !== 'number' || typeof value.type !== 'string') return null
  const data = value.data
  if (!isRecord(data)) return null
  if (value.type === 'status') {
    const status = parseStatus(data.status)
    if (!status || typeof data.label !== 'string') return null
    return {
      seq: value.seq, type: 'status',
      data: {
        status, label: data.label,
        code: typeof data.code === 'string' ? data.code : null,
        message: typeof data.message === 'string' ? data.message : null,
      },
    }
  }
  if (value.type === 'step') {
    if (typeof data.id !== 'string' || typeof data.agent !== 'string' || typeof data.role !== 'string'
      || (data.state !== 'running' && data.state !== 'done') || typeof data.detail !== 'string') return null
    return { seq: value.seq, type: 'step', data: data as unknown as RunStep }
  }
  if (value.type === 'progress') {
    if (typeof data.done !== 'number' || typeof data.total !== 'number' || typeof data.label !== 'string') return null
    return { seq: value.seq, type: 'progress', data: data as unknown as RunProgress }
  }
  if (value.type === 'artifact') {
    if (!isUuid(data.versionId) || typeof data.versionNo !== 'number' || typeof data.title !== 'string') return null
    const kind = data.kind
    if (kind !== 'VIDEO' && kind !== 'WEB' && kind !== 'GAME' && kind !== 'SLIDES') return null
    if (kind === 'SLIDES' && typeof data.slideCount !== 'number') return null
    if (kind === 'VIDEO' && typeof data.mediaPath !== 'string') return null
    if ((kind === 'WEB' || kind === 'GAME') && typeof data.entryPath !== 'string') return null
    return { seq: value.seq, type: 'artifact', data: { ...data, kind } as unknown as RunArtifact }
  }
  return null
}

export function parseSnapshot(value: unknown): RunSnapshot {
  if (!isRecord(value) || !Array.isArray(value.events)) throw invalidResponse()
  const events: RunEvent[] = []
  for (const item of value.events) {
    const event = parseEvent(item)
    if (event) events.push(event)
  }
  return { run: parseRun(value.run), events }
}

export async function fetchLatestRun(projectId: string, signal: AbortSignal): Promise<RunSnapshot | null> {
  try {
    const body = await requestJson(`/api/projects/${encodeURIComponent(projectId)}/runs/latest`, { signal })
    return parseSnapshot(body)
  } catch (error: unknown) {
    if (error instanceof ApiError && error.status === 404) return null
    throw error
  }
}

export async function startSlidesRun(
  projectId: string,
  input: { templateSlug: string | null; importId: string | null },
  signal: AbortSignal,
): Promise<{ runId: string }> {
  return startRun(projectId, { templateSlug: input.templateSlug, importId: input.importId }, signal)
}

export async function startVideoRun(
  projectId: string,
  input: { model: string; ratio: string | null; resolution: string; durationSeconds: number },
  signal: AbortSignal,
): Promise<{ runId: string }> {
  return startRun(projectId, {
    model: input.model, ratio: input.ratio, resolution: input.resolution,
    durationSeconds: input.durationSeconds,
  }, signal)
}

export async function startWebRun(projectId: string, signal: AbortSignal): Promise<{ runId: string }> {
  return startRun(projectId, {}, signal)
}

export interface StartRunInput {
  instruction?: string
  baseVersionId?: string
  templateSlug?: string | null
  importId?: string | null
  model?: string
  ratio?: string | null
  resolution?: string
  durationSeconds?: number
}

export async function startRun(
  projectId: string,
  payload: StartRunInput,
  signal: AbortSignal,
  idempotencyKey: string = crypto.randomUUID(),
): Promise<{ runId: string }> {
  signal.throwIfAborted()
  const session = await ensureSession()
  signal.throwIfAborted()
  try {
    const body = await requestJson(`/api/projects/${encodeURIComponent(projectId)}/runs`, {
      signal,
      method: 'POST',
      headers: { 'Content-Type': 'application/json', 'X-CSRF-Token': session.csrfToken, 'Idempotency-Key': idempotencyKey },
      body: JSON.stringify(payload),
    })
    if (!isRecord(body) || !isUuid(body.runId)) throw invalidResponse()
    return { runId: body.runId }
  } catch (error: unknown) {
    if (error instanceof ApiError && (error.status === 401 || error.code === 'CSRF_REJECTED')) invalidateSession(session)
    throw error
  }
}

export async function cancelRun(projectId: string, runId: string, signal: AbortSignal): Promise<void> {
  signal.throwIfAborted()
  const session = await ensureSession()
  signal.throwIfAborted()
  try {
    await requestJson(`/api/projects/${encodeURIComponent(projectId)}/runs/${encodeURIComponent(runId)}/cancel`, {
      signal,
      method: 'POST',
      headers: { 'Content-Type': 'application/json', 'X-CSRF-Token': session.csrfToken },
      body: '{}',
    })
  } catch (error: unknown) {
    if (error instanceof ApiError && (error.status === 401 || error.code === 'CSRF_REJECTED')) invalidateSession(session)
    throw error
  }
}

/**
 * Follows the server event log. The stream replays stored events first, so a reload continues
 * from the same place instead of losing the steps that already happened.
 */
export async function streamRun(
  projectId: string,
  runId: string,
  after: number,
  onEvent: (event: RunEvent) => void,
  signal: AbortSignal,
): Promise<void> {
  const url = `/api/projects/${encodeURIComponent(projectId)}/runs/${encodeURIComponent(runId)}/events?after=${after}`
  const response = await fetch(url, {
    signal,
    credentials: 'same-origin',
    headers: { Accept: 'text/event-stream' },
    cache: 'no-store',
  })
  if (!response.ok || !response.body) throw new ApiError('无法连接生成过程，请重试。', 'STREAM_FAILED', true)

  const reader = response.body.getReader()
  const decoder = new TextDecoder()
  let buffer = ''
  while (true) {
    const { value, done } = await reader.read()
    if (done) break
    buffer += decoder.decode(value, { stream: true })
    let split = buffer.indexOf('\n\n')
    while (split >= 0) {
      const frame = buffer.slice(0, split)
      buffer = buffer.slice(split + 2)
      for (const line of frame.split('\n')) {
        if (!line.startsWith('data:')) continue
        const payload = line.slice(5).trim()
        if (!payload || payload === '{}') continue
        try {
          const event = parseEvent(JSON.parse(payload))
          if (event) onEvent(event)
        } catch {
          // A malformed frame is skipped; the next reconnect replays from the last good sequence.
        }
      }
      split = buffer.indexOf('\n\n')
    }
  }
}
