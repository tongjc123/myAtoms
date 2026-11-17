import { isRecord } from './http'

export const projectTypes = ['WEB', 'GAME', 'SLIDES', 'VIDEO'] as const
export type ProjectType = (typeof projectTypes)[number]

export interface Capabilities {
  generationAvailable: boolean
  projectManagementAvailable: boolean
  reasonCode: string
  message: string
  projectTypes: ProjectType[]
}

export function isProjectType(value: unknown): value is ProjectType {
  return projectTypes.some((type) => type === value)
}

function parseCapabilities(value: unknown): Capabilities {
  if (
    !isRecord(value) ||
    typeof value.generationAvailable !== 'boolean' ||
    typeof value.projectManagementAvailable !== 'boolean' ||
    typeof value.reasonCode !== 'string' ||
    typeof value.message !== 'string' ||
    !Array.isArray(value.projectTypes) ||
    !value.projectTypes.every(isProjectType)
  ) {
    throw new Error('服务返回了无法识别的状态，请稍后重试。')
  }
  return {
    generationAvailable: value.generationAvailable,
    projectManagementAvailable: value.projectManagementAvailable,
    reasonCode: value.reasonCode,
    message: value.message,
    projectTypes: value.projectTypes,
  }
}

export async function fetchCapabilities(signal: AbortSignal): Promise<Capabilities> {
  let response: Response
  try {
    response = await fetch('/api/system/capabilities', { signal })
  } catch (error: unknown) {
    if (signal.aborted) throw error
    throw new Error('暂时无法连接创作服务，请检查服务是否已启动。')
  }
  if (!response.ok) throw new Error('暂时无法连接创作服务，请检查服务是否已启动。')
  let body: unknown
  try {
    body = await response.json()
  } catch (error: unknown) {
    if (signal.aborted) throw error
    throw new Error('服务返回了无法识别的状态，请稍后重试。')
  }
  return parseCapabilities(body)
}
