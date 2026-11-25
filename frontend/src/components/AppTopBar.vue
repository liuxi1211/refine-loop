<script setup lang="ts">
import { computed } from 'vue'
import { useRoute } from 'vue-router'
import { useAuthStore } from '../stores/auth'

const route = useRoute()
const auth = useAuthStore()

const title = computed(() => {
  const metaTitle = route.meta.title
  return typeof metaTitle === 'string' && metaTitle ? metaTitle : '数据总览'
})
</script>

<template>
  <header class="topbar">
    <div class="crumb">数据中心 / <b>{{ title }}</b></div>
    <div class="top-right">
      <span class="proto-note">本地开发 · 数据来自 8080 运维接口</span>
      <span class="tag env">DEV</span>
      <span class="tag ver">{{ auth.user?.roleLabel || '—' }}</span>
      <span class="tag trade">{{ auth.displayName }}</span>
    </div>
  </header>
</template>
