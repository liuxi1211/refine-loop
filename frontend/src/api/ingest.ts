import { get, post } from './http'
import type {
  BackfillPlan,
  IngestJobView,
  IngestTriggerAck,
  OrchestrationProgress,
  PageResult
} from './types'

export function triggerDaily(): Promise<IngestTriggerAck> {
  return post<IngestTriggerAck>('/api/ops/ingest/daily')
}

export function fetchCurrentOrchestration(silent = false): Promise<OrchestrationProgress> {
  return get<OrchestrationProgress>('/api/ops/ingest/orchestrations/current', undefined, { silent })
}

export interface JobQuery {
  status?: string
  table?: string
  page?: number
  size?: number
}

export function fetchJobs(query: JobQuery = {}, silent = false): Promise<PageResult<IngestJobView>> {
  return get<PageResult<IngestJobView>>(
    '/api/ops/ingest/jobs',
    {
      status: query.status,
      table: query.table,
      page: query.page ?? 1,
      size: query.size ?? 20
    },
    { silent }
  )
}

export function retryJob(id: number): Promise<IngestTriggerAck> {
  return post<IngestTriggerAck>(`/api/ops/ingest/jobs/${id}/retry`)
}

export type BackfillPlanMode = 'standard' | 'all' | 'custom'

export function createBackfillPlan(mode: BackfillPlanMode, tables: string[] = []): Promise<BackfillPlan> {
  return post<BackfillPlan>('/api/ops/ingest/backfill-plan', { mode, tables })
}
