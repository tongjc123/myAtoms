import { useEffect, useState } from 'react'
import { asApiError } from '../../api/http'
import type { ApiError } from '../../api/http'
import { ensureSession, invalidateSession } from '../../api/session'

type SessionState =
  | { status: 'idle' }
  | { status: 'loading' }
  | { status: 'ready' }
  | { status: 'error'; error: ApiError }

export function useSession(enabled: boolean) {
  const [state, setState] = useState<SessionState>({ status: 'idle' })
  const [attempt, setAttempt] = useState(0)
  useEffect(() => {
    if (!enabled) {
      setState({ status: 'idle' })
      return
    }
    let disposed = false
    setState({ status: 'loading' })
    ensureSession()
      .then(() => { if (!disposed) setState({ status: 'ready' }) })
      .catch((error: unknown) => {
        if (!disposed) setState({ status: 'error', error: asApiError(error) })
      })
    return () => { disposed = true }
  }, [enabled, attempt])

  function retry() {
    invalidateSession()
    setState({ status: 'loading' })
    setAttempt((value) => value + 1)
  }
  return { state, retry }
}
