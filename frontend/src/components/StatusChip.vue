<script setup lang="ts">
import type { ChipTone } from '../utils/status'

defineProps<{
  tone: ChipTone
  label: string
}>()
</script>

<template>
  <span class="chip" :class="tone"><span class="d"></span>{{ label }}</span>
</template>
