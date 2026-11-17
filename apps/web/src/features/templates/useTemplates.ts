import { useEffect, useState } from 'react'
import { listTemplates } from '../../api/templates'
import type { TemplateItem } from '../../api/templates'
import type { ProjectType } from '../../api/capabilities'

type TemplateState =
  | { status: 'loading' }
  | { status: 'ready'; items: TemplateItem[] }
  | { status: 'error'; message: string }

export function useTemplates(type: ProjectType) {
  const [state, setState] = useState<TemplateState>({ status: 'loading' })
  const [attempt, setAttempt] = useState(0)

  useEffect(() => {
    const controller = new AbortController()
    let disposed = false
    setState({ status: 'loading' })
    listTemplates(type, controller.signal)
      .then((items) => {
        if (!disposed) setState({ status: 'ready', items })
      })
      .catch((error: unknown) => {
        if (!disposed) {
          setState({
            status: 'error',
            message: controller.signal.aborted
              ? '模板列表加载超时，请重试。'
              : error instanceof Error ? error.message : '暂时无法读取模板列表。',
          })
        }
      })
    return () => {
      disposed = true
      controller.abort()
    }
  }, [type, attempt])

  return { state, retry: () => setAttempt((value) => value + 1) }
}
