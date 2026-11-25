import { defineStore } from 'pinia'
import { ref } from 'vue'
import { asApiError, type ApiError } from '../api/http'
import type { WatchlistItem, WatchlistPayload } from '../api/types'
import * as usersApi from '../api/users'
import { useUiStore } from './ui'

/**
 * 我的账户 store：交易偏好 + 自选股（均为当前登录用户维度，后端按令牌过滤）。
 *
 * <p>不变量：写操作成功后以后端返回为准刷新本地列表；偏好是「整组替换」语义，
 * 调用方需先合并未在表单里出现的既有键（见 `utils/preferences.ts`）。
 */
export const useProfileStore = defineStore('profile', () => {
  const preferences = ref<Record<string, string>>({})
  const watchlist = ref<WatchlistItem[]>([])
  const loading = ref(false)
  const loaded = ref(false)
  const saving = ref(false)
  const error = ref<ApiError | null>(null)

  async function fetchAll(options: usersApi.FetchOptions = {}): Promise<void> {
    if (!options.silent) {
      loading.value = true
      error.value = null
    }
    try {
      const [preferenceView, watchlistView] = await Promise.all([
        usersApi.fetchPreferences({ silent: options.silent }),
        usersApi.fetchWatchlist({ silent: options.silent })
      ])
      preferences.value = preferenceView.preferences ?? {}
      watchlist.value = watchlistView.items ?? []
      loaded.value = true
      error.value = null
    } catch (err) {
      error.value = asApiError(err)
    } finally {
      if (!options.silent) loading.value = false
    }
  }

  async function savePreferences(payload: Record<string, string>): Promise<boolean> {
    const ui = useUiStore()
    saving.value = true
    try {
      const view = await usersApi.savePreferences(payload)
      preferences.value = view.preferences ?? {}
      ui.pushToast('ok', '交易偏好已保存')
      return true
    } catch {
      return false
    } finally {
      saving.value = false
    }
  }

  async function addWatchlistItem(payload: WatchlistPayload): Promise<boolean> {
    return runWrite(() => usersApi.createWatchlistItem(payload), '已加入自选股')
  }

  async function updateWatchlistItem(id: number, payload: WatchlistPayload): Promise<boolean> {
    return runWrite(() => usersApi.updateWatchlistItem(id, payload), '自选股已更新')
  }

  async function removeWatchlistItem(id: number): Promise<boolean> {
    return runWrite(() => usersApi.deleteWatchlistItem(id), '自选股已移除')
  }

  async function runWrite<T>(action: () => Promise<T>, message: string): Promise<boolean> {
    const ui = useUiStore()
    saving.value = true
    try {
      await action()
      ui.pushToast('ok', message)
      await fetchAll({ silent: true })
      return true
    } catch {
      return false
    } finally {
      saving.value = false
    }
  }

  return {
    preferences,
    watchlist,
    loading,
    loaded,
    saving,
    error,
    fetchAll,
    savePreferences,
    addWatchlistItem,
    updateWatchlistItem,
    removeWatchlistItem
  }
})
