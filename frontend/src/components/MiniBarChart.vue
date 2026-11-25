<script setup lang="ts">
import { computed } from 'vue'
import type { DailyLoadPoint } from '../api/types'
import { formatInt, formatShortDay } from '../utils/format'

const props = defineProps<{
  points: DailyLoadPoint[]
  loading?: boolean
}>()

interface BarView {
  date: string
  rows: number
  height: number
}

const bars = computed<BarView[]>(() => {
  const list = Array.isArray(props.points) ? props.points.filter((p) => !!p && !!p.date) : []
  if (!list.length) return []
  const values = list.map((p) => Number(p.rows) || 0)
  const maxValue = Math.max(...values, 1)
  const minValue = Math.min(...values, maxValue)
  const floor = minValue === maxValue ? Math.max(0, maxValue * 0.9) : minValue - (maxValue - minValue) * 0.15
  const span = Math.max(1, maxValue - floor)
  return list.map((p) => ({
    date: p.date,
    rows: Number(p.rows) || 0,
    height: 8 + ((Number(p.rows) || 0) - floor) / span * 92
  }))
})
</script>

<template>
  <div v-if="loading" class="empty" style="padding: 22px">正在加载近 10 日落库行数…</div>
  <div v-else-if="!bars.length" class="empty" style="padding: 22px">暂无近 10 日落库数据</div>
  <div v-else class="bars">
    <div
      v-for="bar in bars"
      :key="bar.date"
      class="b"
      :title="`${bar.date}：${formatInt(bar.rows)} 行`"
    >
      <i :style="{ height: bar.height + '%' }"></i>
      <span class="lab">{{ formatShortDay(bar.date) }}</span>
    </div>
  </div>
</template>
