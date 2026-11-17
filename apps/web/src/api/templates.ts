import type { ProjectType } from './capabilities'
import { ApiError, invalidResponse, isNonnegativeInteger, isRecord, isUuid, requestJson } from './http'
import { parseProjectResponse } from './projects'
import type { Project } from './projects'
import { ensureSession, invalidateSession } from './session'

export interface TemplateItem {
  id: string
  slug: string
  name: string
  type: ProjectType
  category: string
  summary: string
  description: string
  author: string
  sourceUrl: string | null
  licenseNote: string | null
  versionId: string
  versionNo: number
  playUrl: string
  coverUrl: string | null
  playable: boolean
  theme: string | null
  slideCount: number
}

const slugPattern = /^[a-z0-9][a-z0-9-]{1,47}$/
const playUrlPattern = /^\/api\/template-previews\/[a-z0-9][a-z0-9-]{1,47}\/[A-Za-z0-9._/-]{1,300}$/

function parseTemplate(value: unknown): TemplateItem {
  if (
    !isRecord(value) || !isUuid(value.id) || typeof value.slug !== 'string' || !slugPattern.test(value.slug)
    || typeof value.name !== 'string' || !value.name.trim() || value.name.length > 80
    || (value.type !== 'WEB' && value.type !== 'GAME' && value.type !== 'SLIDES' && value.type !== 'VIDEO')
    || typeof value.category !== 'string' || !value.category.trim() || value.category.length > 32
    || typeof value.summary !== 'string' || !value.summary.trim() || value.summary.length > 200
    || typeof value.description !== 'string' || !value.description.trim() || value.description.length > 2000
    || typeof value.author !== 'string' || !value.author.trim() || value.author.length > 80
    || !(value.sourceUrl === null || (typeof value.sourceUrl === 'string' && value.sourceUrl.length <= 300))
    || !(value.licenseNote === null || (typeof value.licenseNote === 'string' && value.licenseNote.length <= 120))
    || !isUuid(value.versionId) || !isNonnegativeInteger(value.versionNo) || value.versionNo < 1
    || typeof value.playUrl !== 'string' || !playUrlPattern.test(value.playUrl) || value.playUrl.includes('..')
    || !(value.coverUrl === null || (typeof value.coverUrl === 'string' && value.coverUrl.startsWith('/api/template-previews/') && !value.coverUrl.includes('..')))
    || typeof value.playable !== 'boolean'
    || !(value.theme === null || (typeof value.theme === 'string' && /^[a-z]{3,16}$/.test(value.theme)))
    || !isNonnegativeInteger(value.slideCount)
  ) throw invalidResponse()
  return {
    id: value.id, slug: value.slug, name: value.name, type: value.type, category: value.category,
    summary: value.summary, description: value.description, author: value.author,
    sourceUrl: value.sourceUrl as string | null, licenseNote: value.licenseNote as string | null,
    versionId: value.versionId, versionNo: value.versionNo, playUrl: value.playUrl,
    coverUrl: value.coverUrl as string | null, playable: value.playable,
    theme: value.theme as string | null, slideCount: value.slideCount,
  }
}

export async function listTemplates(type: ProjectType, signal: AbortSignal): Promise<TemplateItem[]> {
  const body = await requestJson(`/api/templates?type=${encodeURIComponent(type)}`, { signal })
  if (!isRecord(body) || !Array.isArray(body.items) || body.items.length > 100) throw invalidResponse()
  return body.items.map(parseTemplate)
}

export async function cloneTemplate(slug: string, name: string, key: string, signal: AbortSignal): Promise<Project> {
  if (!slugPattern.test(slug) || !isUuid(key)) throw new ApiError('请求标识无效，请重新尝试。', 'INVALID_KEY')
  signal.throwIfAborted()
  const session = await ensureSession()
  signal.throwIfAborted()
  try {
    const body = await requestJson(`/api/templates/${encodeURIComponent(slug)}/clone`, {
      signal,
      method: 'POST',
      headers: {
        'Content-Type': 'application/json',
        'X-CSRF-Token': session.csrfToken,
        'Idempotency-Key': key,
      },
      body: JSON.stringify({ name }),
    })
    return parseProjectResponse(body)
  } catch (error: unknown) {
    if (error instanceof ApiError && (error.status === 401 || error.code === 'CSRF_REJECTED')) invalidateSession(session)
    throw error
  }
}
