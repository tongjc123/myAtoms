import { isProjectType } from './capabilities'
import type { ProjectType } from './capabilities'
import { ApiError, invalidResponse, isNonnegativeInteger, isRecord, isUtcDate, isUuid, requestJson } from './http'
import { ensureSession, invalidateSession } from './session'

export type ProjectMode = 'BUILD' | 'PLAN'
export type VersionKind = 'TEMPLATE' | 'GENERATED' | 'EDIT' | 'RESTORE'

export interface ProjectVersion {
  id: string
  number: number
  kind: VersionKind
  summary: string
  createdAt: string
}

export interface ProjectTemplateRef {
  slug: string
  name: string
}

export interface Project {
  id: string
  name: string
  type: ProjectType
  prompt: string
  mode: ProjectMode
  status: 'DRAFT' | 'READY'
  revision: number
  createdAt: string
  updatedAt: string
  currentVersion: ProjectVersion | null
  sourceTemplate: ProjectTemplateRef | null
}

export interface PreviewSession {
  baseUrl: string
  entryPath: string
  expiresAt: string
}

export interface ProjectPage {
  items: Project[]
  page: number
  pageSize: number
  total: number
}

export interface CreateProject {
  name: string
  type: ProjectType
  prompt: string
  mode: ProjectMode
}

const versionKinds: VersionKind[] = ['TEMPLATE', 'GENERATED', 'EDIT', 'RESTORE']

export function parseVersion(value: unknown): ProjectVersion | null {
  if (value === null || value === undefined) return null
  if (
    !isRecord(value) || !isUuid(value.id) || !isNonnegativeInteger(value.number) || value.number < 1
    || !versionKinds.includes(value.kind as VersionKind)
    || typeof value.summary !== 'string' || !value.summary.trim() || value.summary.length > 200
    || !isUtcDate(value.createdAt)
  ) throw invalidResponse()
  return {
    id: value.id, number: value.number, kind: value.kind as VersionKind,
    summary: value.summary, createdAt: value.createdAt,
  }
}

function parseTemplateRef(value: unknown): ProjectTemplateRef | null {
  if (value === null || value === undefined) return null
  if (
    !isRecord(value) || typeof value.slug !== 'string' || !/^[a-z0-9][a-z0-9-]{1,47}$/.test(value.slug)
    || typeof value.name !== 'string' || !value.name.trim() || value.name.length > 80
  ) throw invalidResponse()
  return { slug: value.slug, name: value.name }
}

export function parseProject(value: unknown): Project {
  if (
    !isRecord(value) || !isUuid(value.id)
    || typeof value.name !== 'string' || !value.name.trim() || value.name.length > 80
    || !isProjectType(value.type)
    || typeof value.prompt !== 'string' || !value.prompt.trim() || value.prompt.length > 8000
    || (value.mode !== 'BUILD' && value.mode !== 'PLAN')
    || (value.status !== 'DRAFT' && value.status !== 'READY')
    || !isNonnegativeInteger(value.revision) || value.revision < 1
    || !isUtcDate(value.createdAt) || !isUtcDate(value.updatedAt)
  ) throw invalidResponse()
  return {
    id: value.id, name: value.name, type: value.type, prompt: value.prompt,
    mode: value.mode, status: value.status, revision: value.revision,
    createdAt: value.createdAt, updatedAt: value.updatedAt,
    currentVersion: parseVersion(value.currentVersion),
    sourceTemplate: parseTemplateRef(value.sourceTemplate),
  }
}

export function parseProjectResponse(value: unknown, expectedId?: string): Project {
  if (!isRecord(value)) throw invalidResponse()
  const project = parseProject(value.project)
  if (expectedId && project.id !== expectedId) throw invalidResponse()
  return project
}

export async function projectRequest(path: string, signal: AbortSignal, mutation?: {
  method: 'POST' | 'PATCH' | 'DELETE'
  body: CreateProject | { name: string; expectedRevision: number } | { expectedRevision: number } | { expectedCurrentVersionId: string }
  key: string
}): Promise<unknown> {
  signal.throwIfAborted()
  const session = await ensureSession()
  signal.throwIfAborted()
  if (mutation && !isUuid(mutation.key)) throw new ApiError('请求标识无效，请重新尝试。', 'INVALID_KEY')
  try {
    return await requestJson(path, {
      signal,
      ...(mutation ? {
        method: mutation.method,
        headers: {
          'Content-Type': 'application/json',
          'X-CSRF-Token': session.csrfToken,
          'Idempotency-Key': mutation.key,
        },
        body: JSON.stringify(mutation.body),
      } : {}),
    })
  } catch (error: unknown) {
    if (error instanceof ApiError && (
      error.status === 401 || error.code === 'CSRF_REJECTED'
    )) invalidateSession(session)
    // No automatic mutation replay or anonymous identity replacement on error.
    throw error
  }
}

export async function listProjects(page: number, pageSize: number, signal: AbortSignal): Promise<ProjectPage> {
  const body = await projectRequest(`/api/projects?page=${page}&pageSize=${pageSize}`, signal)
  if (
    !isRecord(body) || !Array.isArray(body.items)
    || body.page !== page || body.pageSize !== pageSize
    || !isNonnegativeInteger(body.total) || body.items.length > pageSize || body.items.length > body.total
  ) throw invalidResponse()
  return { items: body.items.map(parseProject), page, pageSize, total: body.total }
}

export async function getProject(id: string, signal: AbortSignal): Promise<Project> {
  return parseProjectResponse(await projectRequest(`/api/projects/${encodeURIComponent(id)}`, signal), id)
}

export async function createProject(body: CreateProject, key: string, signal: AbortSignal): Promise<Project> {
  return parseProjectResponse(await projectRequest('/api/projects', signal, { method: 'POST', body, key }))
}

export async function renameProject(id: string, name: string, expectedRevision: number, key: string, signal: AbortSignal): Promise<Project> {
  return parseProjectResponse(await projectRequest(`/api/projects/${encodeURIComponent(id)}`, signal, {
    method: 'PATCH', body: { name, expectedRevision }, key,
  }), id)
}

export async function deleteProject(id: string, expectedRevision: number, key: string, signal: AbortSignal): Promise<void> {
  const body = await projectRequest(`/api/projects/${encodeURIComponent(id)}`, signal, {
    method: 'DELETE', body: { expectedRevision }, key,
  })
  if (body !== undefined) throw invalidResponse()
}

// A preview is opened through a short-lived token instead of the owner cookie:
// the sandboxed iframe runs in an opaque origin and cannot carry it.
export async function createPreviewSession(id: string, signal: AbortSignal, versionId?: string): Promise<PreviewSession> {
  signal.throwIfAborted()
  const session = await ensureSession()
  signal.throwIfAborted()
  const body = await requestJson(`/api/projects/${encodeURIComponent(id)}/preview-sessions`, {
    signal,
    method: 'POST',
    headers: { 'Content-Type': 'application/json', 'X-CSRF-Token': session.csrfToken },
    body: JSON.stringify(versionId ? { versionId } : {}),
  })
  if (
    !isRecord(body)
    || typeof body.baseUrl !== 'string' || !/^\/api\/previews\/[A-Za-z0-9_-]{20,}\/$/.test(body.baseUrl)
    || typeof body.entryPath !== 'string' || !/^[A-Za-z0-9._/-]{1,300}$/.test(body.entryPath)
    || body.entryPath.includes('..')
    || !isUtcDate(body.expiresAt) || Date.parse(body.expiresAt) <= Date.now()
  ) throw invalidResponse()
  return { baseUrl: body.baseUrl, entryPath: body.entryPath, expiresAt: body.expiresAt }
}
