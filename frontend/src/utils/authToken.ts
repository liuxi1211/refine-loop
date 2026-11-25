import { ref } from 'vue'

/**
 * 登录令牌的浏览器存储（唯一读写口）。
 *
 * <p>为什么独立成模块：`api/http.ts` 需要读令牌并在 401 时清理，`stores/auth.ts` 需要写令牌，
 * 两者若互相 import 会形成环（http → store → http）。这里用模块级 `ref` 做「唯一事实源」：
 * 清理后所有依赖它的 computed（如 `isAuthenticated`）会立即失效，避免「localStorage 已清、
 * 路由守卫仍认为已登录」导致登录页被重定向回去的死循环。
 *
 * <p>安全边界：localStorage 可被同源脚本读取，XSS 即可窃取；本项目无富文本/`v-html`，
 * 且令牌有效期短、可服务端撤销，风险可接受。生产若上 HTTPS + 同站部署，可后续改 HttpOnly Cookie。
 */
const TOKEN_KEY = 'refine-loop.auth.token'

function readToken(): string | null {
  try {
    return window.localStorage.getItem(TOKEN_KEY)
  } catch {
    return null
  }
}

/** 进程内令牌（响应式）：登录写入、登出/401 清空。 */
export const authToken = ref<string | null>(readToken())

export function getAuthToken(): string | null {
  return authToken.value
}

export function setAuthToken(token: string): void {
  authToken.value = token
  window.localStorage.setItem(TOKEN_KEY, token)
}

export function clearAuthToken(): void {
  authToken.value = null
  window.localStorage.removeItem(TOKEN_KEY)
}
