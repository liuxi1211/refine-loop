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

function placeholder(name: string) {
  ui.pushToast('info', `${name}：M1 分析端页面，不在本批次范围（见《前端设计详案》）`)
}
</script>

<template>
  <aside class="sidebar">
    <div class="brand">
      <div class="brand-logo">RL</div>
      <div>
        <div class="brand-name">RefineLoop</div>
        <div class="brand-sub">数据中心 · 运维台</div>
      </div>
    </div>

    <div class="nav-group-label">数据接入管理</div>
    <router-link class="nav-item" active-class="active" to="/overview">
      <span class="ico">▦</span>数据总览<span class="cnt">{{ tableCount }}</span>
    </router-link>
    <router-link class="nav-item" active-class="active" to="/jobs">
      <span class="ico">⟳</span>接入作业
      <span v-if="failedCount > 0" class="cnt warn">{{ failedCount }} 失败</span>
    </router-link>

    <div class="nav-group-label">分析工作台</div>
    <div class="nav-item disabled" @click="placeholder('今日简报')"><span class="ico">◈</span>今日简报<span class="cnt">M1</span></div>
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
        <span>后端接口 · 8080</span>
        <span :class="ui.backendState === 'online' ? 'ok' : ui.backendState === 'offline' ? 'off' : ''">
          <span :class="backendDotClass"></span>{{ backendLabel }}
        </span>
      </div>
      <div class="row"><span>最新交易日</span><span class="mono" style="color: #a9b8c6">{{ tradeDate }}</span></div>
      <button class="logout-btn" @click="logout">退出登录</button>
    </div>
  </aside>
</template>

<style scoped>
.logout-btn {
  width: 100%;
  margin-top: 10px;
  padding: 6px 10px;
  border: 1px solid rgba(255, 255, 255, 0.12);
  border-radius: 7px;
  background: rgba(255, 255, 255, 0.04);
  color: #a9b8c6;
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
