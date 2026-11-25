<script setup lang="ts">
withDefaults(
  defineProps<{
    label: string
    value: string
    unit?: string
    tone?: 'blue' | 'green' | 'amber' | 'red'
    loading?: boolean
    clickable?: boolean
    small?: boolean
    icon?: string
  }>(),
  { unit: '', tone: 'blue', loading: false, clickable: false, small: false, icon: '' }
)
</script>

<template>
  <div
    class="stat"
    :class="[{ g: tone === 'green', a: tone === 'amber', r: tone === 'red', clickable }]"
    :style="clickable ? { cursor: 'pointer' } : undefined"
  >
    <span v-if="icon" class="bg-ico">{{ icon }}</span>
    <div class="lb">{{ label }}</div>
    <div class="v" :class="{ sm: small }">
      <template v-if="loading">—</template>
      <template v-else>{{ value }}<small v-if="unit">{{ unit }}</small></template>
    </div>
    <div class="tr">
      <slot />
    </div>
  </div>
</template>
