import { useCallback, useEffect, useRef, useState } from 'react'
import { listImports, uploadImport } from '../../api/imports'
import type { ImportedTemplate } from '../../api/imports'
import { ApiError, asApiError } from '../../api/http'

interface ImportState {
  status: 'loading' | 'ready' | 'error'
  items: ImportedTemplate[]
  message?: string
}

export function useImports(enabled: boolean) {
  const [state, setState] = useState<ImportState>({ status: 'loading', items: [] })
  const [attempt, setAttempt] = useState(0)
  const [uploading, setUploading] = useState(false)
  const [error, setError] = useState<ApiError | null>(null)
  const inputRef = useRef<AbortController | null>(null)

  useEffect(() => {
    if (!enabled) return
    const controller = new AbortController()
    let disposed = false
    setState((current) => ({ ...current, status: current.items.length ? 'ready' : 'loading' }))
    listImports(controller.signal)
      .then((items) => { if (!disposed) setState({ status: 'ready', items }) })
      .catch((cause: unknown) => {
        if (disposed) return
        setState({ status: 'error', items: [], message: asApiError(cause).message })
      })
    return () => {
      disposed = true
      controller.abort()
    }
  }, [enabled, attempt])

  useEffect(() => () => inputRef.current?.abort(), [])

  const upload = useCallback(async (file: File, name = '') => {
    const controller = new AbortController()
    inputRef.current?.abort()
    inputRef.current = controller
    setUploading(true)
    setError(null)
    try {
      const item = await uploadImport(file, name, controller.signal)
      setState((current) => ({ status: 'ready', items: [item, ...current.items] }))
      return item
    } catch (cause: unknown) {
      const failure = asApiError(cause)
      setError(failure)
      throw failure
    } finally {
      setUploading(false)
    }
  }, [])

  return {
    state,
    uploading,
    error,
    upload,
    clearError: () => setError(null),
    refresh: () => setAttempt((value) => value + 1),
  }
}
