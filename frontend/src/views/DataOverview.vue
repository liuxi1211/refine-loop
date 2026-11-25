<script setup lang="ts">
import { computed, onMounted, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { describeError } from '../api/http'
import type { TableMeta } from '../api/types'
import StateBlock from '../components/StateBlock.vue'
import StatCard from '../components/StatCard.vue'
import StatusChip from '../components/StatusChip.vue'
import TableDetailDrawer from '../components/TableDetailDrawer.vue'
import ToggleSwitch from '../components/ToggleSwitch.vue'
import { useIngestStore } from '../stores/ingest'
import { useStatsStore } from '../stores/stats'
import { useTablesStore } from '../stores/tables'
import { useUiStore } from '../stores/ui'
import {
  formatInt,
  formatLagDays,
  formatRowCount,
  formatTimestamp
} from '../utils/format'
import { healthDotTone, jobStatusChip, qualityResultChip } from '../utils/status'

const tables = useTablesStore()
const stats = useStatsStore()
const ingest = useIngestStore()
const ui = useUiStore()
const router = useRouter()
const route = useRoute()

const summary = computed(() => tables.summary)
const today = computed(() => stats.today)
const summaryTables = computed(() => today.value?.tables ?? null)

const totalTables = computed(() => summary.value?.total ?? summaryTables.value?.total ?? tables.list.length)
const healthyCount = computed(() => summary.value?.healthy ?? summaryTables.value?.healthy ?? 0)
const lagCount = computed(() => summary.value?.lag ?? summaryTables.value?.lag ?? 0)
const errorCount = computed(() => summary.value?.error ?? summaryTables.value?.error ?? 0)
const idleCount = computed(() => summary.value?.idle ?? summaryTables.value?.idle ?? 0)

const statsLoading = computed(() => (tables.loading && !tables.loaded) || (stats.loading && !today.value))

const jobsValue = computed(() => (today.value?.jobs ? formatInt(today.value.jobs.total) : '—'))
const callsValue = computed(() => (today.value ? formatInt(today.value.tushareCallsToday) : '—'))

const qualityChip = computed(() => qualityResultChip(today.value?.quality?.result))
const qualityTone = computed<'blue' | 'green' | 'amber' | 'red'>(() => {
  const result = (today.value?.quality?.result ?? '').toUpperCase()
  if (result === 'PASS') return 'green'
  if (result === 'PASS_WITH_WARN' || result === 'WARN') return 'amber'
  if (result === 'FAIL') return 'red'
  return 'blue'
})
const qualityTrend = computed(() => {
  const quality = today.value?.quality
  if (!quality) return '尚未执行 S0 检查 · 点击进入质量门'
  return `${formatTimestamp(quality.checkTime, { withSeconds: true })} · 点击查看`
})

function lagCell(item: TableMeta) {
  return formatLagDays(item.lagDays)
}

async function refresh() {
  await Promise.all([tables.fetchOverview(), stats.fetchToday()])
  if (!tables.error) ui.pushToast('ok', '已刷新：行数与最新日期重查询完成')
}

async function triggerDaily() {
  const ack = await ingest.triggerDaily()
  if (!ack) return
  void tables.fetchOverview({ silent: true, background: true })
  void stats.fetchToday({ silent: true, background: true })
  void router.push('/jobs')
}

function goJobs() {
  void router.push('/jobs')
}

function goQuality() {
  void router.push({ path: '/jobs', query: { tab: 'quality' } })
}

function openTable(table: string) {
  void tables.openTable(table)
}

function openFromQuery() {
  const table = typeof route.query.table === 'string' ? route.query.table : ''
  if (table) void tables.openTable(table)
}

onMounted(async () => {
  await Promise.all([tables.fetchOverview(), stats.fetchToday()])
  openFromQuery()
})

watch(
  () => route.query.table,
  () => openFromQuery()
)
</script>

<template>
  <section>
    <div class="page-h rv">
      <div>
        <h2>数据总览</h2>
        <div class="desc">
          {{ totalTables }} 张 Tushare 数据表的健康度与新鲜度一屏总览 · 状态口径：最新业务日期距今 ≤2 个交易日为健康
        </div>
      </div>
      <div class="right">
        <button class="btn pri" :disabled="ingest.triggeringDaily" @click="triggerDaily">
          <span v-if="ingest.triggeringDaily" class="spinner on-dark" style="margin-right: 2px"></span>▶ 一键每日增量
        </button>
        <button class="btn" @click="goJobs">作业历史 →</button>
      </div>
    </div>

    <!-- 表清单接口不可用：整页错误态（不白屏） -->
    <StateBlock
      v-if="tables.error && !tables.loaded"
      kind="error"
      title="数据总览加载失败"
      :description="describeError(tables.error)"
      action-label="重新加载"
      @action="refresh"
    />

    <template v-else>
      <div v-if="stats.error && !today" class="alert err mb14">
        今日统计接口（/stats/today）不可用：{{ describeError(stats.error) }}；下方表清单仍按 /tables 数据展示。
      </div>

      <div class="stats">
        <StatCard label="表健康度" :value="String(healthyCount)" :unit="` / ${totalTables}`" :loading="statsLoading" icon="▦">
          <b class="txt-ok">{{ healthyCount }} 健康</b> ·
          <b class="txt-warn">{{ lagCount }} 滞后</b> ·
          <b class="txt-err">{{ errorCount }} 失败</b> ·
          <b class="txt-dim">{{ idleCount }} 停用</b>
        </StatCard>
        <StatCard label="今日作业" :value="jobsValue" unit="次" tone="green" :loading="statsLoading" icon="⟳">
          <template v-if="today?.jobs">
            <b class="txt-ok">{{ today.jobs.success }} 成功</b> ·
            <b class="txt-err">{{ today.jobs.failed }} 失败</b> ·
            <b style="color: var(--blue)">{{ today.jobs.running }} 运行中</b>
          </template>
          <template v-else>—</template>
        </StatCard>
        <StatCard label="今日 Tushare 调用" :value="callsValue" :loading="statsLoading" icon="≋">
          <template v-if="today">
            数据来源 rate_limit_counter 今日汇总 · 最新交易日
            <b class="mono">{{ today.date || '—' }}</b>
          </template>
          <template v-else>—</template>
        </StatCard>
        <StatCard
          label="数据质量门 (S0)"
          :value="qualityChip.label"
          :tone="qualityTone"
          :loading="statsLoading"
          small
          clickable
          icon="✓"
          @click="goQuality"
        >
          {{ qualityTrend }}
        </StatCard>
      </div>

      <div class="card rv d2">
        <div class="card-h">
          <h3>数据表清单</h3>
          <span class="sub">按分组展示 · 点击行进入表详情</span>
          <div class="right">
            <button class="btn sm" :disabled="tables.loading" @click="refresh">刷新统计</button>
          </div>
        </div>
        <div class="card-b" style="padding-bottom: 6px">
          <div class="filters">
            <span class="f-chip" :class="{ on: tables.group === 'all' }" @click="tables.group = 'all'">
              全部 {{ tables.totalCount }}
            </span>
            <span
              v-for="filter in tables.filters"
              :key="filter.group"
              class="f-chip"
              :class="{ on: tables.group === filter.group }"
              @click="tables.group = filter.group"
            >
              {{ filter.label }} {{ filter.count }}
            </span>
            <div class="search">
              <input v-model="tables.keyword" placeholder="搜索表名 / api_name" />
            </div>
          </div>
        </div>

        <StateBlock v-if="tables.loading && !tables.loaded" kind="loading" title="正在加载 41 张数据表清单…" />
        <div v-else class="scroll-y" style="max-height: calc(100vh - 420px)">
          <table class="tbl">
            <thead>
              <tr>
                <th style="width: 24px"></th>
                <th>数据表</th>
                <th>api_name</th>
                <th>分组</th>
                <th>更新策略</th>
                <th class="num" style="text-align: right">行数</th>
                <th>最新业务日期</th>
                <th style="text-align: center">滞后天数</th>
                <th>上次作业</th>
                <th>每日增量</th>
                <th style="text-align: right">快捷操作</th>
              </tr>
            </thead>
            <tbody>
              <template v-for="section in tables.sections" :key="section.group">
                <tr>
                  <td
                    colspan="11"
                    style="background: #fafbfc; font-weight: 600; font-size: 12px; color: var(--tx-2); padding: 7px 14px"
                  >
                    {{ section.group }}
                    <span class="mono" style="font-weight: 400">{{ section.rows.length }} 表</span>
                  </td>
                </tr>
                <tr v-for="item in section.rows" :key="item.table" style="cursor: pointer" @click="openTable(item.table)">
                  <td><span class="dot" :class="healthDotTone(item.health)"></span></td>
                  <td><b class="mono" style="font-size: 12.5px">{{ item.table }}</b></td>
                  <td class="mono dim" style="font-size: 11.5px">{{ item.api }}</td>
                  <td class="dim" style="font-size: 12px">{{ item.group }}</td>
                  <td class="dim" style="font-size: 12px">{{ item.strategyLabel || item.strategy || '—' }}</td>
                  <td class="num" style="text-align: right">{{ formatRowCount(item.rowCount, !!item.rowCountApprox) }}</td>
                  <td class="mono dim" style="font-size: 12px">{{ item.latestDate || '—' }}</td>
                  <td style="text-align: center">
                    <span class="mono" :class="lagCell(item).tone" style="font-size: 12px">{{ lagCell(item).text }}</span>
                  </td>
                  <td>
                    <div style="display: flex; align-items: center; gap: 6px">
                      <StatusChip
                        :tone="jobStatusChip(item.lastJobStatus, 0).tone"
                        :label="jobStatusChip(item.lastJobStatus, 0).label"
                      />
                      <span class="num dim" style="font-size: 11.5px">{{ formatTimestamp(item.lastJobTime) }}</span>
                    </div>
                  </td>
                  <td @click.stop>
                    <ToggleSwitch
                      :model-value="item.dailyEnabled"
                      :pending="tables.isToggling(item.table)"
                      @update:model-value="tables.toggleDaily(item.table, $event)"
                    />
                  </td>
                  <td @click.stop>
                    <div class="ops">
                      <button class="btn sm" :disabled="tables.pendingRuns.includes(item.table)" @click="tables.runTable(item.table)">
                        增量
                      </button>
                      <button class="btn sm txt" @click="openTable(item.table)">详情</button>
                    </div>
                  </td>
                </tr>
              </template>
              <tr v-if="!tables.sections.length">
                <td colspan="11">
                  <div class="empty"><div class="big">⌕</div>没有匹配的数据表</div>
                </td>
              </tr>
            </tbody>
          </table>
        </div>
        <div class="pgn">
          <span>当前展示 <b class="mono">{{ tables.totalFiltered }}</b> / {{ tables.totalCount }} 张表</span>
          <span class="meta">状态口径：lagDays ≤2 健康 · &gt;7 滞后 · 最近作业 FAILED 失败 · 无数据未回填</span>
        </div>
      </div>
    </template>

    <TableDetailDrawer />
  </section>
</template>
