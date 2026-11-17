import { useEffect, useState } from 'react'
import { fetchCapabilities } from '../../api/capabilities'
import type { Capabilities } from '../../api/capabilities'

type ServiceState =
  | { status: 'loading' }
  | { status: 'ready'; capabilities: Capabilities }
  | { status: 'error'; message: string }

export function useCapabilities() {
  const [state, setState] = useState<ServiceState>({ status: 'loading' })
  const [attempt, setAttempt] = useState(0)

  useEffect(() => {
    const controller = new AbortController()
    let disposed = false
    const timeout = window.setTimeout(() => controller.abort(), 10_000)
    setState({ status: 'loading' })
    fetchCapabilities(controller.signal)
      .then((capabilities) => {
        if (!disposed) setState({ status: 'ready', capabilities })
      })
      .catch((error: unknown) => {
        if (!disposed) {
          setState({
            status: 'error',
            message: controller.signal.aborted
              ? '连接超时，请稍后重试。'
              : error instanceof Error
                ? error.message
                : '暂时无法连接创作服务。',
          })
        }
      })
      .finally(() => window.clearTimeout(timeout))
    return () => {
      disposed = true
      controller.abort()
      window.clearTimeout(timeout)
    }
  }, [attempt])

  return { state, retry: () => setAttempt((value) => value + 1) }
}
