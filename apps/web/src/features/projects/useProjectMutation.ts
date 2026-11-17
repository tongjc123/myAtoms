import { useEffect, useRef, useState } from 'react'
import { asApiError } from '../../api/http'
import type { ApiError } from '../../api/http'
import { randomUuid } from '../../api/uuid'

export function useProjectMutation() {
  const [busy, setBusy] = useState(false)
  const [error, setError] = useState<ApiError>()
  const mounted = useRef(false)
  const activeRequest = useRef<AbortController | null>(null)
  const receipt = useRef<{ intent: string; key: string } | null>(null)

  useEffect(() => {
    mounted.current = true
    return () => {
      mounted.current = false
      activeRequest.current?.abort()
    }
  }, [])

  async function run<T>(
    intent: string,
    operation: (key: string, signal: AbortSignal) => Promise<T>,
    onSuccess: (result: T) => void,
  ) {
    // A ref closes the gap before React renders the disabled submit button.
    if (activeRequest.current || !mounted.current) return
    const controller = new AbortController()
    activeRequest.current = controller
    setBusy(true)
    setError(undefined)
    try {
      if (receipt.current?.intent !== intent) {
        receipt.current = { intent, key: randomUuid() }
      }
      const result = await operation(receipt.current.key, controller.signal)
      if (!mounted.current || controller.signal.aborted) return
      receipt.current = null
      onSuccess(result)
    } catch (cause: unknown) {
      if (!mounted.current || controller.signal.aborted) return
      const failure = asApiError(cause)
      if (failure.code === 'IDEMPOTENCY_CONFLICT') receipt.current = null
      setError(failure)
    } finally {
      if (activeRequest.current === controller) activeRequest.current = null
      if (mounted.current) setBusy(false)
    }
  }

  return { busy, error, run, clearError: () => setError(undefined) }
}
