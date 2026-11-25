<script setup lang="ts">
import { computed, onBeforeUnmount, ref } from 'vue'
import type { LayerProgress, TableJobProgress } from '../api/types'
import { describeError } from '../api/http'
import { useIngestStore } from '../stores/ingest'
import { elapsedSince, formatDuration, formatInt, formatTimestamp, formatTriggerType } from '../utils/format'
import { orchestrationChip } from '../utils/status'
import StateBlock from './StateBlock.vue'
import StatusChip from './StatusChip.vue'

const ingest = useIngestStore()

/** 运行中计时（轮询 5s，本地每秒刷新耗时展示） */
const now = ref(Date.now())
const clock = window.setInterval(() => {
  now.value = Date.now()
}, 1000)
onBeforeUnmount(() => window.clearInterval(clock))

const progress = computed(() => ingest.orchestration)
const running = computed(() => !!progress.value?.running)
const statusChip = computed(() => orchestrationChip(progress.value?.status))
const layers = computed<LayerProgress[]>(() => (progress.value?.layers ?? []).filter((layer) => !!layer))
const allTables = computed<TableJobProgress[]>(() => layers.value.flatMap((layer) => layer.tables ?? []))

const percent = computed(() => {
  const value = progress.value?.percent
  if (typeof value === 'number' && Number.isFinite(value)) return Math.min(100, Math.max(0, Math.round(value)))
  const total = progress.value?.total ?? 0
  const done = progress.value?.done ?? 0
  return total > 0 ? Math.round((done / total) * 100) : 0
})

const progressClass = computed(() => {
  if (running.value) return ''
  if ((progress.value?.failed ?? 0) > 0) return 'danger'
  return 'ok'
})

const elapsed = computed(() => {
  const p = progress.value
  if (!p) return '—'
  if (p.running) return elapsedSince(p.startedAt, now.value)
  if (p.startedAt && p.finishedAt) {
    const start = new Date(p.startedAt).getTime()
    const end = new Date(p.finishedAt).getTime()
    if (Number.isFinite(start) && Number.isFinite(end)) return formatDuration(Math.max(0, end - start))
  }
  return '—'
})

const hasContent = computed(() => !!progress.value && (layers.value.length > 0 || !!progress.value.runId))

const LAYER_LABELS: Array<[string, string]> = [
  ['DIMENSION', '① 维表层'],
  ['QUOTE', '② 行情层'],
  ['FINANCE', '③ 财务与事件层'],
  ['EVENT', '③ 财务与事件层'],
  ['INDEX', '③ 财务与事件层'],
  ['INDEX_MARKET', '③ 财务与事件层'],
  ['FINANCE_EVENT', '③ 财务·事件层'],
  ['L1', '① 维表层'],
  ['L2', '② 行情层'],
  ['L3', '③ 财务·事件层']
]

function layerLabel(layer: LayerProgress, index: number): string {
  const raw = layer.layer ?? ''
  const hit = LAYER_LABELS.find(([key]) => key === raw.toUpperCase())
  return hit ? hit[1] : raw || `第 ${index + 1} 层`
}

interface ChipView {
  cls: string
  icon: string
}

function chipView(status?: string | null): ChipView {
  switch ((status ?? '').toUpperCase()) {
    case 'SUCCESS':
      return { cls: 'done', icon: '✓' }
    case 'RUNNING':
      return { cls: 'run', icon: '⟳' }
    case 'FAILED':
      return { cls: 'fail', icon: '✕' }
    case 'SKIPPED':
      return { cls: 'wait', icon: '·' }
    case 'PENDING':
      return { cls: 'wait', icon: '' }
    default:
      return { cls: 'wait', icon: '' }
  }
}

function chipTitle(table: TableJobProgress): string {
  const parts: string[] = [`状态：${table.status || '—'}`]
  if (table.rowsWritten !== null && table.rowsWritten !== undefined) parts.push(`落库：${formatInt(table.rowsWritten)} 行`)
  if (table.durationMs !== null && table.durationMs !== undefined) parts.push(`耗时：${formatDuration(table.durationMs)}`)
  if (table.errorMsg) parts.push(`错误：${table.errorMsg}`)
  return parts.join(' · ')
}

function layerMeta(layer: LayerProgress): { text: string; cls: string } {
  const total = layer.total ?? layer.tables?.length ?? 0
  const done = layer.done ?? 0
  const failed = layer.failed ?? 0
  const runningCount = layer.running ?? 0
  if (runningCount > 0) return { text: `${done}/${total} · 运行中`, cls: 'txt-dim' }
  if (failed > 0) return { text: `${done}/${total} · ${failed} 失败`, cls: 'txt-err' }
  if ((layer.pending ?? 0) > 0) return { text: `${done}/${total} · 等待前置层`, cls: 'txt-dim' }
  const duration = (layer.tables ?? []).reduce((sum, item) => sum + (Number(item.durationMs) || 0), 0)
  const suffix = duration > 0 ? ` · ${formatDuration(duration)}` : ''
  return { text: `${done}/${total}${suffix}`, cls: 'txt-ok' }
}

function refresh() {
  void ingest.fetchOrchestration()
}
</script>

<template>
  <div class="card mb14 rv d1">
    <div class="card-h">
      <h3>{{ running ? '运行中编排' : hasContent ? '最近一次编排' : '运行中编排' }}</h3>
      <span v-if="progress?.runId" class="sub mono">{{ progress.runId }}</span>
      <div class="right">
        <span v-if="progress?.triggerType" class="meta">触发：{{ formatTriggerType(progress.triggerType) }}</span>
        <StatusChip :tone="statusChip.tone" :label="statusChip.label" />
        <button class="btn sm" :disabled="ingest.orchestrationLoading" @click="refresh">刷新</button>
      </div>
    </div>

    <StateBlock
      v-if="ingest.orchestrationError && !hasContent"
      kind="error"
      compact
      title="运行中编排加载失败"
      :description="describeError(ingest.orchestrationError)"
      action-label="重试"
      @action="refresh"
    />
    <StateBlock v-else-if="ingest.orchestrationLoading && !hasContent" kind="loading" compact title="正在加载编排进度…" />

    <div v-else-if="!hasContent" class="empty" style="padding: 26px">
      <div class="big">⟳</div>
      当前无运行中的编排<br />
      <span class="meta">点击右上角「一键每日增量」触发分层调度（维表层 → 行情层 → 财务与事件层）</span>
    </div>

    <div v-else class="card-b">
      <div v-for="(layer, index) in layers" :key="layer.layer || index" class="stage-lane">
        <span class="lb">{{ layerLabel(layer, index) }}</span>
        <div class="stage-chips">
          <span
            v-for="table in layer.tables || []"
            :key="table.table"
            class="s-chip"
            :class="chipView(table.status).cls"
            :title="chipTitle(table)"
          >
            {{ table.table }}{{ chipView(table.status).icon ? ` ${chipView(table.status).icon}` : '' }}
          </span>
          <span v-if="!(layer.tables || []).length" class="meta">本层无表</span>
        </div>
        <span style="margin-left: auto; font-size: 11.5px" :class="layerMeta(layer).cls">{{ layerMeta(layer).text }}</span>
      </div>

      <div
        style="
          margin-top: 8px;
          padding-top: 10px;
          border-top: 1px dashed var(--line);
          display: flex;
          align-items: center;
          gap: 12px;
        "
      >
        <span style="font-size: 12px; color: var(--tx-2)">整体进度</span>
        <div class="pgb" style="flex: 1"><i :class="progressClass" :style="{ width: percent + '%' }"></i></div>
        <span class="mono" style="font-size: 12px">
          {{ progress?.done ?? 0 }}/{{ progress?.total ?? 0 }} 表 · {{ percent }}%
          <template v-if="(progress?.failed ?? 0) > 0"> · <span class="txt-err">{{ progress?.failed }} 失败</span></template>
          · {{ elapsed }}
        </span>
      </div>

      <details v-if="allTables.length" class="orc-detail">
        <summary>表级明细（每表耗时 / 行数）</summary>
        <div class="scroll-y" style="max-height: 260px; margin-top: 4px">
          <table class="tbl">
            <thead>
              <tr>
                <th>表</th>
                <th>状态</th>
                <th style="text-align: right">落库行数</th>
                <th style="text-align: right">耗时</th>
                <th>开始时间</th>
                <th>错误摘要</th>
              </tr>
            </thead>
            <tbody>
              <tr v-for="table in allTables" :key="table.table">
                <td><code class="k mono">{{ table.table }}</code></td>
                <td>
                  <span class="s-chip" :class="chipView(table.status).cls">{{ table.status || '—' }}</span>
                </td>
                <td class="num" style="text-align: right">{{ formatInt(table.rowsWritten) }}</td>
                <td class="num dim" style="text-align: right">{{ formatDuration(table.durationMs) }}</td>
                <td class="num dim">{{ formatTimestamp(table.startedAt) }}</td>
                <td class="dim ellipsis" :title="table.errorMsg || ''" style="max-width: 220px">
                  {{ table.errorMsg || '—' }}
                </td>
              </tr>
            </tbody>
          </table>
        </div>
      </details>
    </div>
  </div>
</template>
