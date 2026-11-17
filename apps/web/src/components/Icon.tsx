import type { CSSProperties } from 'react'

export type IconName = 'home' | 'web' | 'game' | 'slides' | 'video' | 'folder' | 'plus' | 'arrow' | 'menu' | 'close'

const paths: Record<IconName, string> = {
  home: 'm3 10 9-7 9 7M5 9v12h14V9M9 21v-7h6v7',
  web: 'M3 5h18v14H3zM3 9h18M7 7h.01M10 7h.01',
  game: 'M7 7h10c3 0 5 12 2 13-2 1-4-3-4-3H9s-2 4-4 3C2 19 4 7 7 7ZM7 11v5M4.5 13.5h5M16 12h.01M18 15h.01',
  slides: 'M3 4h18v13H3zM12 17v4M8 21l4-4 4 4M10 8l5 3-5 3z',
  video: 'M3 6h12v12H3zM15 10l6-3v10l-6-3',
  folder: 'M3 7V4h6l2 3h10v13H3z',
  plus: 'M12 5v14M5 12h14',
  arrow: 'M12 20V4m-7 7 7-7 7 7',
  menu: 'M4 6h16M4 12h16M4 18h16',
  close: 'm6 6 12 12M18 6 6 18',
}

export function Icon({ name, style }: { name: IconName; style?: CSSProperties }) {
  return (
    <svg viewBox="0 0 24 24" width="20" height="20" fill="none" stroke="currentColor"
      strokeWidth="1.6" strokeLinecap="round" strokeLinejoin="round" aria-hidden="true" style={style}>
      <path d={paths[name]} />
    </svg>
  )
}
