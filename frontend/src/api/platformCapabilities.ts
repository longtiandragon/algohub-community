import { get, getSessionGeneration } from './client'
import type { PlatformCapability } from './types'

let cached: { generation: number; request: Promise<PlatformCapability[]> } | undefined

export function getPlatformCapabilities() {
  const generation = getSessionGeneration()
  if (cached?.generation === generation) return cached.request
  const request = get<PlatformCapability[]>('/platforms/capabilities').catch(error => {
    if (cached?.request === request) cached = undefined
    throw error
  })
  cached = { generation, request }
  return request
}
