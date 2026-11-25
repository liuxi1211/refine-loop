import { defineStore } from 'pinia'
import { computed, ref } from 'vue'
import { asApiError, type ApiError } from '../api/http'
import * as tablesApi from '../api/tables'
import type { TableDetail, TableMeta, TablesSummary } from '../api/types'
import { useUiStore } from './ui'

/** 分组顺序与原型一致 */
export const GROUP_ORDER = ['维表', '行情', '财务', '事件', '指数与市场'] as const

export interface TableSection {
  group: string
  rows: TableMeta[]
}

export interface FetchOptions {
  /** 失败不 toast（后台刷新/轮询） */
  silent?: boolean
  /** 不触发整页 loading（局部刷新） */
  background?: boolean
}

export const useTablesStore = defineStore('tables', () => {
  /* ---------------- 总览 ---------------- */
  const summary = ref<TablesSummary | null>(null)
  const list = ref<TableMeta[]>([])
  const loading = ref(false)
  const loaded = ref(false)
  const error = ref<ApiError | null>(null)
  const group = ref<string>('all')
  const keyword = ref('')

  /* ---------------- 抽屉详情 ---------------- */
  const drawerOpen = ref(false)
  const activeTable = ref<string | null>(null)
  const detail = ref<TableDetail | null>(null)
  const detailLoading = ref(false)
  const detailError = ref<ApiError | null>(null)

  /* ---------------- 操作进行中标记 ---------------- */
  const pendingToggles = ref<string[]>([])
  const pendingRuns = ref<string[]>([])
  const pendingBackfills = ref<string[]>([])

  const groupCounts = computed<Record<string, number>>(() => {
    const counts: Record<string, number> = {}
    for (const g of GROUP_ORDER) counts[g] = 0
    for (const item of list.value) {
      const key = item.group ?? '其他'
      counts[key] = (counts[key] ?? 0) + 1
    }
    return counts
  })

  const filters = computed(() =>
    GROUP_ORDER.map((g) => ({ group: g as string, label: g as string, count: groupCounts.value[g] ?? 0 }))
  )

  const totalCount = computed(() => list.value.length)

  const sections = computed<TableSection[]>(() => {
    const kw = keyword.value.trim().toLowerCase()
    const match = (item: TableMeta) => {
      if (!kw) return true
      return (
        item.table.toLowerCase().includes(kw) ||
        (item.api ?? '').toLowerCase().includes(kw) ||
        (item.chineseName ?? '').toLowerCase().includes(kw)
      )
    }
    const groups = group.value === 'all' ? [...GROUP_ORDER] : GROUP_ORDER.filter((g) => g === group.value)
    const extraGroups = [
      ...new Set(list.value.map((item) => item.group).filter((g) => !(GROUP_ORDER as readonly string[]).includes(g)))
    ]
    for (const g of extraGroups) groups.push(g as (typeof GROUP_ORDER)[number])
    return groups
      .map((g) => ({ group: g as string, rows: list.value.filter((item) => item.group === g && match(item)) }))
      .filter((section) => section.rows.length > 0)
  })

  const totalFiltered = computed(() => sections.value.reduce((sum, section) => sum + section.rows.length, 0))

  function metaOf(table: string): TableMeta | undefined {
    return list.value.find((item) => item.table === table)
  }

  function isToggling(table: string): boolean {
    return pendingToggles.value.includes(table)
  }

  function isRunning(table: string): boolean {
    return pendingRuns.value.includes(table) || pendingBackfills.value.includes(table)
  }

  async function fetchOverview(options: FetchOptions = {}) {
    if (!options.background) {
      loading.value = true
      error.value = null
    }
    try {
      const data = await tablesApi.fetchTables({ silent: options.silent })
      summary.value = data.summary ?? null
      list.value = Array.isArray(data.tables) ? data.tables : []
      loaded.value = true
      error.value = null
    } catch (err) {
      if (!options.background) error.value = asApiError(err)
    } finally {
      if (!options.background) loading.value = false
    }
  }

  async function reloadDetail(options: FetchOptions = {}) {
    if (activeTable.value) await loadDetail(activeTable.value, options)
  }

  async function loadDetail(table: string, options: FetchOptions = {}) {
    if (!options.background) {
      detailLoading.value = true
      detailError.value = null
    }
    try {
      const data = await tablesApi.fetchTableDetail(table, { silent: options.silent })
      if (activeTable.value === table) {
        detail.value = data
        detailError.value = null
      }
    } catch (err) {
      if (!options.background) detailError.value = asApiError(err)
    } finally {
      if (!options.background) detailLoading.value = false
    }
  }

  async function openTable(table: string) {
    drawerOpen.value = true
    activeTable.value = table
    detail.value = null
    detailError.value = null
    await loadDetail(table)
  }

  function closeDrawer() {
    drawerOpen.value = false
    activeTable.value = null
    detail.value = null
    detailError.value = null
  }

  async function toggleDaily(table: string, enabled: boolean) {
    if (pendingToggles.value.includes(table)) return
    const ui = useUiStore()
    const meta = metaOf(table)
    const previous = meta?.dailyEnabled
    pendingToggles.value.push(table)
    // 乐观更新，失败回滚
    if (meta) meta.dailyEnabled = enabled
    if (detail.value && detail.value.table === table) detail.value.dailyEnabled = enabled
    try {
      const result = await tablesApi.toggleDaily(table, enabled)
      const actual = typeof result?.dailyEnabled === 'boolean' ? result.dailyEnabled : enabled
      if (meta) meta.dailyEnabled = actual
      if (detail.value && detail.value.table === table) detail.value.dailyEnabled = actual
      ui.pushToast('ok', `${table} 每日增量已${actual ? '启用' : '停用'}${actual ? '' : '，配置留痕 sys_config'}`)
      // 后台刷新 health / summary，不打断当前视图
      void fetchOverview({ silent: true, background: true })
    } catch {
      if (meta && typeof previous === 'boolean') meta.dailyEnabled = previous
      if (detail.value && detail.value.table === table && typeof previous === 'boolean') {
        detail.value.dailyEnabled = previous
      }
    } finally {
      pendingToggles.value = pendingToggles.value.filter((item) => item !== table)
    }
  }

  async function runTable(table: string) {
    if (pendingRuns.value.includes(table)) return
    const ui = useUiStore()
    pendingRuns.value.push(table)
    try {
      const ack = await tablesApi.runTable(table)
      ui.pushToast('ok', ack?.message || `${table} 增量更新已提交，作业进入队列`)
      // 稍等片刻刷新详情，让作业时间线/行数反映新作业
      window.setTimeout(() => {
        if (activeTable.value === table) void loadDetail(table, { silent: true, background: true })
      }, 1500)
    } catch {
      /* toast 已由 API 层统一提示 */
    } finally {
      pendingRuns.value = pendingRuns.value.filter((item) => item !== table)
    }
  }

  async function backfillTable(table: string, range: tablesApi.BackfillRange): Promise<boolean> {
    if (pendingBackfills.value.includes(table)) return false
    const ui = useUiStore()
    pendingBackfills.value.push(table)
    try {
      const ack = await tablesApi.backfillTable(table, range)
      const scope = range.start || range.end ? `${range.start || '起始'} → ${range.end || '最新'}` : '全部历史'
      ui.pushToast('ok', ack?.message || `${table} 全量回填已提交（${scope}），异步执行中`)
      return true
    } catch {
      return false
    } finally {
      pendingBackfills.value = pendingBackfills.value.filter((item) => item !== table)
    }
  }

  return {
    // state
    summary,
    list,
    loading,
    loaded,
    error,
    group,
    keyword,
    drawerOpen,
    activeTable,
    detail,
    detailLoading,
    detailError,
    pendingToggles,
    pendingRuns,
    pendingBackfills,
    // getters
    groupCounts,
    filters,
    sections,
    totalCount,
    totalFiltered,
    // helpers
    metaOf,
    isToggling,
    isRunning,
    // actions
    fetchOverview,
    loadDetail,
    reloadDetail,
    openTable,
    closeDrawer,
    toggleDaily,
    runTable,
    backfillTable
  }
})
