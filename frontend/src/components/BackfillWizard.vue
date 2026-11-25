<script setup lang="ts">
import { computed, ref, watch } from 'vue'
import type { BackfillPlan } from '../api/types'
import { useIngestStore } from '../stores/ingest'
import { useTablesStore, GROUP_ORDER } from '../stores/tables'
import { useUiStore } from '../stores/ui'
import { formatInt } from '../utils/format'
import BaseModal from './BaseModal.vue'

const props = defineProps<{ modelValue: boolean }>()
const emit = defineEmits<{ (e: 'update:modelValue', value: boolean): void }>()

const ingest = useIngestStore()
const tables = useTablesStore()
const ui = useUiStore()

const STANDARD_TABLES = [
  'stock_basic',
  'trade_cal',
  'index_basic',
  'daily',
  'adj_factor',
  'daily_basic',
  'fina_indicator',
  'income',
  'balancesheet',
  'cashflow'
]

const mode = ref<'standard' | 'all' | 'custom'>('standard')
const selected = ref<string[]>([])
const plan = ref<BackfillPlan | null>(null)

const modeLabel = computed(() => {
  if (plan.value) {
    if (plan.value.mode === 'standard') return '标准方案（M1 必需 10 表优先）'
    if (plan.value.mode === 'all') return '全部 41 表'
    if (plan.value.mode === 'custom') return '自选表'
    return plan.value.mode
  }
  return ''
})

const customOptions = computed(() => {
  const order = [...GROUP_ORDER]
  const nameOf = (table: string) => tables.metaOf(table)?.chineseName ?? ''
  return [...tables.list]
    .sort((a, b) => {
      const ia = order.indexOf(a.group as (typeof GROUP_ORDER)[number])
      const ib = order.indexOf(b.group as (typeof GROUP_ORDER)[number])
      if (ia !== ib) return ia - ib
      return a.table.localeCompare(b.table)
    })
    .map((item) => ({ table: item.table, chineseName: nameOf(item.table) || item.chineseName || '' }))
})

function isSelected(table: string): boolean {
  return selected.value.includes(table)
}

function toggleSelected(table: string) {
  if (isSelected(table)) {
    selected.value = selected.value.filter((item) => item !== table)
  } else {
    selected.value = [...selected.value, table]
  }
}

function selectAll() {
  selected.value = customOptions.value.map((item) => item.table)
}

function clearAll() {
  selected.value = []
}

watch(
  () => props.modelValue,
  (open) => {
    if (!open) return
    mode.value = 'standard'
    selected.value = []
    plan.value = null
    if (!tables.loaded) void tables.fetchOverview({ silent: true, background: true })
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
  if (result) plan.value = result
}
</script>

<template>
  <BaseModal
    :model-value="modelValue"
    title="初始回填向导"
    subtitle="全量总预算约 3~5 小时，建议分晚执行"
    width="660px"
    @update:model-value="emit('update:modelValue', $event)"
  >
    <!-- 第一步：选择方案 -->
    <template v-if="!plan">
      <div class="field">
        <label>回填方案</label>
        <div class="seg">
          <button :class="{ on: mode === 'standard' }" @click="mode = 'standard'">标准方案（M1 必需 10 表优先）</button>
          <button :class="{ on: mode === 'all' }" @click="mode = 'all'">全部 41 表</button>
          <button :class="{ on: mode === 'custom' }" @click="mode = 'custom'">自选表</button>
        </div>
        <div class="hint">
          标准方案：stock_basic、trade_cal、daily、adj_factor、daily_basic、index_basic、index_daily、fina_indicator、margin、margin_detail；其余表按默认排期随后。
        </div>
      </div>

      <div v-if="mode === 'standard'" class="field">
        <label>标准方案表清单（10 表）</label>
        <div class="batch">
          <div class="tbs">
            <code v-for="table in STANDARD_TABLES" :key="table">{{ table }}</code>
          </div>
        </div>
      </div>

      <div v-if="mode === 'all'" class="field">
        <label>范围</label>
        <div class="alert info">
          全部 41 张 Tushare 数据表按注册表顺序分晚执行（每批最多 8 张表）。
        </div>
      </div>

      <div v-if="mode === 'custom'" class="field">
        <label>自选表（已选 {{ selected.length }} 张）</label>
        <div style="display: flex; gap: 8px; margin-bottom: 7px; align-items: center">
          <button class="btn sm" @click="selectAll">全选</button>
          <button class="btn sm" @click="clearAll">清空</button>
          <span class="meta">共 {{ customOptions.length }} 张可选（表清单来自 GET /tables）</span>
        </div>
        <div v-if="!customOptions.length" class="empty" style="padding: 18px">
          表清单尚未加载（后端未启动或接口不可用），请先在「数据总览」页加载表清单后重试。
        </div>
        <div v-else class="chk-list">
          <label v-for="item in customOptions" :key="item.table" class="chk">
            <input type="checkbox" :checked="isSelected(item.table)" @change="toggleSelected(item.table)" />
            <span class="nm">{{ item.table }}</span>
            <span class="meta" style="overflow: hidden; text-overflow: ellipsis; white-space: nowrap">{{ item.chineseName }}</span>
          </label>
        </div>
      </div>

      <div class="alert info">
        ⓘ 后端只生成分晚计划、不自动执行；计划保存到 sys_config，逐表进度记录于 ingest_log，可在「接入作业」页实时查看。回填完成 index_daily 后需进行单位实测核对并回写手册第 7 章（M1 验收项）。
      </div>
    </template>

    <!-- 第二步：展示返回的分晚计划 -->
    <template v-else>
      <div class="alert info" style="margin-bottom: 14px">回填计划已生成（未自动执行）</div>
      <div class="kv" style="margin-bottom: 14px">
        <div class="it">
          <div class="k">计划编号</div>
          <div class="v">{{ plan.planId }}</div>
        </div>
        <div class="it">
          <div class="k">回填方案</div>
          <div class="v" style="font-size: 11.5px">{{ modeLabel }}</div>
        </div>
        <div class="it">
          <div class="k">表数量 / 晚数</div>
          <div class="v">{{ formatInt(plan.tables?.length) }} 表 / {{ formatInt(plan.batches?.length) }} 晚</div>
        </div>
      </div>
      <div class="field">
        <label>分晚计划</label>
        <div v-if="plan.batches?.length">
          <div v-for="batch in plan.batches" :key="batch.night" class="batch">
            <div class="hd">第 {{ batch.night }} 晚 · {{ batch.tables.length }} 表</div>
            <div class="tbs">
              <code v-for="table in batch.tables" :key="table">{{ table }}</code>
            </div>
          </div>
        </div>
        <div v-else class="empty" style="padding: 18px">后端未返回分晚批次</div>
      </div>
      <div class="alert warn">{{ plan.message || '回填计划已生成（未自动执行）；可用单表回填或 CLI 逐晚执行' }}</div>
    </template>

    <template #footer>
      <template v-if="!plan">
        <button class="btn" @click="close">取消</button>
        <button class="btn pri" :disabled="ingest.creatingPlan" @click="submit">
          <span v-if="ingest.creatingPlan" class="spinner on-dark" style="margin-right: 4px"></span>创建回填计划
        </button>
      </template>
      <button v-else class="btn pri" @click="close">关闭</button>
    </template>
  </BaseModal>
</template>
