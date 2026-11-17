import { useSyncExternalStore } from 'react'
import { isUuid } from '../../api/http'

type Route =
  | { kind: 'home' }
  | { kind: 'projects'; page: number }
  | { kind: 'detail'; id: string }
  | { kind: 'chat'; id: string }
  | { kind: 'not-found' }

function subscribe(listener: () => void) {
  window.addEventListener('hashchange', listener)
  return () => window.removeEventListener('hashchange', listener)
}

function readHash() {
  return window.location.hash
}

export function useProjectRoute(): Route {
  const hash = useSyncExternalStore(subscribe, readHash, () => '#home')
  if (hash === '' || hash === '#' || hash === '#home') return { kind: 'home' }
  const [path, query = ''] = hash.split('?')
  if (path === '#projects') {
    const page = Number(new URLSearchParams(query).get('page') ?? 0)
    return Number.isSafeInteger(page) && page >= 0 && page <= 10_000
      ? { kind: 'projects', page }
      : { kind: 'not-found' }
  }
  const id = path.slice('#projects/'.length)
  if (path.startsWith('#projects/') && isUuid(id)) return { kind: 'detail', id }
  const chatId = path.slice('#chat/'.length)
  if (path.startsWith('#chat/') && isUuid(chatId)) return { kind: 'chat', id: chatId }
  return { kind: 'not-found' }
}

export function openProject(id: string) {
  window.location.hash = `projects/${id}`
}

export function openChat(id: string) {
  window.location.hash = `chat/${id}`
}
