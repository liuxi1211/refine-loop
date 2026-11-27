<script setup lang="ts">
import { computed } from 'vue'
import { useRouter } from 'vue-router'
import { useAuthStore } from '../stores/auth'
import { useStatsStore } from '../stores/stats'
import { useTablesStore } from '../stores/tables'
import { useUiStore } from '../stores/ui'
import { formatBusinessDate } from '../utils/format'

const tables = useTablesStore()
const stats = useStatsStore()
const ui = useUiStore()
const auth = useAuthStore()
const router = useRouter()

const tableCount = computed(() => {
  const total = tables.summary?.total ?? tables.list.length
  return total > 0 ? String(total) : '—'
})

const failedCount = computed(() => stats.today?.jobs?.failed ?? 0)

const tradeDate = computed(() => formatBusinessDate(stats.today?.date))

const backendLabel = computed(() => {
  if (ui.backendState === 'online') return '在线'
  if (ui.backendState === 'offline') return '离线'
  return '未连接'
})

const backendDotClass = computed(() => {
  if (ui.backendState === 'online') return 'token-dot'
  if (ui.backendState === 'offline') return 'token-dot off'
  return 'token-dot unknown'
})

async function logout() {
  const ok = await ui.confirm({
    title: '退出登录',
    body: `确认退出账号「${auth.user?.username ?? ''}」？`,
    okText: '退出',
    tone: 'primary'
  })
  if (!ok) return
  await auth.logout()
  await router.replace('/login')
}

</script>

<template>
  <aside class="sidebar">
    <div class="brand">
      <div class="brand-logo">RL</div>
      <div>
        <div class="brand-name">RefineLoop</div>
        <div class="brand-sub">投研工作台 · 数据中心</div>
      </div>
    </div>

    <div class="nav-group-label">数据中心</div>
    <router-link class="nav-item" active-class="active" to="/data/overview">
      <span class="ico">▦</span>数据总览<span class="cnt">{{ tableCount }}</span>
    </router-link>
    <router-link class="nav-item" active-class="active" to="/data/jobs">
      <span class="ico">⟳</span>接入与回填
      <span v-if="failedCount > 0" class="cnt warn">{{ failedCount }} 失败</span>
    </router-link>
    <router-link class="nav-item" active-class="active" :to="{ path: '/data/jobs', query: { tab: 'quality' } }">
      <span class="ico">✓</span>数据质量门
    </router-link>

    <div class="nav-group-label">投研工作台</div>
    <div class="nav-note">分析闭环将在 T18 后接入；当前先把数据准备与判断地基跑通。</div>

    <div class="nav-group-label">账户</div>
    <router-link class="nav-item" active-class="active" to="/profile">
      <span class="ico">◉</span>我的账户
    </router-link>

    <template v-if="auth.isAdmin">
      <div class="nav-group-label">系统管理</div>
      <router-link class="nav-item" active-class="active" to="/users">
        <span class="ico">☰</span>用户管理
      </router-link>
    </template>

    <div class="side-foot">
      <div class="row">
        <span>{{ auth.displayName }}</span>
        <span class="mono">{{ auth.user?.roleLabel || '—' }}</span>
      </div>
      <div class="row">
        <span>后端状态</span>
        <span :class="ui.backendState === 'online' ? 'ok' : ui.backendState === 'offline' ? 'off' : ''">
          <span :class="backendDotClass"></span>{{ backendLabel }}
        </span>
      </div>
      <div class="row"><span>当前业务日</span><span class="mono trade-date">{{ tradeDate }}</span></div>
      <button class="logout-btn" @click="logout">退出登录</button>
    </div>
  </aside>
</template>

<style scoped>
.nav-note {
  margin: 0 12px 10px;
  color: var(--tx-3);
  font-size: 11.5px;
  line-height: 1.6;
}

.trade-date {
  color: var(--tx-3);
}

.logout-btn {
  width: 100%;
  margin-top: 10px;
  padding: 6px 10px;
  border: 1px solid rgba(255, 255, 255, 0.12);
  border-radius: 7px;
  background: rgba(255, 255, 255, 0.04);
  color: var(--tx-3);
  font-size: 11.5px;
  cursor: pointer;
  transition: 0.15s;
}

.logout-btn:hover {
  background: rgba(220, 38, 38, 0.18);
  border-color: rgba(248, 113, 113, 0.4);
  color: #fca5a5;
}

@media (max-width: 900px) {
  .side-foot {
    display: none;
  }
}
</style>
