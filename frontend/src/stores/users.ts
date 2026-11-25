import { defineStore } from 'pinia'
import { ref } from 'vue'
import { asApiError, type ApiError } from '../api/http'
import type { CreateUserPayload, UpdateUserPayload, UserSummary } from '../api/types'
import * as usersApi from '../api/users'
import { useUiStore } from './ui'

/**
 * 用户管理 store（管理员页）：分页列表 + 新建/修改/重置密码/删除。
 *
 * <p>不变量：写操作成功后重新拉取当前页（以服务端为准，避免本地拼装与后端规则漂移）。
 */
export const useUsersStore = defineStore('users', () => {
  const items = ref<UserSummary[]>([])
  const total = ref(0)
  const page = ref(1)
  const size = ref(20)
  const keyword = ref('')
  const role = ref('')
  const active = ref<'' | 'true' | 'false'>('')
  const loading = ref(false)
  const loaded = ref(false)
  const error = ref<ApiError | null>(null)
  const saving = ref(false)

  async function fetchPage(options: usersApi.FetchOptions = {}): Promise<void> {
    if (!options.silent) {
      loading.value = true
      error.value = null
    }
    try {
      const data = await usersApi.fetchUsers(
        {
          keyword: keyword.value.trim() || undefined,
          role: role.value || undefined,
          active: active.value === '' ? undefined : active.value === 'true',
          page: page.value,
          size: size.value
        },
        { silent: options.silent }
      )
      items.value = data.items
      total.value = data.total
      loaded.value = true
      error.value = null
    } catch (err) {
      error.value = asApiError(err)
    } finally {
      if (!options.silent) loading.value = false
    }
  }

  async function create(payload: CreateUserPayload): Promise<boolean> {
    return runWrite(() => usersApi.createUser(payload), (detail) => `用户 ${detail.username} 已创建`)
  }

  async function update(id: number, payload: UpdateUserPayload): Promise<boolean> {
    return runWrite(() => usersApi.updateUser(id, payload), (detail) => `用户 ${detail.username} 已更新`)
  }

  async function resetPassword(id: number, password: string): Promise<boolean> {
    return runWrite(() => usersApi.resetUserPassword(id, password), () => '密码已重置，该用户需要重新登录')
  }

  async function remove(id: number): Promise<boolean> {
    return runWrite(() => usersApi.deleteUser(id), () => '用户已删除')
  }

  async function runWrite<T>(action: () => Promise<T>, message: (result: T) => string): Promise<boolean> {
    const ui = useUiStore()
    saving.value = true
    try {
      const result = await action()
      ui.pushToast('ok', message(result))
      await fetchPage({ silent: true })
      return true
    } catch {
      // 失败文案已由 http 层统一 toast
      return false
    } finally {
      saving.value = false
    }
  }

  function resetFilters(): void {
    keyword.value = ''
    role.value = ''
    active.value = ''
    page.value = 1
  }

  return {
    items,
    total,
    page,
    size,
    keyword,
    role,
    active,
    loading,
    loaded,
    error,
    saving,
    fetchPage,
    create,
    update,
    resetPassword,
    remove,
    resetFilters
  }
})
