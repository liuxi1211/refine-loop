import { defineStore } from 'pinia'
import { ref } from 'vue'

export type ToastType = 'ok' | 'err' | 'info'
export type BackendState = 'unknown' | 'online' | 'offline'

export interface ToastItem {
  id: number
  type: ToastType
  message: string
}

export interface ConfirmOptions {
  title: string
  body?: string
  okText?: string
  cancelText?: string
  tone?: 'danger' | 'primary'
}

interface ConfirmState {
  open: boolean
  title: string
  body: string
  okText: string
  cancelText: string
  tone: 'danger' | 'primary'
}

/**
 * 全局 UI 状态：toast 队列、确认弹窗、后端连通性。
 * toast 由 API 层与业务动作统一调用，组件只负责渲染。
 */
export const useUiStore = defineStore('ui', () => {
  const toasts = ref<ToastItem[]>([])
  const backendState = ref<BackendState>('unknown')
  const confirmState = ref<ConfirmState>({
    open: false,
    title: '',
    body: '',
    okText: '确认',
    cancelText: '取消',
    tone: 'danger'
  })

  let toastSeq = 0
  let confirmResolver: ((value: boolean) => void) | null = null
  let lastMessage = ''
  let lastAt = 0

  function pushToast(type: ToastType, message: string) {
    const now = Date.now()
    // 同一批请求同时失败时避免 toast 刷屏
    if (type === 'err' && message === lastMessage && now - lastAt < 2500) return
    lastMessage = message
    lastAt = now
    const id = ++toastSeq
    toasts.value.push({ id, type, message })
    window.setTimeout(() => dismissToast(id), 3600)
  }

  function dismissToast(id: number) {
    toasts.value = toasts.value.filter((item) => item.id !== id)
  }

  function markBackend(online: boolean) {
    backendState.value = online ? 'online' : 'offline'
  }

  function confirm(options: ConfirmOptions): Promise<boolean> {
    if (confirmResolver) {
      confirmResolver(false)
      confirmResolver = null
    }
    confirmState.value = {
      open: true,
      title: options.title,
      body: options.body ?? '',
      okText: options.okText ?? '确认',
      cancelText: options.cancelText ?? '取消',
      tone: options.tone ?? 'danger'
    }
    return new Promise<boolean>((resolve) => {
      confirmResolver = resolve
    })
  }

  function resolveConfirm(ok: boolean) {
    confirmState.value.open = false
    const resolver = confirmResolver
    confirmResolver = null
    if (resolver) resolver(ok)
  }

  return {
    toasts,
    backendState,
    confirmState,
    pushToast,
    dismissToast,
    markBackend,
    confirm,
    resolveConfirm
  }
})
