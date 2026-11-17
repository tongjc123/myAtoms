import { useEffect, useState } from 'react'

/**
 * Hovering a template card swaps the painted preview for a live, self-advancing one. The iframe is
 * only created on intent so a gallery of cards costs nothing at rest.
 */
export function useHoverPeek(delay = 140) {
  const [peeking, setPeeking] = useState(false)
  const [timer, setTimer] = useState<number | null>(null)

  useEffect(() => () => { if (timer !== null) window.clearTimeout(timer) }, [timer])

  return {
    peeking,
    start() {
      if (timer !== null) window.clearTimeout(timer)
      setTimer(window.setTimeout(() => setPeeking(true), delay))
    },
    stop() {
      if (timer !== null) window.clearTimeout(timer)
      setTimer(null)
      setPeeking(false)
    },
  }
}
