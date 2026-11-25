<script setup lang="ts">
/**
 * 字典标签：把 code 渲染成带语义色的 chip（复用全局 .chip 样式与 tokens）。
 *
 * 用法：
 *   <DictTag type="jobStatus" :code="job.status" />
 *   <DictTag type="jobStatus" :code="job.status" :show-dot="false" />
 *
 * 容错：字典未加载或 code 未登记时，直接显示 code 本身（不显示空白），
 * 这样「后端加了新状态、前端字典还没刷新」时页面仍然可读。
 */
import { computed } from 'vue'
import { useDict } from '../composables/useDict'

const TONES = ['ok', 'warn', 'err', 'idle', 'run', 'off'] as const
type Tone = (typeof TONES)[number]

const props = withDefaults(
  defineProps<{
    type: string
    code?: string | null
    /** 空值占位 */
    fallback?: string
    showDot?: boolean
  }>(),
  {
    code: null,
    fallback: '—',
    showDot: true
  }
)

const { labelOf, itemOf } = useDict(props.type)

const label = computed(() => labelOf(props.code) || props.fallback)

const tone = computed<Tone>(() => {
  const color = itemOf(props.code)?.color
  return color && (TONES as readonly string[]).includes(color) ? (color as Tone) : 'idle'
})
</script>

<template>
  <span class="chip" :class="tone">
    <span v-if="showDot" class="d"></span>{{ label }}
  </span>
</template>
