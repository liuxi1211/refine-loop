<script setup lang="ts">
import { useUiStore } from '../stores/ui'

const ui = useUiStore()

function iconOf(type: string): string {
  if (type === 'ok') return '✓'
  if (type === 'err') return '✕'
  return 'i'
}
</script>

<template>
  <div class="toasts">
    <transition-group name="toast">
      <div
        v-for="item in ui.toasts"
        :key="item.id"
        class="toast"
        :class="item.type"
        @click="ui.dismissToast(item.id)"
      >
        <span class="ic">{{ iconOf(item.type) }}</span>
        <span>{{ item.message }}</span>
      </div>
    </transition-group>
  </div>
</template>
