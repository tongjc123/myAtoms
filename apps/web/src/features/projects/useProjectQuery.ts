import { useCallback, useEffect, useState } from 'react'
import { asApiError } from '../../api/http'
import type { ApiError } from '../../api/http'

type QueryState<T> =
  | { status: 'idle' }
  | { status: 'loading' }
  | { status: 'ready'; data: T }
  | { status: 'error'; error: ApiError }

export function useProjectQuery<T>(load: (signal: AbortSignal) => Promise<T>, enabled: boolean, retainData = false) {
  const [state, setState] = useState<QueryState<T>>({ status: 'idle' })
  const [refreshError, setRefreshError] = useState<ApiError>()
  const [attempt, setAttempt] = useState(0)
  useEffect(() => {
    if (!enabled) {
      setState({ status: 'idle' })
      return
    }
    const controller = new AbortController()
    let disposed = false
    setRefreshError(undefined)
    setState((current) => retainData && current.status === 'ready' ? current : { status: 'loading' })
    load(controller.signal)
      .then((data) => {
        if (!disposed) setState({ status: 'ready', data })
      })
      .catch((error: unknown) => {
        if (!disposed) {
          const failure = asApiError(error)
          setRefreshError(failure)
          setState((current) => retainData && current.status === 'ready' ? current : { status: 'error', error: failure })
        }
      })
    return () => {
      disposed = true
      controller.abort()
    }
  }, [load, enabled, attempt, retainData])

  const refresh = useCallback(() => {
    setState((current) => retainData && current.status === 'ready' ? current : { status: 'loading' })
    setAttempt((value) => value + 1)
  }, [retainData])

  return {
    state,
    refresh,
    refreshError,
  }
}
