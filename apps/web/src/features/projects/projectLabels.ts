import type { ProjectType } from '../../api/capabilities'
import type { ProjectMode } from '../../api/projects'

export const typeLabels: Record<ProjectType, string> = {
  WEB: '网页', GAME: '游戏', SLIDES: '幻灯片', VIDEO: '视频',
}

export const modeLabels: Record<ProjectMode, string> = { BUILD: '构建', PLAN: '规划' }

export function formatProjectDate(value: string) {
  return new Intl.DateTimeFormat('zh-CN', { dateStyle: 'medium', timeStyle: 'short' }).format(new Date(value))
}
