import { invalidResponse, isRecord, requestJson } from './http'

export interface VideoModelOption {
  key: string
  label: string
  description: string
  badge: string | null
  available: boolean
}

export interface VideoOptions {
  models: VideoModelOption[]
  defaultModel: string | null
  defaultRatio: string | null
  defaultResolution: string
  defaultDurationSeconds: number
  minDurationSeconds: number
  maxDurationSeconds: number
  ratios: string[]
  resolutions: string[]
  providerConfigured: boolean
  providerState: string
  providerDetail: string
}

export interface VideoSpec {
  model: string
  ratio: string | null
  resolution: string
  durationSeconds: number
}

export function specLabel(spec: Pick<VideoSpec, 'ratio' | 'resolution' | 'durationSeconds'>) {
  return `${spec.ratio ?? '智能'} · ${spec.resolution} · ${spec.durationSeconds}s`
}

function parseModel(value: unknown): VideoModelOption {
  if (
    !isRecord(value) || typeof value.key !== 'string' || typeof value.label !== 'string'
    || typeof value.description !== 'string' || typeof value.available !== 'boolean'
  ) throw invalidResponse()
  return {
    key: value.key, label: value.label, description: value.description,
    badge: typeof value.badge === 'string' ? value.badge : null,
    available: value.available,
  }
}

function parseOptions(value: unknown): VideoOptions {
  if (
    !isRecord(value) || !Array.isArray(value.models) || !Array.isArray(value.ratios)
    || !Array.isArray(value.resolutions)
    || typeof value.defaultResolution !== 'string' || typeof value.defaultDurationSeconds !== 'number'
    || typeof value.minDurationSeconds !== 'number' || typeof value.maxDurationSeconds !== 'number'
    || typeof value.providerConfigured !== 'boolean' || typeof value.providerState !== 'string'
    || typeof value.providerDetail !== 'string'
  ) throw invalidResponse()
  return {
    models: value.models.map(parseModel),
    defaultModel: typeof value.defaultModel === 'string' ? value.defaultModel : null,
    defaultRatio: typeof value.defaultRatio === 'string' ? value.defaultRatio : null,
    defaultResolution: value.defaultResolution,
    defaultDurationSeconds: value.defaultDurationSeconds,
    minDurationSeconds: value.minDurationSeconds,
    maxDurationSeconds: value.maxDurationSeconds,
    ratios: value.ratios.filter((ratio): ratio is string => typeof ratio === 'string'),
    resolutions: value.resolutions.filter((item): item is string => typeof item === 'string'),
    providerConfigured: value.providerConfigured,
    providerState: value.providerState,
    providerDetail: value.providerDetail,
  }
}

export async function fetchVideoOptions(signal: AbortSignal): Promise<VideoOptions> {
  return parseOptions(await requestJson('/api/video/options', { signal }))
}
