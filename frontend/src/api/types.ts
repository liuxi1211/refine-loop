/**
 * 运维 API 契约类型（与 .trae/documents/t2-t5-dev-contract.md §9 对齐）。
 * 后端全部返回 Result{code,msg,data}；ISO 时间字符串；业务日期 yyyyMMdd。
 */

/* ---------------- tables 模块 ---------------- */
export interface TablesSummary {
  total: number
  healthy: number
  lag: number
  error: number
  idle: number
  dailyEnabled: number
}

export interface TableMeta {
  table: string
  api: string
  chineseName?: string | null
  group: string
  strategy?: string | null
  strategyLabel?: string | null
  rowCount?: number | null
  rowCountApprox?: boolean | null
  latestDate?: string | null
  latestDateColumn?: string | null
  lagDays?: number | null
  lastJobStatus?: string | null
  lastJobTime?: string | null
  lastJobType?: string | null
  dailyEnabled: boolean
  health?: string | null
  rateLimit?: number | null
  unitNote?: string | null
}

export interface TablesOverview {
  summary: TablesSummary
  tables: TableMeta[]
}

export interface TableColumnInfo {
  name: string
  type?: string | null
  comment?: string | null
}

export interface DailyLoadPoint {
  date: string
  rows: number
}

export interface LastFullBackfill {
  jobType?: string | null
  status?: string | null
  startTime?: string | null
  endTime?: string | null
  rowsWritten?: number | null
}

export interface TableDetail {
  table: string
  api: string
  chineseName?: string | null
  group?: string | null
  strategy?: string | null
  strategyLabel?: string | null
  pk?: string | null
  rowCount?: number | null
  rowCountApprox?: boolean | null
  latestDate?: string | null
  latestDateColumn?: string | null
  lagDays?: number | null
  rateLimit?: number | null
  unitNote?: string | null
  dailyEnabled: boolean
  lastFullBackfill?: LastFullBackfill | null
  recentDailyLoad?: DailyLoadPoint[] | null
  columns?: TableColumnInfo[] | null
}

export interface PreviewResult {
  table: string
  columnMode: string
  columns: string[]
  rows: Array<Record<string, unknown>>
  total: number
  page: number
  size: number
  limited?: boolean | null
}

export interface PreviewQuery {
  code?: string
  start?: string
  end?: string
  columnMode: 'key' | 'all'
  page: number
  size: number
  order?: 'desc' | 'asc'
}

/* ---------------- ingest 模块 ---------------- */
export interface IngestJobView {
  id: number
  runId?: string | null
  tableName: string
  jobType?: string | null
  rangeParams?: string | null
  status?: string | null
  rowsFetched?: number | null
  rowsWritten?: number | null
  retryCount?: number | null
  startTime?: string | null
  endTime?: string | null
  durationMs?: number | null
  errorMsg?: string | null
  createdAt?: string | null
}

export interface PageResult<T> {
  items: T[]
  total: number
  page: number
  size: number
}

export interface IngestTriggerAck {
  accepted?: boolean | null
  runId?: string | null
  message?: string | null
}

export interface TableJobProgress {
  table: string
  status?: string | null
  rowsWritten?: number | null
  durationMs?: number | null
  startedAt?: string | null
  finishedAt?: string | null
  errorMsg?: string | null
}

export interface LayerProgress {
  layer: string
  tables: TableJobProgress[]
  total: number
  done: number
  failed: number
  running: number
  pending: number
  percent: number
}

export interface OrchestrationProgress {
  running: boolean
  runId?: string | null
  status?: string | null
  startedAt?: string | null
  finishedAt?: string | null
  triggerType?: string | null
  layers: LayerProgress[]
  total: number
  done: number
  failed: number
  /** 后端 record 中布尔 running 与计数 runningCount 并列（契约 §7 的 Java record 修正） */
  runningCount?: number | null
  pending: number
  percent: number
}

export interface PlanBatch {
  night: number
  tables: string[]
}

export interface BackfillPlan {
  planId: string
  mode: string
  tables: string[]
  batches: PlanBatch[]
  message?: string | null
}

/* ---------------- quality 模块 ---------------- */
export interface QualityCheckItem {
  code?: string | null
  layer?: number | null
  name?: string | null
  status: string
  expected?: string | null
  actual?: string | null
  message?: string | null
}

export interface QualityReport {
  checkId?: number | null
  checkTime?: string | null
  tradeDate?: string | null
  triggerType?: string | null
  result: string
  durationMs?: number | null
  items?: QualityCheckItem[] | null
}

export interface QualityHistoryItem {
  checkId: number
  checkTime?: string | null
  triggerType?: string | null
  result: string
  durationMs?: number | null
  passCount?: number | null
  warnCount?: number | null
  failCount?: number | null
}

export interface QualityHistoryPage {
  items: QualityHistoryItem[]
  total: number
  page: number
  size: number
}

/* ---------------- stats 模块 ---------------- */
export interface TodayJobs {
  total: number
  success: number
  failed: number
  running: number
  pending: number
}

export interface TodayTables {
  total: number
  healthy: number
  lag: number
  error: number
  idle: number
}

export interface TodayQuality {
  result?: string | null
  checkTime?: string | null
}

export interface LastOrchestration {
  runId?: string | null
  status?: string | null
  startedAt?: string | null
  finishedAt?: string | null
}

export interface StatsToday {
  date?: string | null
  jobs?: TodayJobs | null
  tushareCallsToday?: number | null
  tables?: TodayTables | null
  quality?: TodayQuality | null
  lastOrchestration?: LastOrchestration | null
}

/* ---------------- 账号域（/api/auth、/api/users、/api/users/me/*） ---------------- */

export type UserRoleCode = 'ADMIN' | 'USER'

/** 当前登录用户（GET /api/auth/me 与登录结果复用）。 */
export interface CurrentUser {
  id: number
  username: string
  displayName?: string | null
  role: UserRoleCode
  roleLabel?: string | null
  permissions: string[]
}

export interface LoginResult {
  token: string
  expiresAt?: string | null
  user: CurrentUser
}

/** 用户列表行（GET /api/users）。 */
export interface UserSummary {
  id: number
  username: string
  displayName?: string | null
  role: string
  roleLabel?: string | null
  active: boolean
  lastLoginAt?: string | null
  createdAt?: string | null
}

/** 用户详情（含角色展开后的权限码）。 */
export interface UserDetail extends UserSummary {
  remark?: string | null
  updatedAt?: string | null
  permissions: string[]
}

export interface UserQuery {
  keyword?: string
  role?: string
  active?: boolean
  page: number
  size: number
}

export interface CreateUserPayload {
  username: string
  password: string
  displayName?: string | null
  role?: string | null
  remark?: string | null
}

export interface UpdateUserPayload {
  displayName?: string | null
  role?: string | null
  active?: boolean | null
  remark?: string | null
}

/** 交易偏好（GET/PUT /api/users/me/preferences）。 */
export interface PreferenceView {
  userId: number
  preferences: Record<string, string>
}

/** 自选股条目（GET/POST/PUT /api/users/me/watchlist）。 */
export interface WatchlistItem {
  id: number
  tsCode: string
  stockName?: string | null
  groupName?: string | null
  note?: string | null
  sortNo: number
  createdAt?: string | null
}

export interface WatchlistOverview {
  items: WatchlistItem[]
  total: number
}

export interface WatchlistPayload {
  tsCode: string
  stockName?: string | null
  groupName?: string | null
  note?: string | null
  sortNo?: number | null
}

