<script setup lang="ts">
import { computed } from 'vue'

const props = withDefaults(
  defineProps<{
    kind?: 'loading' | 'empty' | 'error'
    title?: string
    description?: string
    actionLabel?: string
    compact?: boolean
  }>(),
  { kind: 'empty', title: '', description: '', actionLabel: '', compact: false }
)

const emit = defineEmits<{ (e: 'action'): void }>()

const defaultTitle = computed(() => {
  if (props.title) return props.title
  if (props.kind === 'loading') return '加载中…'
  if (props.kind === 'error') return '加载失败'
  return '暂无数据'
})

const defaultIcon = computed(() => {
  if (props.kind === 'error') return '⚠'
  if (props.kind === 'empty') return '⌕'
  return ''
})
</script>

<template>
  <div class="state-box" :class="{ compact }">
    <div v-if="kind === 'loading'" class="ic"><span class="spinner"></span></div>
    <div v-else class="ic">{{ defaultIcon }}</div>
    <div class="ti">{{ defaultTitle }}</div>
    <div v-if="description" class="de">{{ description }}</div>
    <div v-if="actionLabel" class="act">
      <button class="btn sm" @click="emit('action')">{{ actionLabel }}</button>
    </div>
  </div>
</template>
