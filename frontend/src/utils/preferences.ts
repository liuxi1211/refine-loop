/**
 * 交易偏好键登记表（与后端 `user_preference.pref_key` 约定一致）。
 *
 * <p>偏好是「键值字符串」，前端表单只编辑已登记的键；新增偏好 = 在这里加一行 + 表单加一项。
 * 未登记的键不会出现在「我的账户」表单里，但后端仍会原样保存（向前兼容）。
 */
export interface PreferenceField {
  key: string
  label: string
  placeholder: string
  /** number：按整数/小数输入；text：自由文本；select：从 options 选 */
  kind: 'text' | 'number' | 'select'
  options?: Array<{ value: string; label: string }>
}

export const PREFERENCE_FIELDS: PreferenceField[] = [
  {
    key: 'trading.style',
    label: '交易风格',
    placeholder: '请选择',
    kind: 'select',
    options: [
      { value: 'DAY', label: '日内短线' },
      { value: 'SWING', label: '波段' },
      { value: 'POSITION', label: '中线持仓' },
      { value: 'VALUE', label: '价值长线' }
    ]
  },
  { key: 'risk.maxPositionPct', label: '单票最大仓位（%）', placeholder: '如 30', kind: 'number' },
  { key: 'risk.stopLossPct', label: '止损线（%）', placeholder: '如 8', kind: 'number' },
  { key: 'trade.defaultHorizonDays', label: '默认持有周期（交易日）', placeholder: '如 20', kind: 'number' },
  { key: 'trade.note', label: '交易纪律备注', placeholder: '如：只做龙头，破位即走', kind: 'text' }
]

/** 表单模型：键 → 字符串（空串表示不提交该键）。 */
export type PreferenceDraft = Record<string, string>

/** 后端返回值 → 表单模型（补齐未设置的键，避免 v-model 为 undefined）。 */
export function toPreferenceDraft(values: Record<string, string> | null | undefined): PreferenceDraft {
  const draft: PreferenceDraft = {}
  for (const field of PREFERENCE_FIELDS) {
    draft[field.key] = values?.[field.key] ?? ''
  }
  return draft
}

/** 表单模型 → 提交体（丢弃空值；未登记的既有键不丢失由调用方合并）。 */
export function fromPreferenceDraft(draft: PreferenceDraft, existing: Record<string, string> = {}): Record<string, string> {
  const payload: Record<string, string> = { ...existing }
  for (const field of PREFERENCE_FIELDS) {
    const value = (draft[field.key] ?? '').trim()
    if (value) {
      payload[field.key] = value
    } else {
      delete payload[field.key]
    }
  }
  return payload
}
