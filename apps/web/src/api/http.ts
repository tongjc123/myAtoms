export function isRecord(value: unknown): value is Record<string, unknown> {
  return typeof value === 'object' && value !== null && !Array.isArray(value)
}

export function isUuid(value: unknown): value is string {
  return typeof value === 'string' && /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i.test(value)
}

export function isUtcDate(value: unknown): value is string {
  return typeof value === 'string'
    && /^\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2}(?:\.\d{1,9})?Z$/.test(value)
    && Number.isFinite(Date.parse(value))
}

export function isNonnegativeInteger(value: unknown): value is number {
  return typeof value === 'number' && Number.isSafeInteger(value) && value >= 0
}

export class ApiError extends Error {
  constructor(
    message: string,
    readonly code: string,
    readonly retryable = false,
    readonly requestId?: string,
    readonly status?: number,
  ) {
    super(message)
    this.name = 'ApiError'
  }
}

export function invalidResponse(): ApiError {
  return new ApiError('服务返回了无法识别的数据，请重试或重新连接。', 'INVALID_RESPONSE', true)
}

export function asApiError(error: unknown): ApiError {
  return error instanceof ApiError
    ? error
    : new ApiError('暂时无法完成请求，请稍后重试。', 'REQUEST_FAILED', true)
}

export interface RequestOptions extends RequestInit {
  timeoutMs?: number
}

// Requests stay on the workbench origin. The browser supplies Origin and the
// HttpOnly cookie; neither owner credentials nor CSRF tokens go into storage.
export async function requestJson(path: string, options: RequestOptions = {}): Promise<unknown> {
  const controller = new AbortController()
  const abort = () => controller.abort()
  const parentSignal = options.signal
  parentSignal?.throwIfAborted()
  parentSignal?.addEventListener('abort', abort, { once: true })
  const timeout = window.setTimeout(abort, options.timeoutMs ?? 10_000)
  try {
    const response = await fetch(path, {
      ...options,
      signal: controller.signal,
      credentials: 'same-origin',
      cache: 'no-store',
      headers: { Accept: 'application/json', ...options.headers },
    })
    let body: unknown
    if (response.status !== 204) {
      try {
        body = await response.json()
      } catch {
        throw invalidResponse()
      }
    }
    if (!response.ok) {
      if (
        isRecord(body) && typeof body.code === 'string' && body.code.length > 0
        && typeof body.message === 'string' && body.message.length > 0
        && typeof body.retryable === 'boolean' && typeof body.requestId === 'string'
      ) {
        throw new ApiError(body.message, body.code, body.retryable, body.requestId, response.status)
      }
      throw invalidResponse()
    }
    return body
  } catch (error: unknown) {
    parentSignal?.throwIfAborted()
    if (controller.signal.aborted) {
      throw new ApiError('请求超时，请重试。保存结果可能已生效，重试会核对同一次请求。', 'TIMEOUT', true)
    }
    if (error instanceof ApiError) throw error
    throw new ApiError('暂时无法连接服务，请检查连接后重试。', 'NETWORK_ERROR', true)
  } finally {
    window.clearTimeout(timeout)
    parentSignal?.removeEventListener('abort', abort)
  }
}
