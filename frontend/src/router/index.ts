import { createRouter, createWebHashHistory } from 'vue-router'
import type { RouteRecordRaw } from 'vue-router'
import { useAuthStore } from '../stores/auth'
import DataOverview from '../views/DataOverview.vue'
import IngestJobs from '../views/IngestJobs.vue'
import LoginView from '../views/LoginView.vue'
import ProfileView from '../views/ProfileView.vue'
import UserManagementView from '../views/UserManagementView.vue'

declare module 'vue-router' {
  interface RouteMeta {
    title?: string
    /** 公开页面（无需登录）：仅登录页。 */
    public?: boolean
    /** blank = 不渲染侧边栏/顶栏（登录页）。 */
    layout?: 'blank'
    /** 进入该路由所需的权限码（与后端 Permission 枚举一致）。 */
    permission?: string
  }
}

const routes: RouteRecordRaw[] = [
  {
    path: '/login',
    name: 'login',
    component: LoginView,
    meta: { title: '登录', public: true, layout: 'blank' }
  },
  { path: '/', redirect: '/overview' },
  {
    path: '/overview',
    name: 'overview',
    component: DataOverview,
    meta: { title: '数据总览' }
  },
  {
    path: '/jobs',
    name: 'jobs',
    component: IngestJobs,
    meta: { title: '接入作业' }
  },
  {
    path: '/profile',
    name: 'profile',
    component: ProfileView,
    meta: { title: '我的账户' }
  },
  {
    path: '/users',
    name: 'users',
    component: UserManagementView,
    meta: { title: '用户管理', permission: 'user:read' }
  },
  { path: '/:pathMatch(.*)*', redirect: '/overview' }
]

/** hash 模式：纯静态部署到 Nginx 无需 history 回退配置（与《前端设计详案》§7 一致） */
const router = createRouter({
  history: createWebHashHistory(),
  routes
})

/**
 * 全局守卫：未登录 → 登录页（带 redirect）；已登录但尚未拉取用户 → 先拉取；
 * 需要权限码的路由在无权限时回数据总览（后端仍会 403，前端只负责不展示入口）。
 */
router.beforeEach(async (to) => {
  const auth = useAuthStore()

  if (to.meta.public) {
    if (to.name === 'login' && auth.isAuthenticated) {
      return { path: '/overview' }
    }
    return true
  }

  if (!auth.isAuthenticated) {
    return { path: '/login', query: { redirect: to.fullPath } }
  }

  if (!auth.user) {
    try {
      await auth.loadMe()
    } catch {
      return { path: '/login', query: { redirect: to.fullPath } }
    }
  }

  const permission = to.meta.permission
  if (typeof permission === 'string' && !auth.hasPermission(permission)) {
    return { path: '/overview' }
  }
  return true
})

router.afterEach((to) => {
  const title = typeof to.meta.title === 'string' ? to.meta.title : ''
  document.title = title ? `${title} · RefineLoop 数据中心` : 'RefineLoop 数据中心'
})

export default router
