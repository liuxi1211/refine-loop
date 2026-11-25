import { del, get, post, put } from './http'
import type {
  CreateUserPayload,
  PageResult,
  PreferenceView,
  UpdateUserPayload,
  UserDetail,
  UserQuery,
  UserSummary,
  WatchlistItem,
  WatchlistOverview,
  WatchlistPayload
} from './types'

export interface FetchOptions {
  /** 失败不 toast（后台刷新） */
  silent?: boolean
}

/* ---------------- 用户管理（管理员） ---------------- */

export function fetchUsers(query: UserQuery, options: FetchOptions = {}): Promise<PageResult<UserSummary>> {
  return get<PageResult<UserSummary>>(
    '/api/users',
    {
      keyword: query.keyword,
      role: query.role,
      active: query.active,
      page: query.page,
      size: query.size
    },
    { silent: options.silent }
  )
}

export function fetchUser(id: number, options: FetchOptions = {}): Promise<UserDetail> {
  return get<UserDetail>(`/api/users/${id}`, undefined, { silent: options.silent })
}

export function createUser(payload: CreateUserPayload): Promise<UserDetail> {
  return post<UserDetail>('/api/users', payload)
}

export function updateUser(id: number, payload: UpdateUserPayload): Promise<UserDetail> {
  return put<UserDetail>(`/api/users/${id}`, payload)
}

export function resetUserPassword(id: number, password: string): Promise<void> {
  return post<void>(`/api/users/${id}/password`, { password })
}

export function deleteUser(id: number): Promise<void> {
  return del<void>(`/api/users/${id}`)
}

/* ---------------- 我的交易偏好 ---------------- */

export function fetchPreferences(options: FetchOptions = {}): Promise<PreferenceView> {
  return get<PreferenceView>('/api/users/me/preferences', undefined, { silent: options.silent })
}

export function savePreferences(preferences: Record<string, string>): Promise<PreferenceView> {
  return put<PreferenceView>('/api/users/me/preferences', { preferences })
}

/* ---------------- 我的自选股 ---------------- */

export function fetchWatchlist(options: FetchOptions = {}): Promise<WatchlistOverview> {
  return get<WatchlistOverview>('/api/users/me/watchlist', undefined, { silent: options.silent })
}

export function createWatchlistItem(payload: WatchlistPayload): Promise<WatchlistItem> {
  return post<WatchlistItem>('/api/users/me/watchlist', payload)
}

export function updateWatchlistItem(id: number, payload: WatchlistPayload): Promise<WatchlistItem> {
  return put<WatchlistItem>(`/api/users/me/watchlist/${id}`, payload)
}

export function deleteWatchlistItem(id: number): Promise<void> {
  return del<void>(`/api/users/me/watchlist/${id}`)
}
