<script setup lang="ts">
import { computed, onBeforeUnmount, onMounted, ref } from 'vue'
import { describeError } from '../api/http'
import type { QualityCheckItem, QualityReport } from '../api/types'
import { useQualityStore } from '../stores/quality'
import {
  formatDuration,
  formatInt,
  formatTimestamp,
  formatTriggerType
} from '../utils/format'
import { checkItemView, qualityResultChip } from '../utils/status'
import StateBlock from './StateBlock.vue'
import StatusChip from './StatusChip.vue'

const quality = useQualityStore()

const revealed = ref(0)
let revealTimer: number | null = null

const latest = computed<QualityReport | null>(() => quality.latest)
const items = computed<QualityCheckItem[]>(() => latest.value?.items ?? [])
const visibleItems = computed(() => items.value.slice(0, revealed.value))
const latestChip = computed(() => qualityResultChip(latest.value?.result))

const runLabel = computed(() => (quality.running ? 'S0 检查执行中…' : '✓ 立即执行 S0 检查'))

function stopReveal() {
  if (revealTimer !== null) {
    window.clearInterval(revealTimer)
    revealTimer = null
  }
}

function revealAll() {
  revealed.value = items.value.length
}

function startReveal() {
  stopReveal()
  revealed.value = 0
  if (!items.value.length) return
  revealTimer = window.setInterval(() => {
    if (revealed.value >= items.value.length) {
      stopReveal()
      return
    }
    revealed.value += 1
  }, 260)
}

async function run() {
  stopReveal()
  const report = await quality.runCheck()
  if (!report) {
    revealAll()
    return
  }
  startReveal()
}

onMounted(async () => {
  await Promise.all([
    quality.fetchLatest(),
    quality.fetchHistory(1)
  ])
  revealAll()
})

onBeforeUnmount(stopReveal)
</script>

<template>
  <div>
    <div style="display: flex; align-items: center; margin-bottom: 14px; gap: 12px; flex-wrap: wrap">
      <div>
        <div style="font-weight: 700; font-size: 15px">
          数据质量门
          <StatusChip :tone="latestChip.tone" :label="latestChip.label" style="vertical-align: 2px; margin-left: 4px" />
        </div>
        <div style="font-size: 12.5px; color: var(--tx-2); margin-top: 2px">
          DataQualityGate · 流水线 S0 双层判定：① 当日关键表作业状态 ② 行数一致性与字段 sanity · 不达标将挂起整线并告警
        </div>
      </div>
      <div style="margin-left: auto">
        <button class="btn pri" :disabled="quality.running" @click="run">
          <span v-if="quality.running" class="spinner on-dark" style="margin-right: 5px"></span>{{ runLabel }}
        </button>
      </div>
    </div>

    <!-- 最近一次检查结果 -->
    <div class="card mb14">
      <div class="card-h">
        <h3>最近一次检查结果</h3>
        <span class="sub mono">
          {{ latest ? `${formatTimestamp(latest.checkTime, { withSeconds: true })} · 触发方式：${formatTriggerType(latest.triggerType)}` : '暂无检查记录' }}
        </span>
        <div class="right">
          <StatusChip
            v-if="latest"
            :tone="latestChip.tone"
            :label="`${latestChip.label} · 耗时 ${formatDuration(latest.durationMs)}`"
          />
        </div>
      </div>

      <StateBlock
        v-if="quality.latestError && !latest"
        kind="error"
        compact
        title="最近一次 S0 检查加载失败"
        :description="describeError(quality.latestError)"
        action-label="重试"
        @action="quality.fetchLatest()"
      />
      <StateBlock v-else-if="quality.latestLoading && !latest" kind="loading" compact title="正在加载最近一次 S0 检查…" />
      <div v-else-if="!latest" class="empty" style="padding: 30px">
        <div class="big">✓</div>
        暂无 S0 检查记录<br />
        <span class="meta">点击右上角「立即执行 S0 检查」，逐项渲染 PASS / WARN / FAIL / SKIP</span>
      </div>
      <div v-else>
        <div v-for="(item, index) in visibleItems" :key="`${item.code || item.name || index}`" class="q-item">
          <div class="q-ico" :class="checkItemView(item.status).tone">{{ checkItemView(item.status).icon }}</div>
          <div style="min-width: 0">
            <div class="t">{{ item.name || item.code || '检查项' }}</div>
            <div class="s">
              <span>{{ item.message || '—' }}</span>
              <span
                v-if="item.expected !== null && item.expected !== undefined || item.actual !== null && item.actual !== undefined"
                class="mono"
                style="margin-left: 6px; color: var(--tx-2)"
              >
                [期望 {{ item.expected || '—' }} · 实际 {{ item.actual || '—' }}]
              </span>
            </div>
          </div>
          <span style="margin-left: auto"><StatusChip :tone="checkItemView(item.status).tone === 'pass' ? 'ok' : checkItemView(item.status).tone === 'fail' ? 'err' : checkItemView(item.status).tone === 'warn' ? 'warn' : 'idle'" :label="item.status" /></span>
        </div>
        <div v-if="visibleItems.length < items.length" class="q-item">
          <div class="q-ico wait">…</div>
          <div><div class="t">检查项渲染中…</div></div>
        </div>
        <div v-if="!items.length" class="empty" style="padding: 26px">本次检查未返回逐项结果</div>
      </div>
    </div>

    <div class="grid-2">
      <!-- 检查历史 -->
      <div class="card rv d2">
        <div class="card-h">
          <h3>检查历史</h3>
          <span class="sub">保留近 30 天</span>
          <div class="right">
            <button class="btn sm" :disabled="quality.historyLoading" @click="quality.fetchHistory(quality.historyPage)">刷新</button>
          </div>
        </div>
        <StateBlock
          v-if="quality.historyError && !quality.history.length"
          kind="error"
          compact
          title="检查历史加载失败"
          :description="describeError(quality.historyError)"
          action-label="重试"
          @action="quality.fetchHistory(1)"
        />
        <StateBlock v-else-if="quality.historyLoading && !quality.history.length" kind="loading" compact title="正在加载检查历史…" />
        <div v-else class="scroll-y" style="max-height: 300px">
          <table class="tbl">
            <thead>
              <tr>
                <th>时间</th>
                <th>触发</th>
                <th>结果</th>
                <th>不达标项</th>
              </tr>
            </thead>
            <tbody>
              <tr v-for="row in quality.history" :key="row.checkId">
                <td class="num dim">{{ formatTimestamp(row.checkTime) }}</td>
                <td class="dim">{{ formatTriggerType(row.triggerType) }}</td>
                <td>
                  <StatusChip :tone="qualityResultChip(row.result).tone" :label="qualityResultChip(row.result).label" />
                </td>
                <td :style="{ color: (row.failCount ?? 0) > 0 ? 'var(--red)' : (row.warnCount ?? 0) > 0 ? 'var(--amber)' : 'var(--tx-2)' }">
                  <template v-if="(row.failCount ?? 0) > 0 || (row.warnCount ?? 0) > 0">
                    {{ row.failCount ?? 0 }} FAIL · {{ row.warnCount ?? 0 }} WARN · {{ row.passCount ?? 0 }} PASS
                  </template>
                  <template v-else>—</template>
                </td>
              </tr>
              <tr v-if="!quality.history.length">
                <td colspan="4"><div class="empty" style="padding: 22px">暂无检查历史</div></td>
              </tr>
            </tbody>
          </table>
        </div>
        <div class="pgn">
          <span>共 <b class="mono">{{ formatInt(quality.historyTotal) }}</b> 次</span>
          <button :disabled="quality.historyPage <= 1 || quality.historyLoading" @click="quality.setHistoryPage(quality.historyPage - 1)">‹</button>
          <button class="cur">{{ quality.historyPage }} / {{ quality.totalHistoryPages }}</button>
          <button :disabled="quality.historyPage >= quality.totalHistoryPages || quality.historyLoading" @click="quality.setHistoryPage(quality.historyPage + 1)">›</button>
        </div>
      </div>

      <!-- 不达标时的行为 -->
      <div class="card rv d3">
        <div class="card-h">
          <h3>不达标时的行为</h3>
          <span class="sub">与流水线的联动语义</span>
        </div>
        <div class="card-b" style="font-size: 12.5px; color: var(--tx-2); line-height: 2">
          <div><span class="chip err"><span class="d"></span>不达标</span> → 整线 <b class="mono" style="color: var(--tx)">RETRY_WAIT</b>，30 分钟后自动重检</div>
          <div><span class="chip warn"><span class="d"></span>重试至 22:00</span> → 仍不达标则挂起并推送告警（Server酱）</div>
          <div><span class="chip ok"><span class="d"></span>非交易日</span> → 空结果按「跳过」处理，不误报为失败</div>
          <div style="padding-top: 8px; border-top: 1px dashed var(--line); margin-top: 6px">
            检查项与阈值可在 <b style="color: var(--tx)">quality.yml</b> 调整；此处修改留痕 sys_config
          </div>
        </div>
      </div>
    </div>
  </div>
</template>
