import { ApiError, invalidResponse, isNonnegativeInteger, isRecord, isUuid } from './http'
import { parseProjectResponse, parseVersion, projectRequest } from './projects'
import type { ProjectVersion } from './projects'
import { ensureSession, invalidateSession } from './session'

export interface HistoryVersion extends ProjectVersion {
  parentVersionId: string | null
  restoredFromVersionId: string | null
  entryPath: string
}

export interface VersionPage {
  items: HistoryVersion[]
  page: number
  pageSize: number
  total: number
  currentVersionId: string | null
}

export function parseVersionPage(body: unknown, page: number, pageSize: number): VersionPage {
  if (!isRecord(body) || !Array.isArray(body.items)
    || body.page !== page || body.pageSize !== pageSize
    || !isNonnegativeInteger(body.total) || body.items.length > pageSize || body.items.length > body.total
    || !(body.currentVersionId === null || isUuid(body.currentVersionId))) throw invalidResponse()
  const items = body.items.map((value: unknown): HistoryVersion => {
    const version = parseVersion(value)
    if (!version || !isRecord(value)
      || !(value.parentVersionId === null || isUuid(value.parentVersionId))
      || !(value.restoredFromVersionId === null || isUuid(value.restoredFromVersionId))
      || typeof value.entryPath !== 'string' || !value.entryPath
      || value.entryPath.length > 300 || value.entryPath.startsWith('/') || value.entryPath.includes('..')
      || value.entryPath.includes('\\')) throw invalidResponse()
    return { ...version, parentVersionId: value.parentVersionId, restoredFromVersionId: value.restoredFromVersionId, entryPath: value.entryPath }
  })
  if (new Set(items.map(item => item.id)).size !== items.length) throw invalidResponse()
  return { items, page, pageSize, total: body.total, currentVersionId: body.currentVersionId }
}

function versionPath(id: string, versionId?: string) {
  return `/api/projects/${encodeURIComponent(id)}/versions${versionId ? `/${encodeURIComponent(versionId)}` : ''}`
}

export async function listVersions(id: string, page: number, pageSize: number, signal: AbortSignal) {
  return parseVersionPage(await projectRequest(`${versionPath(id)}?page=${page}&pageSize=${pageSize}`, signal), page, pageSize)
}

export async function restoreVersion(id: string, versionId: string, expectedCurrentVersionId: string, key: string, signal: AbortSignal) {
  return parseProjectResponse(await projectRequest(`${versionPath(id, versionId)}/restore`, signal, {
    method: 'POST', key, body: { expectedCurrentVersionId },
  }), id)
}

export async function downloadVersionSource(id: string, versionId: string, signal: AbortSignal) {
  const session = await ensureSession()
  signal.throwIfAborted()
  const controller = new AbortController()
  const abort = () => controller.abort()
  signal.addEventListener('abort', abort, { once: true })
  const timer = window.setTimeout(abort, 30_000)
  try {
    const response = await fetch(`${versionPath(id, versionId)}/source.zip`, {
      signal: controller.signal, credentials: 'same-origin', cache: 'no-store', headers: { Accept: 'application/zip' },
    })
    if (!response.ok) {
      const body: unknown = await response.json().catch(() => null)
      if (response.status === 401) invalidateSession(session)
      if (isRecord(body) && typeof body.message === 'string' && typeof body.code === 'string') {
        throw new ApiError(body.message, body.code, body.retryable === true,
          typeof body.requestId === 'string' ? body.requestId : undefined, response.status)
      }
      throw invalidResponse()
    }
    if (response.headers.get('Content-Type')?.split(';')[0].trim() !== 'application/zip') throw invalidResponse()
    const blob = await response.blob()
    signal.throwIfAborted()
    const filename = response.headers.get('Content-Disposition')?.match(/filename="?([A-Za-z0-9_.-]+\.zip)"?(?:;|$)/)?.[1]
      ?? `project-${id}-${versionId}.zip`
    const url = URL.createObjectURL(blob)
    const link = document.createElement('a')
    link.href = url
    link.download = filename
    document.body.append(link)
    link.click()
    link.remove()
    window.setTimeout(() => URL.revokeObjectURL(url), 1000)
  } catch (cause: unknown) {
    signal.throwIfAborted()
    if (cause instanceof ApiError) throw cause
    throw new ApiError(controller.signal.aborted ? '源码下载超时，请重试。' : '源码下载失败，请检查连接后重试。',
      controller.signal.aborted ? 'TIMEOUT' : 'NETWORK_ERROR', true)
  } finally {
    window.clearTimeout(timer)
    signal.removeEventListener('abort', abort)
  }
}
