import { defineStore } from 'pinia'
import { ref } from 'vue'
import { fetchAllDicts, refreshDicts, type DictItem, type DictMap } from '../api/dict'

export type DictLoadState = 'idle' | 'loading' | 'ready' | 'error'

export interface DictOption {
  value: string
  label: string
  disabled: boolean
}

/**
 * 字典仓库：进程内只拉一次、并发请求合并、失败不阻断页面。
 *
 * 设计取舍：
 *  - 失败只置 state=error，不抛异常——字典缺失时页面应退化为「显示原始 code」而不是白屏；
 *  - 并发调用 ensureLoaded 共享同一个 promise（首屏多个 DictSelect 同时挂载只发一次请求）；
 *  - 不持久化到 localStorage：字典随后端发布变化，缓存进浏览器会长期不更新。
 */
export const useDictStore = defineStore('dict', () => {
  const dicts = ref<DictMap>({})
  const state = ref<DictLoadState>('idle')

  let inflight: Promise<void> | null = null

  async function ensureLoaded(force = false): Promise<void> {
    if (!force && state.value === 'ready') return
    if (inflight) return inflight
    state.value = 'loading'
    inflight = fetchAllDicts()
      .then((map) => {
        dicts.value = map ?? {}
        state.value = 'ready'
      })
      .catch(() => {
        state.value = 'error'
      })
      .finally(() => {
        inflight = null
      })
    return inflight
  }

  /** 强制重新拉取（枚举变更后调用）。 */
  async function reload(): Promise<void> {
    state.value = 'idle'
    await ensureLoaded(true)
  }

  /** 重建后端快照并本地刷新（仅运维页面需要用）。 */
  async function refreshFromBackend(): Promise<void> {
    const map = await refreshDicts()
    dicts.value = map ?? {}
    state.value = 'ready'
  }

  function items(type: string): DictItem[] {
    return dicts.value[type] ?? []
  }

  function itemOf(type: string, code?: string | null): DictItem | undefined {
    if (!code) return undefined
    return items(type).find((item) => item.code === code)
  }

  /** 展示文案：未知 code 原样返回，避免「字典没加载」时标签变空白。 */
  function labelOf(type: string, code?: string | null): string {
    if (!code) return ''
    return itemOf(type, code)?.label ?? code
  }

  /** 下拉选项（过滤停用项；停用项需要展示时用 items()）。 */
  function options(type: string): DictOption[] {
    return items(type)
      .filter((item) => item.enabled)
      .map((item) => ({ value: item.code, label: item.label, disabled: !item.enabled }))
  }

  return {
    dicts,
    state,
    ensureLoaded,
    reload,
    refreshFromBackend,
    items,
    itemOf,
    labelOf,
    options
  }
})
