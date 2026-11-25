import { get, post } from './http'
import type { CurrentUser, LoginResult } from './types'

/** 登录（公开端点）：成功返回令牌与当前用户；失败由 http 层抛 ApiError（401）。 */
export function login(username: string, password: string): Promise<LoginResult> {
  return post<LoginResult>('/api/auth/login', { username, password })
}

/** 登出：撤销当前令牌（幂等，失败也不应阻塞前端清理）。 */
export function logout(): Promise<void> {
  return post<void>('/api/auth/logout')
}

/** 当前登录用户与权限码（前端据此渲染菜单/按钮）。 */
export function fetchMe(silent = true): Promise<CurrentUser> {
  return get<CurrentUser>('/api/auth/me', undefined, { silent })
}

/** 自助改密（成功后原会话全部失效，需要重新登录）。 */
export function changePassword(oldPassword: string, newPassword: string): Promise<void> {
  return post<void>('/api/auth/password', { oldPassword, newPassword })
}
