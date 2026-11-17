import { invalidResponse, isRecord, isUtcDate, requestJson } from './http'

export const providerStates = [
  'ACTIVATED',
  'NOT_ACTIVATED',
  'MODEL_NOT_FOUND',
  'AUTH_FAILED',
  'UNREACHABLE',
  'UNKNOWN',
  'NOT_CONFIGURED',
] as const

export type ProviderState = (typeof providerStates)[number]

export interface ProviderStatus {
  configured: boolean
  provider: string
  endpointHost: string
  videoModel: string
  state: ProviderState
  detail: string
  checkedAt: string
  defaultDurationSeconds: number
  defaultResolution: string
  textModel: string
  textState: ProviderState
  textDetail: string
  imageModel: string
  imageState: ProviderState
  imageDetail: string
}

function isProviderState(value: unknown): value is ProviderState {
  return typeof value === 'string' && providerStates.some((state) => state === value)
}

export async function fetchProviderStatus(signal: AbortSignal): Promise<ProviderStatus> {
  const body = await requestJson('/api/system/provider', { signal })
  if (
    !isRecord(body)
    || typeof body.configured !== 'boolean'
    || typeof body.provider !== 'string'
    || typeof body.endpointHost !== 'string'
    || typeof body.videoModel !== 'string'
    || !isProviderState(body.state)
    || typeof body.detail !== 'string'
    || !isUtcDate(body.checkedAt)
    || !Number.isSafeInteger(body.defaultDurationSeconds)
    || typeof body.defaultResolution !== 'string'
  ) {
    throw invalidResponse()
  }
  return {
    configured: body.configured,
    provider: body.provider,
    endpointHost: body.endpointHost,
    videoModel: body.videoModel,
    state: body.state,
    detail: body.detail,
    checkedAt: body.checkedAt,
    defaultDurationSeconds: body.defaultDurationSeconds as number,
    defaultResolution: body.defaultResolution,
    textModel: typeof body.textModel === 'string' ? body.textModel : '',
    textState: isProviderState(body.textState) ? body.textState : 'UNKNOWN',
    textDetail: typeof body.textDetail === 'string' ? body.textDetail : '',
    imageModel: typeof body.imageModel === 'string' ? body.imageModel : '',
    imageState: isProviderState(body.imageState) ? body.imageState : 'UNKNOWN',
    imageDetail: typeof body.imageDetail === 'string' ? body.imageDetail : '',
  }
}

/** Wording for the web pipeline's chat model; never claims readiness before the probe agrees. */
export function describeTextModel(status: ProviderStatus): { text: string; ready: boolean } {
  switch (status.textState) {
    case 'ACTIVATED':
      return { text: `网页由 ${status.textModel} 生成（结构规划 + 页面编写两轮调用）。`, ready: true }
    case 'NOT_ACTIVATED':
      return { text: `文本模型 ${status.textModel} 尚未开通，生成会失败。`, ready: false }
    case 'NOT_CONFIGURED':
      return { text: '服务端尚未配置文本模型编号，网页生成暂不可用。', ready: false }
    case 'AUTH_FAILED':
      return { text: '文本模型鉴权失败，请检查服务端密钥配置。', ready: false }
    case 'MODEL_NOT_FOUND':
      return { text: `文本模型 ${status.textModel || '（未填写）'} 不存在或当前账号无权访问。`, ready: false }
    case 'UNREACHABLE':
      return { text: '暂时无法连接模型服务，生成可能失败。', ready: false }
    default:
      return { text: status.textDetail || '文本模型状态待核实。', ready: false }
  }
}

/** Honest, provider-neutral wording: never implies generation is available before it is. */
export function describeProvider(status: ProviderStatus): { text: string; warning: boolean } {
  const spec = `${status.defaultDurationSeconds} 秒 · ${status.defaultResolution}`
  switch (status.state) {
    case 'ACTIVATED':
      return { text: `视频模型 ${status.videoModel} 已就绪（${spec}）。`, warning: false }
    case 'NOT_ACTIVATED':
      return { text: `视频模型 ${status.videoModel} 尚未在服务商控制台开通（${spec}），暂不能生成。`, warning: true }
    case 'NOT_CONFIGURED':
      return { text: '视频服务尚未配置，暂不能生成。', warning: true }
    case 'AUTH_FAILED':
      return { text: '视频服务鉴权失败，请检查服务端密钥配置。', warning: true }
    case 'MODEL_NOT_FOUND':
      return { text: `视频模型 ${status.videoModel || '（未填写）'} 不存在或当前账号无权访问。`, warning: true }
    case 'UNREACHABLE':
      return { text: '暂时无法连接视频服务，稍后可重试检测。', warning: true }
    default:
      return { text: status.detail || '模型状态待核实。', warning: true }
  }
}
