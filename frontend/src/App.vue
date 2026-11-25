<script setup lang="ts">
import { computed } from 'vue'
import { useRoute } from 'vue-router'
import AppSidebar from './components/AppSidebar.vue'
import AppTopBar from './components/AppTopBar.vue'
import ConfirmDialog from './components/ConfirmDialog.vue'
import ToastHost from './components/ToastHost.vue'

const route = useRoute()

/** 登录页不渲染工作台骨架（无侧边栏/顶栏）。 */
const blank = computed(() => route.meta.layout === 'blank')
</script>

<template>
  <div v-if="blank" class="blank-shell">
    <router-view />
  </div>
  <div v-else class="app">
    <AppSidebar />
    <div class="main">
      <AppTopBar />
      <div class="content">
        <router-view />
      </div>
    </div>
  </div>
  <ToastHost />
  <ConfirmDialog />
</template>
