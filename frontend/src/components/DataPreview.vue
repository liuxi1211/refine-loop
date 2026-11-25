<script setup lang="ts">
import { computed, onMounted, ref, watch } from 'vue'
import { asApiError, describeError, type ApiError } from '../api/http'
import { fetchPreview } from '../api/tables'
import type { PreviewResult } from '../api/types'
import { useUiStore } from '../stores/ui'
import { formatInt, formatNumber } from '../utils/format'
import StateBlock from './StateBlock.vue'

const props = defineProps<{ table: string }>()

const ui = useUiStore()

const columnMode = ref<'key' | 'all'>('key')
const code = ref('')
const start = ref('')
const end = ref('')
const page = ref(1)
const size = 20
const order = ref<'desc' | 'asc'>('desc')

const data = ref<PreviewResult | null>(null)
const loading = ref(false)
const error = ref<ApiError | null>(null)

const columns = computed(() => data.value?.columns ?? [])
const rows = computed(() => data.value?.rows ?? [])
const totalPages = computed(() => Math.max(1, Math.ceil((data.value?.total ?? 0) / size)))
const pageLimitReached = computed(() => page.value * size >= 10000)

function validDate(value: string): boolean {
  return !value || /^\d{8}$/.test(value)
}

async function load() {
  if (loading.value) return
  if (!validDate(start.value) || !validDate(end.value)) {
    ui.pushToast('err', '日期格式应为 yyyyMMdd（如 20261101）')
    return
  }
  loading.value = true
  error.value = null
  try {
    data.value = await fetchPreview(props.table, {
      code: code.value.trim(),
      start: start.value.trim(),
      end: end.value.trim(),
      columnMode: columnMode.value,
      page: page.value,
      size,
      order: order.value
    })
    error.value = null
  } catch (err) {
    error.value = asApiError(err)
  } finally {
    loading.value = false
  }
}

function search() {
  page.value = 1
  void load()
}

function setMode(mode: 'key' | 'all') {
  if (columnMode.value === mode) return
  columnMode.value = mode
  page.value = 1
  void load()
}

function prevPage() {
  if (page.value > 1) {
    page.value -= 1
    void load()
  }
}

function nextPage() {
  if (page.value >= totalPages.value || pageLimitReached.value) return
  page.value += 1
  void load()
}

function cellText(value: unknown): string {
  if (value === null || value === undefined) return '—'
  if (typeof value === 'number') return formatNumber(value)
  if (typeof value === 'boolean') return value ? 'true' : 'false'
  const text = String(value)
  return text === '' ? '—' : text
}

function exportCsv() {
  if (!columns.value.length || !rows.value.length) {
    ui.pushToast('info', '当前页没有可导出的数据')
    return
  }
  const escape = (value: string) => `"${value.replace(/"/g, '""')}"`
  const lines = [columns.value.map(escape).join(',')]
  for (const row of rows.value) {
    lines.push(columns.value.map((col) => escape(cellText(row[col]))).join(','))
  }
  const blob = new Blob([`\uFEFF${lines.join('\r\n')}`], { type: 'text/csv;charset=utf-8' })
  const url = URL.createObjectURL(blob)
  const anchor = document.createElement('a')
  anchor.href = url
  anchor.download = `${props.table}_page${page.value}.csv`
  document.body.appendChild(anchor)
  anchor.click()
  document.body.removeChild(anchor)
  URL.revokeObjectURL(url)
  ui.pushToast('ok', `已导出当前页 ${rows.value.length} 行 CSV`)
}

onMounted(load)
watch(
  () => props.table,
  () => {
    page.value = 1
    void load()
  }
)
</script>

<template>
  <div class="card mb14">
    <div class="card-h">
      <h3>数据预览</h3>
      <div class="right">
        <div class="seg" style="width: 150px">
          <button :class="{ on: columnMode === 'key' }" @click="setMode('key')">关键列</button>
          <button :class="{ on: columnMode === 'all' }" @click="setMode('all')">全部列</button>
        </div>
      </div>
    </div>
    <div class="card-b" style="padding-bottom: 8px; display: flex; gap: 8px; flex-wrap: wrap; align-items: center">
      <input v-model="code" class="inp" style="width: 170px" placeholder="代码，如 000001.SZ" />
      <input v-model="start" class="inp" style="width: 120px" placeholder="开始日期 yyyyMMdd" maxlength="8" />
      <span style="color: var(--tx-3); font-size: 12px">至</span>
      <input v-model="end" class="inp" style="width: 120px" placeholder="结束日期 yyyyMMdd" maxlength="8" />
      <select v-model="order" class="sel" style="width: 110px">
        <option value="desc">日期倒序</option>
        <option value="asc">日期正序</option>
      </select>
      <button class="btn sm pri" :disabled="loading" @click="search">查询</button>
      <button class="btn sm" @click="exportCsv">导出当前页 CSV</button>
    </div>
    <div class="scroll-y" style="max-height: 300px">
      <StateBlock v-if="error" kind="error" title="数据预览加载失败" :description="describeError(error)" action-label="重试" @action="load" />
      <StateBlock v-else-if="loading && !data" kind="loading" title="正在查询预览数据…" />
      <table v-else-if="columns.length" class="tbl">
        <thead>
          <tr>
            <th v-for="col in columns" :key="col" class="num">{{ col }}</th>
          </tr>
        </thead>
        <tbody>
          <tr v-for="(row, rowIndex) in rows" :key="rowIndex">
            <td v-for="col in columns" :key="col" class="num" :title="cellText(row[col])">{{ cellText(row[col]) }}</td>
          </tr>
          <tr v-if="!rows.length">
            <td :colspan="columns.length">
              <div class="empty" style="padding: 22px">当前条件下无数据</div>
            </td>
          </tr>
        </tbody>
      </table>
      <div v-else class="empty" style="padding: 22px">暂无预览数据</div>
    </div>
    <div class="pgn">
      <span>共 <b class="mono">{{ formatInt(data?.total) }}</b> 行</span>
      <span v-if="pageLimitReached" style="color: var(--amber)">分页深度上限 10,000 行</span>
      <button :disabled="page <= 1 || loading" @click="prevPage">‹</button>
      <button class="cur">{{ page }} / {{ totalPages }}</button>
      <button :disabled="page >= totalPages || pageLimitReached || loading" @click="nextPage">›</button>
    </div>
  </div>
</template>
