<script setup lang="ts">
import { computed, onMounted } from 'vue'
import { useRouter } from 'vue-router'
import { describeError } from '../api/http'
import type { IngestJobView } from '../api/types'
import { useIngestStore } from '../stores/ingest'
import { useTablesStore } from '../stores/tables'
import { useUiStore } from '../stores/ui'
import {
  formatDuration,
  formatInt,
  formatJobType,
  formatTimestamp
} from '../utils/format'
import { jobStatusChip } from '../utils/status'
import StateBlock from './StateBlock.vue'
import StatusChip from './StatusChip.vue'

const ingest = useIngestStore()
const tables = useTablesStore()
const ui = useUiStore()
const router = useRouter()

const STATUS_OPTIONS = [
  { value: '', label: '全部状态' },
  { value: 'RUNNING', label: '运行中' },
  { value: 'SUCCESS', label: '成功' },
  { value: 'FAILED', label: '失败' },
  { value: 'PENDING', label: '等待' },
  { value: 'SKIPPED', label: '跳过' }
]

const tableOptions = computed(() => [
  { value: '', label: '全部表' },
  ...tables.list.map((item) => ({ value: item.table, label: item.table }))
])

const pageNumbers = computed<number[]>(() => {
  const total = ingest.totalJobPages
  const current = ingest.jobsPage
  const start = Math.max(1, Math.min(current - 2, total - 4))
  const pages: number[] = []
  for (let page = start; page <= Math.min(total, start + 4); page += 1) pages.push(page)
  return pages
})

function onStatusChange(event: Event) {
  ingest.setJobStatus((event.target as HTMLSelectElement).value)
}

function onTableChange(event: Event) {
  ingest.setJobTable((event.target as HTMLSelectElement).value)
}

function openTableDetail(tableName: string) {
  void router.push({ path: '/overview', query: { table: tableName } })
}

async function retry(job: IngestJobView) {
  const confirmed = await ui.confirm({
    title: '重试失败作业',
    body: `确认重试 ${job.tableName} 的失败作业（#${job.id}）？\n限流器将等待下一分钟窗口，重试结果写入 ingest_log。`,
    okText: '重试',
    tone: 'primary'
  })
  if (!confirmed) return
  await ingest.retryJob(job.id)
}

onMounted(() => {
  if (!tables.loaded) void tables.fetchOverview({ silent: true, background: true })
  if (!ingest.jobsLoaded) void ingest.fetchJobs()
})
</script>

<template>
  <div class="card rv d2">
    <div class="card-h">
      <h3>作业历史</h3>
      <span class="sub">ingest_log</span>
      <div class="right">
        <select class="sel" style="width: 130px; padding: 5px 9px; font-size: 12px" :value="ingest.jobsStatus" @change="onStatusChange">
          <option v-for="option in STATUS_OPTIONS" :key="option.value" :value="option.value">{{ option.label }}</option>
        </select>
        <select class="sel" style="width: 150px; padding: 5px 9px; font-size: 12px" :value="ingest.jobsTable" @change="onTableChange">
          <option v-for="option in tableOptions" :key="option.value" :value="option.value">{{ option.label }}</option>
        </select>
        <button class="btn sm" :disabled="ingest.jobsLoading" @click="ingest.fetchJobs()">刷新</button>
      </div>
    </div>

    <StateBlock
      v-if="ingest.jobsError && !ingest.jobs.length"
      kind="error"
      compact
      title="作业历史加载失败"
      :description="describeError(ingest.jobsError)"
      action-label="重试"
      @action="ingest.fetchJobs()"
    />
    <StateBlock v-else-if="ingest.jobsLoading && !ingest.jobs.length" kind="loading" compact title="正在加载作业历史…" />

    <div v-else class="scroll-y" style="max-height: 460px">
      <table class="tbl">
        <thead>
          <tr>
            <th>开始时间</th>
            <th>表</th>
            <th>类型</th>
            <th>范围</th>
            <th style="text-align: right">拉取行数</th>
            <th style="text-align: right">落库行数</th>
            <th style="text-align: right">耗时</th>
            <th>状态</th>
            <th>错误摘要</th>
            <th style="text-align: right">操作</th>
          </tr>
        </thead>
        <tbody>
          <tr v-for="job in ingest.jobs" :key="job.id">
            <td class="num dim">{{ formatTimestamp(job.startTime || job.createdAt) }}</td>
            <td><code class="k mono">{{ job.tableName }}</code></td>
            <td class="dim">{{ formatJobType(job.jobType) }}</td>
            <td class="mono dim">{{ job.rangeParams || '—' }}</td>
            <td class="num" style="text-align: right">{{ formatInt(job.rowsFetched) }}</td>
            <td class="num" style="text-align: right">{{ formatInt(job.rowsWritten) }}</td>
            <td class="num dim" style="text-align: right">{{ formatDuration(job.durationMs) }}</td>
            <td>
              <StatusChip
                :tone="jobStatusChip(job.status, job.retryCount ?? 0).tone"
                :label="jobStatusChip(job.status, job.retryCount ?? 0).label"
              />
            </td>
            <td
              class="ellipsis"
              :style="{ color: job.status === 'FAILED' ? 'var(--red)' : 'var(--tx-2)' }"
              :title="job.errorMsg || ''"
            >
              {{ job.errorMsg || '—' }}
            </td>
            <td>
              <div class="ops">
                <button
                  v-if="job.status === 'FAILED'"
                  class="btn sm danger"
                  :disabled="ingest.retryingIds.includes(job.id)"
                  @click="retry(job)"
                >
                  {{ ingest.retryingIds.includes(job.id) ? '重试中…' : '重试' }}
                </button>
                <button class="btn sm txt" @click="openTableDetail(job.tableName)">详情</button>
              </div>
            </td>
          </tr>
          <tr v-if="!ingest.jobs.length">
            <td colspan="10">
              <div class="empty" style="padding: 26px">当前筛选条件下没有作业记录</div>
            </td>
          </tr>
        </tbody>
      </table>
    </div>

    <div class="pgn">
      <span>共 <b class="mono">{{ formatInt(ingest.jobsTotal) }}</b> 条</span>
      <button :disabled="ingest.jobsPage <= 1 || ingest.jobsLoading" @click="ingest.setJobPage(ingest.jobsPage - 1)">‹</button>
      <button
        v-for="page in pageNumbers"
        :key="page"
        :class="{ cur: page === ingest.jobsPage }"
        @click="ingest.setJobPage(page)"
      >
        {{ page }}
      </button>
      <button :disabled="ingest.jobsPage >= ingest.totalJobPages || ingest.jobsLoading" @click="ingest.setJobPage(ingest.jobsPage + 1)">›</button>
    </div>
  </div>
</template>
