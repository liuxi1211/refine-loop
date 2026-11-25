import { defineStore } from 'pinia'
import { computed, ref, watch } from 'vue'
import * as authApi from '../api/auth'
import { asApiError, type ApiError } from '../api/http'
import type { CurrentUser } from '../api/types'
import { authToken, clearAuthToken, setAuthToken } from '../utils/authToken'

/**
 * 登录态 store：令牌 + 当前用户 + 权限判定。
 *
 * <p>不变量：令牌的唯一事实源是 `utils/authToken.ts`（http 层只认它）；`user` 只在已登录时有值；
 * 令牌被清空（登出或 401）时必须同时清空 `user`，避免「界面仍显示旧用户」。
 */
export const useAuthStore = defineStore('auth', () => {
  const user = ref<CurrentUser | null>(null)
  const loading = ref(false)
  const error = ref<ApiError | null>(null)

  const isAuthenticated = computed(() => Boolean(authToken.value))
  const isAdmin = computed(() => user.value?.role === 'ADMIN')
  const displayName = computed(() => user.value?.displayName || user.value?.username || '未登录')

  // 令牌被 http 层清理（401/过期）时，同步清空用户，避免残留身份
  watch(authToken, (value) => {
    if (!value) {
      user.value = null
    }
  })

  function hasPermission(code: string): boolean {
    return user.value?.permissions?.includes(code) ?? false
  }

  async function login(username: string, password: string): Promise<void> {
    loading.value = true
    error.value = null
    try {
      const result = await authApi.login(username, password)
      setAuthToken(result.token)
      user.value = result.user
    } catch (err) {
      error.value = asApiError(err)
      throw err
    } finally {
      loading.value = false
    }
  }

  /** 用已存令牌拉取当前用户（刷新页面后恢复登录态）。 */
  async function loadMe(): Promise<void> {
    if (!authToken.value) return
    try {
      user.value = await authApi.fetchMe()
    } catch (err) {
      error.value = asApiError(err)
      throw err
    }
  }

  async function logout(): Promise<void> {
    try {
      await authApi.logout()
    } catch {
      // 令牌可能已过期/被撤销：登出接口失败不影响本地清理
    } finally {
      clearAuthToken()
    }
  }

  return {
    user,
    loading,
    error,
    isAuthenticated,
    isAdmin,
    displayName,
    hasPermission,
    login,
    loadMe,
    logout
  }
})
