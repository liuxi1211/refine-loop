<script setup lang="ts">
import { computed, ref, watch } from 'vue'
import type { BackfillPlan } from '../api/types'
import { useIngestStore } from '../stores/ingest'
import { useTablesStore, GROUP_ORDER } from '../stores/tables'
import { useUiStore } from '../stores/ui'
import { formatInt } from '../utils/format'
import BaseModal from './BaseModal.vue'

/** 初始回填向导：生成分晚计划，并按顺序逐表发起回填（后端单线程队列保证串行）。 */
const props = defineProps<{ modelValue: boolean }>()
const emit = defineEmits<{ (e: 'update:modelValue', value: boolean): void }>()

const ingest = useIngestStore()
const tables = useTablesStore()
const ui = useUiStore()

const PLAN_STORAGE_KEY = 'refine-loop.backfill-plan'

const mode = ref<'standard' | 'all' | 'custom'>('standard')
const selected = ref<string[]>([])
const plan = ref<BackfillPlan | null>(null)
const executed = ref<string[]>([])
const executingNight = ref<number | null>(null)

const modeLabel = computed(() => {
  if (!plan.value) return ''
  if (plan.value.mode === 'standard') return '标准方案（M1 必需表优先）'
  if (plan.value.mode === 'all') return '全部表'
  if (plan.value.mode === 'custom') return '自选表'
  return plan.value.mode
})

const customOptions = computed(() => {
  const order = [...GROUP_ORDER]
  return [...tables.list]
    .sort((a, b) => {
      const ia = order.indexOf(a.group as (typeof GROUP_ORDER)[number])
      const ib = order.indexOf(b.group as (typeof GROUP_ORDER)[number])
      if (ia !== ib) return ia - ib
      return a.table.localeCompare(b.table)
    })
    .map((item) => ({ table: item.table, chineseName: item.chineseName ?? '' }))
})

const totalCount = computed(() => plan.value?.tables?.length ?? 0)
const doneCount = computed(() => executed.value.length)

function isSelected(table: string): boolean {
  return selected.value.includes(table)
}

function toggleSelected(table: string) {
  selected.value = isSelected(table)
    ? selected.value.filter((item) => item !== table)
    : [...selected.value, table]
}

function selectAll() {
  selected.value = customOptions.value.map((item) => item.table)
}

function clearAll() {
  selected.value = []
}

function isExecuted(table: string): boolean {
  return executed.value.includes(table)
}

function restorePlan() {
  try {
    const raw = window.sessionStorage.getItem(PLAN_STORAGE_KEY)
    if (!raw) return null
    const parsed = JSON.parse(raw) as { plan?: BackfillPlan; executed?: string[] }
    return parsed?.plan ? parsed : null
  } catch {
    return null
  }
}

function persistPlan() {
  try {
    window.sessionStorage.setItem(
      PLAN_STORAGE_KEY,
      JSON.stringify({ plan: plan.value, executed: executed.value })
    )
  } catch {
    /* 隐私模式下不可写：忽略，只是丢失恢复能力 */
  }
}

watch(
  () => props.modelValue,
  (open) => {
    if (!open) return
    if (!tables.loaded) void tables.fetchOverview({ silent: true, background: true })
    const restored = restorePlan()
    if (restored?.plan) {
      plan.value = restored.plan
      executed.value = restored.executed ?? []
      return
    }
    mode.value = 'standard'
    selected.value = []
    plan.value = null
    executed.value = []
  }
)

function close() {
  emit('update:modelValue', false)
}

async function submit() {
  if (ingest.creatingPlan) return
  if (mode.value === 'custom' && !selected.value.length) {
    ui.pushToast('err', '自选表模式请至少选择一张表')
    return
  }
  const result = await ingest.createBackfillPlan(
    mode.value,
    mode.value === 'custom' ? selected.value : []
  )
  if (result) {
    plan.value = result
    executed.value = []
    persistPlan()
  }
}

/** 顺序执行一组表：单表失败不阻断后续表，最后汇总提示（逐表静默，避免刷屏）。 */
async function runTables(list: string[]) {
  const failed: string[] = []
  for (const table of list) {
    if (isExecuted(table)) continue
    const ok = await tables.backfillTable(table, {}, { silent: true })
    if (ok) {
      executed.value = [...executed.value, table]
      persistPlan()
    } else {
      failed.push(table)
    }
  }
  if (failed.length) {
    ui.pushToast('err', `${failed.length} 张表提交失败：${failed.join('、')}，可稍后在数据总览页单表重试`)
  } else {
    ui.pushToast('ok', '本批回填已全部入队，可在「接入与回填」页查看作业进度')
  }
}

async function runNight(night: number, list: string[]) {
  if (executingNight.value !== null) return
  executingNight.value = night
  try {
    await runTables(list)
  } finally {
    executingNight.value = null
  }
}

async function runAllPending() {
  if (!plan.value?.batches?.length || executingNight.value !== null) return
  executingNight.value = 0
  try {
    for (const batch of plan.value.batches) {
      await runTables(batch.tables)
    }
  } finally {
    executingNight.value = null
  }
}

function resetPlan() {
  plan.value = null
  executed.value = []
  window.sessionStorage.removeItem(PLAN_STORAGE_KEY)
}
</script>

<template>
  <BaseModal
    :model-value="modelValue"
    title="初始回填向导"
    subtitle="全量总预算约 3~5 小时，建议分晚执行；向导只发起作业，不入库计划本身"
    width="680px"
    @update:model-value="emit('update:modelValue', $event)"
  >
    <template v-if="!plan">
      <div class="field">
        <label>回填方案</label>
        <div class="seg">
          <button :class="{ on: mode === 'standard' }" @click="mode = 'standard'">标准方案（必需表优先）</button>
          <button :class="{ on: mode === 'all' }" @click="mode = 'all'">全部表</button>
          <button :class="{ on: mode === 'custom' }" @click="mode = 'custom'">自选表</button>
        </div>
        <div class="hint">
          标准方案由后端注册表给出优先表清单（维表 → 行情 → 财务/事件）；表清单与顺序以创建后返回的批次为准。
        </div>
      </div>

      <div v-if="mode === 'all'" class="alert info">
        全部表按注册表顺序分晚执行，每批最多 8 张表。首次使用建议先跑标准方案，确认 S0 质量门通过后再补其余表。
      </div>

      <div v-if="mode === 'custom'" class="field">
        <label>自选表（已选 {{ selected.length }} 张）</label>
        <div class="row-actions">
          <button class="btn sm" @click="selectAll">全选</button>
          <button class="btn sm" @click="clearAll">清空</button>
          <span class="meta">共 {{ customOptions.length }} 张可选（来自 GET /ops/tables）</span>
        </div>
        <div v-if="!customOptions.length" class="empty" style="padding: 18px">
          表清单尚未加载（后端未启动或接口不可用），请先打开「数据总览」页加载表清单后重试。
        </div>
        <div v-else class="chk-list">
          <label v-for="item in customOptions" :key="item.table" class="chk">
            <input type="checkbox" :checked="isSelected(item.table)" @change="toggleSelected(item.table)" />
            <span class="nm">{{ item.table }}</span>
            <span class="meta ellipsis">{{ item.chineseName }}</span>
          </label>
        </div>
      </div>

      <div class="alert info">
        回填采用 upsert 幂等写入，重跑不产生重复数据；回填期间该表的每日增量作业会被 S0 质量门按数据完整性判定，不会误报通过。
      </div>
    </template>

    <template v-else>
      <div class="alert info" style="margin-bottom: 14px">回填计划已生成，可逐晚执行</div>
      <div class="kv" style="margin-bottom: 14px">
        <div class="it">
          <div class="k">计划编号</div>
          <div class="v mono">{{ plan.planId }}</div>
        </div>
        <div class="it">
          <div class="k">回填方案</div>
          <div class="v" style="font-size: 11.5px">{{ modeLabel }}</div>
        </div>
        <div class="it">
          <div class="k">进度</div>
          <div class="v">{{ formatInt(doneCount) }} / {{ formatInt(totalCount) }} 表已入队</div>
        </div>
      </div>

      <div class="field">
        <label>分晚计划</label>
        <div v-if="plan.batches?.length">
          <div v-for="batch in plan.batches" :key="batch.night" class="batch">
            <div class="hd">
              第 {{ batch.night }} 晚 · {{ batch.tables.length }} 表
              <button
                class="btn sm"
                style="margin-left: 8px"
                :disabled="executingNight !== null"
                @click="runNight(batch.night, batch.tables)"
              >
                {{ executingNight === batch.night ? '提交中…' : '执行本晚' }}
              </button>
            </div>
            <div class="tbs">
              <code v-for="table in batch.tables" :key="table" :class="{ done: isExecuted(table) }">
                {{ table }}{{ isExecuted(table) ? ' ✓' : '' }}
              </code>
            </div>
          </div>
        </div>
        <div v-else class="empty" style="padding: 18px">后端未返回分晚批次</div>
      </div>

      <div class="alert warn">{{ plan.message || '计划已生成，未自动执行' }}</div>
    </template>

    <template #footer>
      <template v-if="!plan">
        <button class="btn" @click="close">取消</button>
        <button class="btn pri" :disabled="ingest.creatingPlan" @click="submit">
          <span v-if="ingest.creatingPlan" class="spinner on-dark" style="margin-right: 4px"></span>创建回填计划
        </button>
      </template>
      <template v-else>
        <button class="btn" @click="resetPlan">重新规划</button>
        <button class="btn" :disabled="executingNight !== null" @click="runAllPending">
          按顺序执行全部未完成批次
        </button>
        <button class="btn pri" @click="close">关闭</button>
      </template>
    </template>
  </BaseModal>
</template>

<style scoped>
.row-actions {
  display: flex;
  gap: 8px;
  align-items: center;
  margin-bottom: 7px;
}

.ellipsis {
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.tbs code.done {
  color: var(--ok, #15803d);
  border-color: var(--ok, #15803d);
}
</style>
