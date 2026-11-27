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

const environmentLabel = computed(() =>
  import.meta.env.DEV ? '开发环境' : '个人投研环境'
)
const environmentTag = computed(() => (import.meta.env.DEV ? 'DEV' : 'LOCAL'))
</script>

<template>
  <header class="topbar">
    <div class="crumb">投研工作台 / <b>{{ title }}</b></div>
    <div class="top-right">
      <span class="proto-note">{{ environmentLabel }}</span>
      <span class="tag env">{{ environmentTag }}</span>
      <span class="tag ver">{{ auth.user?.roleLabel || '—' }}</span>
      <span class="tag trade">{{ auth.displayName }}</span>
    </div>
  </header>
</template>
