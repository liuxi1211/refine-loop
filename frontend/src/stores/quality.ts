import { defineStore } from 'pinia'
import { computed, ref } from 'vue'
import { asApiError, type ApiError } from '../api/http'
import * as qualityApi from '../api/quality'
import type { QualityHistoryItem, QualityReport } from '../api/types'
import { formatDuration } from '../utils/format'
import { qualityResultChip } from '../utils/status'
import { useUiStore } from './ui'

/** 数据质量门（S0）：最近一次结果、立即执行、检查历史 */
export const useQualityStore = defineStore('quality', () => {
  const latest = ref<QualityReport | null>(null)
  const latestLoading = ref(false)
  const latestLoaded = ref(false)
  const latestError = ref<ApiError | null>(null)

  const history = ref<QualityHistoryItem[]>([])
  const historyTotal = ref(0)
  const historyPage = ref(1)
  const historySize = ref(10)
  const historyLoading = ref(false)
  const historyError = ref<ApiError | null>(null)

  const running = ref(false)

  const totalHistoryPages = computed(() => Math.max(1, Math.ceil(historyTotal.value / historySize.value)))

  async function fetchLatest(options: { silent?: boolean; background?: boolean } = {}) {
    if (!options.background) {
      latestLoading.value = true
      latestError.value = null
    }
    try {
      latest.value = await qualityApi.fetchLatestQuality(!!options.silent)
      latestLoaded.value = true
      latestError.value = null
    } catch (err) {
      if (!options.background) latestError.value = asApiError(err)
    } finally {
      if (!options.background) latestLoading.value = false
    }
  }

  async function fetchHistory(page = historyPage.value, options: { silent?: boolean; background?: boolean } = {}) {
    if (!options.background) {
      historyLoading.value = true
      historyError.value = null
    }
    try {
      const data = await qualityApi.fetchQualityHistory(page, historySize.value)
      history.value = Array.isArray(data.items) ? data.items : []
      historyTotal.value = Number(data.total ?? 0)
      historyPage.value = Number(data.page ?? page) || page
      historyError.value = null
    } catch (err) {
      if (!options.background) historyError.value = asApiError(err)
    } finally {
      if (!options.background) historyLoading.value = false
    }
  }

  function setHistoryPage(page: number) {
    const target = Math.min(Math.max(1, page), totalHistoryPages.value)
    void fetchHistory(target)
  }

  /** 立即执行 S0：返回报告供组件做逐项揭示动画 */
  async function runCheck(tradeDate?: string): Promise<QualityReport | null> {
    if (running.value) return null
    const ui = useUiStore()
    running.value = true
    try {
      const report = await qualityApi.runQualityCheck(tradeDate)
      latest.value = report
      latestLoaded.value = true
      latestError.value = null
      const chip = qualityResultChip(report?.result)
      ui.pushToast(
        report?.result === 'FAIL' ? 'err' : 'ok',
        `S0 检查完成：${chip.label} · 耗时 ${formatDuration(report?.durationMs)}`
      )
      void fetchHistory(1, { silent: true, background: true })
      return report
    } catch (err) {
      if (err instanceof Error) {
        // 保持页面可读，错误态由 API 层的 toast + 本 store 的 latestError 共同兜底
        latestError.value = asApiError(err)
      }
      return null
    } finally {
      running.value = false
    }
  }

  return {
    latest,
    latestLoading,
    latestLoaded,
    latestError,
    history,
    historyTotal,
    historyPage,
    historySize,
    historyLoading,
    historyError,
    running,
    totalHistoryPages,
    fetchLatest,
    fetchHistory,
    setHistoryPage,
    runCheck
  }
})
