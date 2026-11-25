import { get, post } from './http'
import type {
  IngestJobView,
  PageResult,
  PreviewQuery,
  PreviewResult,
  TableDetail,
  TablesOverview
} from './types'

export interface OverviewOptions {
  silent?: boolean
}

export function fetchTables(options: OverviewOptions = {}): Promise<TablesOverview> {
  return get<TablesOverview>('/api/ops/tables', undefined, { silent: options.silent })
}

export function fetchTableDetail(table: string, options: OverviewOptions = {}): Promise<TableDetail> {
  return get<TableDetail>(`/api/ops/tables/${encodeURIComponent(table)}`, undefined, { silent: options.silent })
}

export function fetchPreview(table: string, query: PreviewQuery): Promise<PreviewResult> {
  return get<PreviewResult>(`/api/ops/tables/${encodeURIComponent(table)}/preview`, {
    code: query.code,
    start: query.start,
    end: query.end,
    columnMode: query.columnMode,
    page: query.page,
    size: query.size,
    order: query.order
  })
}

export function fetchTableJobs(
  table: string,
  page = 1,
  size = 10
): Promise<PageResult<IngestJobView>> {
  return get<PageResult<IngestJobView>>(`/api/ops/tables/${encodeURIComponent(table)}/jobs`, { page, size })
}

export interface TriggerAck {
  accepted?: boolean | null
  runId?: string | null
  message?: string | null
}

export function runTable(table: string): Promise<TriggerAck> {
  return post<TriggerAck>(`/api/ops/tables/${encodeURIComponent(table)}/run`)
}

export interface BackfillRange {
  start?: string
  end?: string
}

export function backfillTable(table: string, range: BackfillRange): Promise<TriggerAck> {
  const body: Record<string, string> = {}
  if (range.start) body.start = range.start
  if (range.end) body.end = range.end
  return post<TriggerAck>(`/api/ops/tables/${encodeURIComponent(table)}/backfill`, body)
}

export interface DailyToggleResult {
  table: string
  dailyEnabled: boolean
}

export function toggleDaily(table: string, enabled: boolean): Promise<DailyToggleResult> {
  return post<DailyToggleResult>(`/api/ops/tables/${encodeURIComponent(table)}/daily-toggle`, { enabled })
}
