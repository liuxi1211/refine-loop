import { defineStore } from 'pinia'
import { ref } from 'vue'
import { asApiError, type ApiError } from '../api/http'
import { fetchStatsToday } from '../api/stats'
import type { StatsToday } from '../api/types'

/** 今日统计（/stats/today）：总览页统计卡 + 侧边栏「今日失败」计数 */
export const useStatsStore = defineStore('stats', () => {
  const today = ref<StatsToday | null>(null)
  const loading = ref(false)
  const loaded = ref(false)
  const error = ref<ApiError | null>(null)

  async function fetchToday(options: { silent?: boolean; background?: boolean } = {}) {
    if (!options.background) {
      loading.value = true
      error.value = null
    }
    try {
      today.value = await fetchStatsToday(options.silent)
      loaded.value = true
      error.value = null
    } catch (err) {
      if (!options.background) error.value = asApiError(err)
    } finally {
      if (!options.background) loading.value = false
    }
  }

  return { today, loading, loaded, error, fetchToday }
})
