/** 状态语义 → chip 样式/文案（成功绿 / 运行蓝 / 告警黄 / 失败红 / 停用灰） */

export type ChipTone = 'ok' | 'warn' | 'err' | 'idle' | 'run' | 'off'

export interface ChipView {
  tone: ChipTone
  label: string
}

const UNKNOWN: ChipView = { tone: 'idle', label: '—' }

/** GET /tables 的 health 字段：ok|lag|err|idle|none */
export function healthChip(health?: string | null): ChipView {
  switch ((health ?? '').toLowerCase()) {
    case 'ok':
      return { tone: 'ok', label: '健康' }
    case 'lag':
      return { tone: 'warn', label: '滞后' }
    case 'err':
      return { tone: 'err', label: '失败' }
    case 'warn':
      return { tone: 'warn', label: '部分成功' }
    case 'idle':
      return { tone: 'idle', label: '停用' }
    case 'none':
      return { tone: 'idle', label: '未回填' }
    default:
      return UNKNOWN
  }
}

/** 表清单行首小圆点颜色 */
export function healthDotTone(health?: string | null): 'ok' | 'warn' | 'err' | 'idle' {
  switch ((health ?? '').toLowerCase()) {
    case 'ok':
      return 'ok'
    case 'lag':
    case 'warn':
      return 'warn'
    case 'err':
      return 'err'
    default:
      return 'idle'
  }
}

/** ingest_log 作业状态 */
export function jobStatusChip(status?: string | null, retryCount = 0): ChipView {
  switch ((status ?? '').toUpperCase()) {
    case 'SUCCESS':
      return retryCount > 0 ? { tone: 'ok', label: '重试成功' } : { tone: 'ok', label: '成功' }
    case 'FAILED':
      return { tone: 'err', label: '失败' }
    case 'RUNNING':
      return { tone: 'run', label: '运行中' }
    case 'PENDING':
      return { tone: 'idle', label: '等待' }
    case 'SKIPPED':
      return { tone: 'idle', label: '跳过' }
    default:
      return UNKNOWN
  }
}

/** 质量门整体结果：PASS / PASS_WITH_WARN / FAIL / EMPTY */
export function qualityResultChip(result?: string | null): ChipView {
  switch ((result ?? '').toUpperCase()) {
    case 'PASS':
      return { tone: 'ok', label: '达标' }
    case 'PASS_WITH_WARN':
    case 'WARN':
      return { tone: 'warn', label: '带告警达标' }
    case 'FAIL':
      return { tone: 'err', label: '不达标' }
    case 'EMPTY':
      return { tone: 'idle', label: '跳过（非交易日/无数据）' }
    default:
      return { tone: 'idle', label: '未执行' }
  }
}

/** 运行中编排整体状态 */
export function orchestrationChip(status?: string | null): ChipView {
  switch ((status ?? '').toUpperCase()) {
    case 'RUNNING':
      return { tone: 'run', label: '执行中' }
    case 'SUCCESS':
      return { tone: 'ok', label: '已完成' }
    case 'FAILED':
      return { tone: 'err', label: '失败' }
    case 'PARTIAL':
      return { tone: 'warn', label: '部分失败' }
    case 'PENDING':
      return { tone: 'idle', label: '等待' }
    case 'PAUSED':
      return { tone: 'warn', label: '已暂停' }
    default:
      return { tone: 'idle', label: '空闲' }
  }
}

/** 质量门单项状态：PASS/WARN/FAIL/SKIP → q-ico 样式与图标 */
export function checkItemView(status?: string | null): { tone: 'pass' | 'warn' | 'fail' | 'wait'; icon: string } {
  switch ((status ?? '').toUpperCase()) {
    case 'PASS':
      return { tone: 'pass', icon: '✓' }
    case 'WARN':
      return { tone: 'warn', icon: '!' }
    case 'FAIL':
      return { tone: 'fail', icon: '!' }
    case 'SKIP':
    case 'SKIPPED':
      return { tone: 'wait', icon: '·' }
    default:
      return { tone: 'wait', icon: '…' }
  }
}
