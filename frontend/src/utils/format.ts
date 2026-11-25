/** 数字 / 日期 / 耗时 / 状态文案格式化（全部在前端组装，后端只提供原始值） */

function pad2(n: number): string {
  return String(n).padStart(2, '0')
}

/** 千分位整数（行数等）：12847391 → 12,847,391 */
export function formatInt(value: number | null | undefined): string {
  if (value === null || value === undefined) return '—'
  const n = Number(value)
  if (!Number.isFinite(n)) return '—'
  return Math.round(n).toLocaleString('en-US')
}

/** 行数（带近似标记）：rowCountApprox=true 时前缀 ≈ */
export function formatRowCount(value: number | null | undefined, approximate = false): string {
  const text = formatInt(value)
  if (text === '—') return text
  return approximate ? `≈${text}` : text
}

/** 通用数字：整数千分位，小数原样 */
export function formatNumber(value: number | null | undefined): string {
  if (value === null || value === undefined) return '—'
  const n = Number(value)
  if (!Number.isFinite(n)) return '—'
  return Number.isInteger(n) ? n.toLocaleString('en-US') : String(n)
}

/** 毫秒 → 8.2s / 22m48s / 1h05m */
export function formatDuration(ms: number | null | undefined): string {
  if (ms === null || ms === undefined) return '—'
  const n = Number(ms)
  if (!Number.isFinite(n) || n < 0) return '—'
  const seconds = n / 1000
  if (seconds < 60) return `${seconds.toFixed(1)}s`
  const minutes = Math.floor(seconds / 60)
  const rest = Math.round(seconds % 60)
  if (minutes < 60) return `${minutes}m${pad2(rest)}s`
  const hours = Math.floor(minutes / 60)
  return `${hours}h${pad2(minutes % 60)}m`
}

const TS_RE = /^(\d{4})-(\d{2})-(\d{2})[T ](\d{2}):(\d{2})(?::(\d{2}))?/

/**
 * 时间戳展示（ISO 字符串）：默认 `MM-dd HH:mm`；withYear → `yyyy-MM-dd HH:mm`；
 * 无法解析时原样返回（可能本身就是 yyyyMMdd）。
 */
export function formatTimestamp(
  value: string | null | undefined,
  options: { withYear?: boolean; withSeconds?: boolean } = {}
): string {
  if (!value) return '—'
  const m = TS_RE.exec(value)
  if (!m) return value
  const [, year, month, day, hour, minute, second] = m
  const datePart = options.withYear ? `${year}-${month}-${day}` : `${month}-${day}`
  const timePart = options.withSeconds ? `${hour}:${minute}:${second ?? '00'}` : `${hour}:${minute}`
  return `${datePart} ${timePart}`
}

/** 业务日期展示：后端已是 yyyyMMdd，原样展示（缺失 → —） */
export function formatBusinessDate(value: string | null | undefined): string {
  return value && value.trim() ? value : '—'
}

/** 柱图 x 轴短标签：20261110 → 1110 */
export function formatShortDay(value: string | null | undefined): string {
  if (!value) return ''
  return value.length === 8 ? value.slice(4) : value
}

/** 滞后天数：0/1 健康绿、≤3 琥珀、其余红；null → — */
export function formatLagDays(lagDays: number | null | undefined): { text: string; tone: string } {
  if (lagDays === null || lagDays === undefined) return { text: '—', tone: 'txt-dim' }
  const n = Number(lagDays)
  const tone = n <= 1 ? 'txt-ok' : n <= 3 ? 'txt-warn' : 'txt-err'
  return { text: `${n} 天`, tone }
}

/** 作业类型：INCR 增量 / FULL 全量 / BACKFILL 回填 */
export function formatJobType(jobType: string | null | undefined): string {
  switch ((jobType ?? '').toUpperCase()) {
    case 'INCR':
    case 'INCREMENT':
      return '增量'
    case 'FULL':
      return '全量'
    case 'BACKFILL':
      return '回填'
    case 'DAILY':
      return '每日增量'
    default:
      return jobType || '—'
  }
}

/** 质量门触发方式 */
export function formatTriggerType(trigger: string | null | undefined): string {
  switch ((trigger ?? '').toUpperCase()) {
    case 'MANUAL':
      return '手动'
    case 'ORCHESTRATED':
      return '编排调度'
    case 'RETRY':
      return '重试'
    case 'SCHEDULED':
    case 'CRON':
      return '定时调度'
    default:
      return trigger || '—'
  }
}

/** 从 startedAt 到 now 的耗时（用于运行中编排的实时计时） */
export function elapsedSince(startedAt: string | null | undefined, now: number = Date.now()): string {
  if (!startedAt) return '—'
  const start = new Date(startedAt).getTime()
  if (!Number.isFinite(start)) return '—'
  return formatDuration(Math.max(0, now - start))
}

/** 当前本地日期 yyyyMMdd */
export function todayStamp(): string {
  const d = new Date()
  return `${d.getFullYear()}${pad2(d.getMonth() + 1)}${pad2(d.getDate())}`
}
