import { useEffect, useState } from 'react'
import { fetchProviderStatus } from '../../api/provider'
import type { ProviderStatus } from '../../api/provider'

type ProviderView =
  | { status: 'loading' }
  | { status: 'ready'; provider: ProviderStatus }
  | { status: 'unavailable' }

/**
 * Provider readiness is supplementary information: a failure here never blocks the workbench and
 * is shown as "unavailable" instead of a scary error.
 */
export function useProvider() {
  const [state, setState] = useState<ProviderView>({ status: 'loading' })

  useEffect(() => {
    const controller = new AbortController()
    let disposed = false
    fetchProviderStatus(controller.signal)
      .then((provider) => {
        if (!disposed) setState({ status: 'ready', provider })
      })
      .catch(() => {
        if (!disposed) setState({ status: 'unavailable' })
      })
    return () => {
      disposed = true
      controller.abort()
    }
  }, [])

  return { state }
}
