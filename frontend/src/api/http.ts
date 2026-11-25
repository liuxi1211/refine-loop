/**
 * 统一 fetch 封装：
 *  - 解包后端 Result{code,msg,data}；code !== 0 抛 ApiError 并 toast；
 *  - HTTP 非 2xx / 非 JSON（Vite dev proxy 连不上后端时返回的 500 文本）→ 视为「后端未启动或接口不可用」；
 *  - 网络错误/超时同样给出明确错误态，页面据此渲染错误块而不是白屏；
 *  - 通过 ui store 记录后端连通性，供侧边栏展示。
 */
import { useUiStore } from '../stores/ui'
import { clearAuthToken, getAuthToken } from '../utils/authToken'

export interface Result<T> {
  code: number
  msg: string
  data: T
}

export type ApiErrorKind = 'network' | 'timeout' | 'http' | 'business'

export class ApiError extends Error {
  kind: ApiErrorKind
  code: number
  path: string

  constructor(kind: ApiErrorKind, message: string, code = -1, path = '') {
    super(message)
    this.name = 'ApiError'
    this.kind = kind
    this.code = code
    this.path = path
  }
}

/** 后端未启动/代理不可达时的统一文案（页面错误态与 toast 都用它） */
export const BACKEND_OFFLINE_MESSAGE =
  '后端未启动或接口不可用：请确认 127.0.0.1:8080 的 RefineLoop 服务已启动（或检查 Vite 代理配置）'

const TIMEOUT_MESSAGE = '接口请求超时：后端可能未启动或响应过慢，请稍后重试'

export type QueryValue = string | number | boolean | null | undefined

export interface RequestOptions {
  method?: 'GET' | 'POST' | 'PUT' | 'DELETE'
  query?: Record<string, QueryValue>
  body?: unknown
  timeoutMs?: number
  /** 静默请求：失败不 toast（用于轮询/后台刷新），页面仍拿到 ApiError */
  silent?: boolean
}

const LOGIN_PATH = '/api/auth/login'

/** 未认证/令牌过期：清理本地令牌并跳登录页（登录接口本身不再跳转，避免循环）。 */
function handleUnauthorized(path: string): void {
  clearAuthToken()
  if (path === LOGIN_PATH) return
  if (window.location.hash.startsWith('#/login')) return
  window.location.hash = '#/login'
}

function buildUrl(path: string, query?: Record<string, QueryValue>): string {
  if (!query) return path
  const params = new URLSearchParams()
  for (const [key, value] of Object.entries(query)) {
    if (value === undefined || value === null || value === '') continue
    params.set(key, String(value))
  }
  const qs = params.toString()
  return qs ? `${path}?${qs}` : path
}

function notifyError(ui: ReturnType<typeof useUiStore>, error: ApiError, silent?: boolean) {
  if (silent) return
  ui.pushToast('err', error.message)
}

/**
 * 发起请求并解包 Result.data。
 * 失败时已按需 toast，调用方通常只需渲染错误态。
 */
export async function request<T>(path: string, options: RequestOptions = {}): Promise<T> {
  const ui = useUiStore()
  const method = options.method ?? 'GET'
  const url = buildUrl(path, options.query)
  const timeoutMs = options.timeoutMs ?? 15000

  const controller = new AbortController()
  const timer = window.setTimeout(() => controller.abort(), timeoutMs)

  let response: Response
  try {
    const headers: Record<string, string> = { Accept: 'application/json' }
    const token = getAuthToken()
    if (token) {
      headers.Authorization = `Bearer ${token}`
    }
    let body: string | undefined
    if (options.body !== undefined) {
      headers['Content-Type'] = 'application/json'
      body = JSON.stringify(options.body)
    }
    response = await fetch(url, {
      method,
      headers,
      body,
      signal: controller.signal
    })
  } catch (error) {
    const aborted = error instanceof DOMException && error.name === 'AbortError'
    const apiError = aborted
      ? new ApiError('timeout', TIMEOUT_MESSAGE, -1, path)
      : new ApiError('network', BACKEND_OFFLINE_MESSAGE, -1, path)
    ui.markBackend(false)
    notifyError(ui, apiError, options.silent)
    throw apiError
  } finally {
    window.clearTimeout(timer)
  }

  const text = await response.text().catch(() => '')
  let payload: Result<T> | null = null
  if (text) {
    try {
      const parsed = JSON.parse(text) as Result<T>
      if (parsed && typeof parsed.code === 'number') payload = parsed
    } catch {
      payload = null
    }
  }

  // 非 2xx 或响应不是合法的 Result 结构 → 按「接口不可用」处理（例如 dev proxy 连不上后端）
  if (!response.ok || !payload) {
    ui.markBackend(false)
    const apiError = new ApiError(
      'http',
      `${BACKEND_OFFLINE_MESSAGE}（HTTP ${response.status}）`,
      response.status,
      path
    )
    notifyError(ui, apiError, options.silent)
    throw apiError
  }

  ui.markBackend(true)

  if (payload.code !== 0) {
    if (payload.code === 401) {
      handleUnauthorized(path)
    }
    const apiError = new ApiError('business', payload.msg || '请求失败', payload.code, path)
    notifyError(ui, apiError, options.silent)
    throw apiError
  }

  return payload.data
}

export function get<T>(path: string, query?: Record<string, QueryValue>, options: Omit<RequestOptions, 'method' | 'query'> = {}): Promise<T> {
  return request<T>(path, { ...options, query })
}

export function post<T>(path: string, body?: unknown, options: Omit<RequestOptions, 'method' | 'body'> = {}): Promise<T> {
  return request<T>(path, { ...options, method: 'POST', body: body ?? {} })
}

export function put<T>(path: string, body?: unknown, options: Omit<RequestOptions, 'method' | 'body'> = {}): Promise<T> {
  return request<T>(path, { ...options, method: 'PUT', body: body ?? {} })
}

export function del<T>(path: string, query?: Record<string, QueryValue>, options: Omit<RequestOptions, 'method' | 'query'> = {}): Promise<T> {
  return request<T>(path, { ...options, method: 'DELETE', query })
}

/** 页面错误态展示用文案 */
export function describeError(error: ApiError | Error | null | undefined): string {
  if (!error) return ''
  if (error instanceof ApiError) return error.message
  return error.message || '未知错误'
}

export function asApiError(error: unknown): ApiError {
  if (error instanceof ApiError) return error
  return new ApiError('network', error instanceof Error ? error.message : String(error))
}
