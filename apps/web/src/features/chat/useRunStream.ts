import { useCallback, useEffect, useRef, useState } from 'react'
import { asApiError } from '../../api/http'
import { cancelRun, fetchLatestRun, streamRun } from '../../api/runs'
import type { RunArtifact, RunEvent, RunInfo, RunProgress, RunStep } from '../../api/runs'

export interface RunView {
  phase: 'loading' | 'none' | 'ready' | 'error'
  run: RunInfo | null
  steps: RunStep[]
  progress: RunProgress | null
  artifact: RunArtifact | null
  message: string | null
  notice: string | null
}

const initial: RunView = {
  phase: 'loading', run: null, steps: [], progress: null, artifact: null, message: null, notice: null,
}

function applyEvent(view: RunView, event: RunEvent): RunView {
  if (event.type === 'status') {
    return {
      ...view,
      run: view.run ? {
        ...view.run,
        status: event.data.status,
        errorCode: event.data.code ?? view.run.errorCode,
        errorMessage: event.data.message ?? view.run.errorMessage,
      } : view.run,
      notice: event.data.label,
    }
  }
  if (event.type === 'step') {
    const steps = [...view.steps]
    const existing = steps.findIndex((step) => step.id === event.data.id)
    if (existing >= 0) steps[existing] = event.data
    else steps.push(event.data)
    return { ...view, steps }
  }
  if (event.type === 'progress') return { ...view, progress: event.data }
  return { ...view, artifact: event.data }
}

function fromSnapshot(snapshot: { run: RunInfo; events: RunEvent[] }): { view: RunView; lastSeq: number } {
  let view: RunView = { ...initial, phase: 'ready', run: snapshot.run, notice: null }
  let lastSeq = 0
  for (const event of snapshot.events) {
    lastSeq = Math.max(lastSeq, event.seq)
    view = applyEvent(view, event)
  }
  // Events can lag the transaction that finishes a run. The snapshot row is authoritative.
  view.run = snapshot.run
  const status = snapshot.run.status
  if (status === 'FAILED') view.notice = snapshot.run.errorMessage ?? '生成失败'
  else if (status === 'CANCELLED') view.notice = '已停止'
  else if (status === 'SUCCEEDED') view.notice = '已完成'
  return { view, lastSeq }
}

function reconnectDelay(milliseconds: number, signal: AbortSignal): Promise<void> {
  return new Promise((resolve, reject) => {
    signal.throwIfAborted()
    const abort = () => {
      window.clearTimeout(timer)
      reject(signal.reason)
    }
    const timer = window.setTimeout(() => {
      signal.removeEventListener('abort', abort)
      resolve()
    }, milliseconds)
    signal.addEventListener('abort', abort, { once: true })
  })
}

/**
 * Loads the newest run for a project and follows its event log. The log lives on the server, so a
 * refresh, a navigation away and a stop all keep the same visible history.
 */
export function useRunStream(projectId: string) {
  const [view, setView] = useState<RunView>(initial)
  const [attempt, setAttempt] = useState(0)
  const [stopping, setStopping] = useState(false)
  const stopRequest = useRef<AbortController | null>(null)
  const viewRef = useRef(view)
  viewRef.current = view

  useEffect(() => {
    let disposed = false
    const controller = new AbortController()
    setView(initial)

    async function follow() {
      for (let reconnect = 0; reconnect <= 3; reconnect++) {
        const snapshot = await fetchLatestRun(projectId, controller.signal)
        if (disposed) return
        if (!snapshot) {
          setView({ ...initial, phase: 'none' })
          return
        }
        const { view: loaded, lastSeq } = fromSnapshot(snapshot)
        setView(loaded)
        const active = loaded.run?.status === 'QUEUED' || loaded.run?.status === 'RUNNING'
        if (!active || !loaded.run) return
        if (reconnect === 3) {
          setView({ ...loaded, phase: 'error', message: '生成过程连接已中断，任务可能仍在运行。请重新读取状态。' })
          return
        }
        if (reconnect > 0) await reconnectDelay(500 * reconnect, controller.signal)
        let cursor = lastSeq
        try {
          await streamRun(projectId, loaded.run.id, cursor, (event) => {
            if (disposed || event.seq <= cursor) return
            cursor = event.seq
            setView((current) => applyEvent(current, event))
          }, controller.signal)
        } catch (cause: unknown) {
          controller.signal.throwIfAborted()
          // A lost stream has the same recovery path as EOF: re-read persisted state.
          if (disposed) throw cause
        }
        // EOF (including the server's stream timeout) always re-reads before deciding terminal.
      }
    }

    follow().catch((cause: unknown) => {
      if (disposed || controller.signal.aborted) return
      setView({ ...viewRef.current, phase: 'error', message: asApiError(cause).message })
    })

    return () => {
      disposed = true
      controller.abort()
      stopRequest.current?.abort()
      stopRequest.current = null
    }
  }, [projectId, attempt])

  const stop = useCallback(async () => {
    const run = viewRef.current.run
    if (!run || stopRequest.current) return
    setStopping(true)
    const controller = new AbortController()
    stopRequest.current = controller
    try {
      await cancelRun(projectId, run.id, controller.signal)
      if (!controller.signal.aborted) setView((current) => ({ ...current, notice: '正在停止，等待当前步骤结束…' }))
    } catch (cause: unknown) {
      if (!controller.signal.aborted) setView((current) => ({ ...current, notice: asApiError(cause).message }))
    } finally {
      if (stopRequest.current === controller) stopRequest.current = null
      if (!controller.signal.aborted) setStopping(false)
    }
  }, [projectId])

  return {
    view,
    stopping,
    stop,
    retry: () => setAttempt((value) => value + 1),
  }
}
