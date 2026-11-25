/**
 * 前端字典 API：下拉框/状态标签的唯一数据源。
 *
 * 后端 `/api/common/dicts` 一次下发全部字典（`{ [dictType]: DictItem[] }`），
 * 字典来自后端枚举（`DictEnum` 实现），前端不再硬编码「PENDING → 等待」这类映射。
 */
import { get, post } from './http'

export interface DictItem {
  code: string
  label: string
  sort: number
  /** chip 色调：ok / warn / err / idle / run / off；后端未指定时为 null */
  color?: string | null
  enabled: boolean
}

export type DictMap = Record<string, DictItem[]>

/** 全部字典。默认静默：首屏拉字典失败不应该弹 toast（后端离线由侧边栏统一提示）。 */
export function fetchAllDicts(options: { silent?: boolean } = {}): Promise<DictMap> {
  return get<DictMap>('/api/common/dicts', undefined, { silent: options.silent ?? true })
}

/** 单个字典（懒加载场景）。 */
export function fetchDict(type: string): Promise<DictItem[]> {
  return get<DictItem[]>(`/api/common/dicts/${encodeURIComponent(type)}`, undefined, { silent: true })
}

/** 重建后端字典快照（枚举改动后无需重启服务）。 */
export function refreshDicts(): Promise<DictMap> {
  return post<DictMap>('/api/common/dicts/refresh')
}
