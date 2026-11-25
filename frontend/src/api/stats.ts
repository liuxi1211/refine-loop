import { get } from './http'
import type { StatsToday } from './types'

export function fetchStatsToday(silent = false): Promise<StatsToday> {
  return get<StatsToday>('/api/ops/stats/today', undefined, { silent })
}
