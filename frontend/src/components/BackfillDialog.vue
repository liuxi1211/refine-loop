<script setup lang="ts">
import { ref, watch } from 'vue'
import { useTablesStore } from '../stores/tables'
import { useUiStore } from '../stores/ui'
import { todayStamp } from '../utils/format'
import BaseModal from './BaseModal.vue'

const props = defineProps<{
  modelValue: boolean
  table: string
}>()

const emit = defineEmits<{ (e: 'update:modelValue', value: boolean): void }>()

const tables = useTablesStore()
const ui = useUiStore()

const mode = ref<'all' | 'range'>('all')
const start = ref('19901219')
const end = ref(todayStamp())
const submitting = ref(false)

watch(
  () => props.modelValue,
  (open) => {
    if (!open) return
    mode.value = 'all'
    start.value = '19901219'
    end.value = todayStamp()
    submitting.value = false
  }
)

function close() {
  emit('update:modelValue', false)
}

async function submit() {
  if (submitting.value) return
  if (mode.value === 'range') {
    if (!/^\d{8}$/.test(start.value.trim()) || !/^\d{8}$/.test(end.value.trim())) {
      ui.pushToast('err', '开始/结束日期应为 yyyyMMdd（如 19901219）')
      return
    }
    if (start.value.trim() > end.value.trim()) {
      ui.pushToast('err', '开始日期不能晚于结束日期')
      return
    }
  }
  submitting.value = true
  const range =
    mode.value === 'range' ? { start: start.value.trim(), end: end.value.trim() } : {}
  const ok = await tables.backfillTable(props.table, range)
  submitting.value = false
  if (ok) close()
}
</script>

<template>
  <BaseModal
    :model-value="modelValue"
    :title="`全量回填 · ${table}`"
    @update:model-value="emit('update:modelValue', $event)"
  >
    <div class="field">
      <label>回填范围</label>
      <div class="seg">
        <button :class="{ on: mode === 'all' }" @click="mode = 'all'">全部历史（19901219 起）</button>
        <button :class="{ on: mode === 'range' }" @click="mode = 'range'">指定日期范围</button>
      </div>
    </div>
    <div class="grid-2" style="gap: 10px">
      <div class="field">
        <label>开始日期</label>
        <input v-model="start" class="inp" placeholder="19901219" maxlength="8" :disabled="mode === 'all'" />
      </div>
      <div class="field">
        <label>结束日期</label>
        <input v-model="end" class="inp" placeholder="yyyyMMdd" maxlength="8" :disabled="mode === 'all'" />
      </div>
    </div>
    <div class="field">
      <label>执行方式</label>
      <div class="alert info" style="padding: 8px 12px">
        后端以异步作业执行（jobType=BACKFILL），逐表进度写入 ingest_log，可在「接入作业」页查看。
      </div>
      <div class="hint">按官方限流估算耗时较长，建议与其他表分晚执行；任务提交后本表每日增量作业在回填完成前自动跳过。</div>
    </div>
    <div class="alert warn">
      ⚠ 回填采用 upsert 幂等写入，重跑不产生重复数据；回填期间该表每日增量作业自动跳过，完成后恢复。未完成回填前，流水线 S0 数据门会正常拦截该表。
    </div>
    <template #footer>
      <button class="btn" @click="close">取消</button>
      <button class="btn pri" :disabled="submitting" @click="submit">
        <span v-if="submitting" class="spinner on-dark" style="margin-right: 4px"></span>确认回填
      </button>
    </template>
  </BaseModal>
</template>
