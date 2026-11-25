<script setup lang="ts">
import { computed, ref, watch } from 'vue'
import { useRouter } from 'vue-router'
import { asApiError, describeError, type ApiError } from '../api/http'
import { fetchTableJobs } from '../api/tables'
import type { IngestJobView } from '../api/types'
import { useTablesStore } from '../stores/tables'
import {
  formatBusinessDate,
  formatDuration,
  formatInt,
  formatJobType,
  formatLagDays,
  formatRowCount,
  formatTimestamp
} from '../utils/format'
import { healthChip, jobStatusChip } from '../utils/status'
import BackfillDialog from './BackfillDialog.vue'
import DataPreview from './DataPreview.vue'
import MiniBarChart from './MiniBarChart.vue'
import StateBlock from './StateBlock.vue'
import StatusChip from './StatusChip.vue'
import ToggleSwitch from './ToggleSwitch.vue'

const tables = useTablesStore()
const router = useRouter()

const jobs = ref<IngestJobView[]>([])
const jobsLoading = ref(false)
const jobsError = ref<ApiError | null>(null)
const backfillOpen = ref(false)

const open = computed(() => tables.drawerOpen)
const detail = computed(() => tables.detail)
const meta = computed(() => (tables.activeTable ? tables.metaOf(tables.activeTable) : undefined))
const health = computed(() => healthChip(meta.value?.health))
const lag = computed(() => formatLagDays(detail.value?.lagDays ?? meta.value?.lagDays))

const lastBackfillText = computed(() => {
  const last = detail.value?.lastFullBackfill
  if (!last) return '—'
  const time = last.endTime || last.startTime
  return time ? `${formatTimestamp(time)} · ${last.status || '—'}` : '—'
})

async function loadJobs(table: string) {
  jobsLoading.value = true
  jobsError.value = null
  try {
    const data = await fetchTableJobs(table, 1, 10)
    jobs.value = Array.isArray(data.items) ? data.items : []
  } catch (err) {
    jobsError.value = asApiError(err)
  } finally {
    jobsLoading.value = false
  }
}

watch(
  () => tables.activeTable,
  (table) => {
    jobs.value = []
    jobsError.value = null
    if (table) void loadJobs(table)
  },
  { immediate: true }
)

function tlClass(job: IngestJobView): string {
  switch ((job.status ?? '').toUpperCase()) {
    case 'SUCCESS':
      return 'ok'
    case 'FAILED':
      return 'err'
    case 'RUNNING':
      return 'run'
    default:
      return ''
  }
}

function onToggle(enabled: boolean) {
  if (tables.activeTable) void tables.toggleDaily(tables.activeTable, enabled)
}

function onRun() {
  if (tables.activeTable) void tables.runTable(tables.activeTable)
}

function goJobs() {
  tables.closeDrawer()
  void router.push('/jobs')
}
</script>

<template>
  <div class="dmask" :class="{ show: open }" @click="tables.closeDrawer()"></div>
  <aside class="drawer" :class="{ show: open }">
    <div class="drawer-h">
      <div style="min-width: 0">
        <h3 style="font-size: 16px; font-weight: 600">
          <code class="mono" style="font-size: 16px">{{ tables.activeTable || '—' }}</code>
          <span style="font-weight: 400; color: var(--tx-2); font-size: 13px; margin-left: 4px">
            {{ detail?.chineseName || meta?.chineseName || '' }}
          </span>
        </h3>
        <div style="font-size: 12px; color: var(--tx-3); margin-top: 3px">
          主键 <code class="k mono">{{ detail?.pk || '—' }}</code> ·
          <span>{{ detail?.strategyLabel || meta?.strategyLabel || '—' }}</span>
          <span v-if="meta?.lastJobStatus">
            · 上次作业 {{ formatTimestamp(meta.lastJobTime) }}
            <StatusChip :tone="jobStatusChip(meta.lastJobStatus).tone" :label="jobStatusChip(meta.lastJobStatus).label" style="margin-left: 4px" />
          </span>
        </div>
      </div>
      <StatusChip v-if="meta" :tone="health.tone" :label="health.label" style="margin-left: 8px" />
      <span
        style="margin-left: auto; cursor: pointer; color: var(--tx-3); font-size: 18px; padding: 2px 6px; border-radius: 6px"
        @click="tables.closeDrawer()"
        >✕</span
      >
    </div>

    <div class="drawer-b">
      <StateBlock
        v-if="tables.detailError"
        kind="error"
        title="表详情加载失败"
        :description="describeError(tables.detailError)"
        action-label="重新加载"
        @action="tables.reloadDetail()"
      />
      <StateBlock v-else-if="tables.detailLoading || !detail" kind="loading" title="正在加载表详情…" />

      <template v-else>
        <!-- 操作区 -->
        <div style="display: flex; gap: 8px; align-items: center; margin-bottom: 14px; flex-wrap: wrap">
          <ToggleSwitch
            :model-value="detail.dailyEnabled"
            :pending="tables.isToggling(detail.table)"
            @update:model-value="onToggle"
          >
            <span class="tx">{{ detail.dailyEnabled ? '每日增量已启用' : '每日增量已停用' }}</span>
          </ToggleSwitch>
          <span style="flex: 1"></span>
          <button class="btn" :disabled="tables.isRunning(detail.table)" @click="onRun">
            <span v-if="tables.pendingRuns.includes(detail.table)" class="spinner" style="margin-right: 2px"></span>⟳ 增量更新
          </button>
          <button class="btn pri" @click="backfillOpen = true">⤓ 全量回填</button>
        </div>

        <!-- 详情 kv -->
        <div class="kv" style="margin-bottom: 14px">
          <div class="it">
            <div class="k">总行数</div>
            <div class="v">{{ formatRowCount(detail.rowCount, !!detail.rowCountApprox) }}</div>
          </div>
          <div class="it">
            <div class="k">最新业务日期</div>
            <div class="v">{{ formatBusinessDate(detail.latestDate) }}</div>
          </div>
          <div class="it">
            <div class="k">距今</div>
            <div class="v" :class="lag.tone">{{ lag.text }}</div>
          </div>
          <div class="it">
            <div class="k">官方限流（只读）</div>
            <div class="v">{{ detail.rateLimit ? `${detail.rateLimit} 次/分钟` : '—' }}</div>
          </div>
          <div class="it">
            <div class="k">单位口径</div>
            <div class="v" style="font-size: 11.5px">{{ detail.unitNote || '—' }}</div>
          </div>
          <div class="it">
            <div class="k">上次全量回填</div>
            <div class="v" style="font-size: 11.5px">{{ lastBackfillText }}</div>
          </div>
          <div class="it">
            <div class="k">更新策略</div>
            <div class="v" style="font-size: 11.5px">{{ detail.strategyLabel || detail.strategy || '—' }}</div>
          </div>
          <div class="it">
            <div class="k">字段数</div>
            <div class="v">{{ detail.columns?.length ?? '—' }}</div>
          </div>
          <div class="it">
            <div class="k">分组</div>
            <div class="v" style="font-size: 11.5px">{{ detail.group || '—' }}</div>
          </div>
        </div>

        <!-- 数据预览 -->
        <DataPreview :key="detail.table" :table="detail.table" />

        <div class="grid-2">
          <div class="card">
            <div class="card-h">
              <h3>近 10 日落库行数</h3>
              <span class="sub">按业务日期分组</span>
            </div>
            <div class="card-b">
              <MiniBarChart :points="detail.recentDailyLoad || []" />
              <div
                class="meta"
                style="margin-top: 10px; padding-top: 10px; border-top: 1px dashed var(--line); color: var(--tx-2)"
              >
                <span class="dot ok" style="margin-right: 5px"></span>仅展示最近 10 个自然日的实际落库行数，用于观察截断/漏拉迹象
              </div>
            </div>
          </div>

          <div class="card">
            <div class="card-h">
              <h3>该表作业历史</h3>
              <div class="right"><button class="btn sm txt" @click="goJobs">全部 →</button></div>
            </div>
            <div style="padding: 14px 18px 10px">
              <StateBlock v-if="jobsError" kind="error" compact title="作业时间线加载失败" :description="describeError(jobsError)" action-label="重试" @action="loadJobs(detail.table)" />
              <StateBlock v-else-if="jobsLoading && !jobs.length" kind="loading" compact title="正在加载作业时间线…" />
              <div v-else-if="!jobs.length" class="empty" style="padding: 18px">该表暂无作业记录</div>
              <div v-else class="tl">
                <div v-for="job in jobs" :key="job.id" class="tl-item" :class="tlClass(job)">
                  <span class="t">{{ formatTimestamp(job.startTime || job.createdAt) }}</span>
                  {{ formatJobType(job.jobType) }}
                  <span v-if="job.rangeParams" class="mono" style="color: var(--tx-2)"> {{ job.rangeParams }}</span>
                  ·
                  <b class="mono">{{ formatInt(job.rowsWritten) }} 行</b>
                  · {{ formatDuration(job.durationMs) }}
                  <span v-if="job.status === 'FAILED'" class="txt-err" :title="job.errorMsg || ''">
                    · 失败{{ job.errorMsg ? `：${job.errorMsg}` : '' }}
                  </span>
                  <span v-else-if="job.status === 'RUNNING'" class="txt-dim">· 运行中</span>
                </div>
              </div>
            </div>
          </div>
        </div>
      </template>
    </div>
  </aside>

  <BackfillDialog v-model="backfillOpen" :table="tables.activeTable || ''" />
</template>
