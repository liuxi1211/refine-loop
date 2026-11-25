import { get, post } from './http'
import type { QualityHistoryPage, QualityReport } from './types'

/** GET /api/ops/quality/latest → QualityReport（无历史时 data 为 null） */
export function fetchLatestQuality(silent = false): Promise<QualityReport | null> {
  return get<QualityReport | null>('/api/ops/quality/latest', undefined, { silent })
}

/** POST /api/ops/quality/run → QualityReport（检查可能较慢，给 90s 超时） */
export function runQualityCheck(tradeDate?: string): Promise<QualityReport> {
  return post<QualityReport>('/api/ops/quality/run', undefined, { query: tradeDate ? { tradeDate } : undefined, timeoutMs: 90000 })
}

export function fetchQualityHistory(page = 1, size = 20, days = 30): Promise<QualityHistoryPage> {
  return get<QualityHistoryPage>('/api/ops/quality/history', { days, page, size })
}
