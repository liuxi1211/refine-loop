<script setup lang="ts">
import { onBeforeUnmount, onMounted, ref, watch } from 'vue'
import { useRoute } from 'vue-router'
import BackfillWizard from '../components/BackfillWizard.vue'
import JobHistoryTable from '../components/JobHistoryTable.vue'
import OrchestrationCard from '../components/OrchestrationCard.vue'
import QualityPanel from '../components/QualityPanel.vue'
import { useIngestStore } from '../stores/ingest'
import { useTablesStore } from '../stores/tables'

const route = useRoute()
const ingest = useIngestStore()
const tables = useTablesStore()

type TabKey = 'jobs' | 'quality'

const tab = ref<TabKey>(route.query.tab === 'quality' ? 'quality' : 'jobs')
const wizardOpen = ref(false)
let pollTimer: number | null = null

function stopPolling() {
  if (pollTimer !== null) {
    window.clearInterval(pollTimer)
    pollTimer = null
  }
}

/** 运行中编排 5s 轮询：静默请求，避免后端抖动时 toast 刷屏 */
function startPolling() {
  stopPolling()
  pollTimer = window.setInterval(() => {
    void ingest.fetchOrchestration({ silent: true, background: true })
  }, 5000)
}

async function switchTab(next: TabKey) {
  tab.value = next
  if (next === 'jobs') {
    await ingest.fetchOrchestration({ silent: true, background: true })
    startPolling()
  } else {
    stopPolling()
  }
}

async function triggerDaily() {
  const ack = await ingest.triggerDaily()
  if (ack) {
    await ingest.fetchOrchestration({ silent: true, background: true })
    await ingest.fetchJobs({ silent: true, background: true })
  }
}

watch(
  () => route.query.tab,
  (value) => {
    const next: TabKey = value === 'quality' ? 'quality' : 'jobs'
    if (next !== tab.value) void switchTab(next)
  }
)

onMounted(async () => {
  if (!tables.loaded) void tables.fetchOverview({ silent: true, background: true })
  await Promise.all([ingest.fetchOrchestration(), ingest.fetchJobs()])
  if (tab.value === 'jobs') startPolling()
})

onBeforeUnmount(stopPolling)
</script>

<template>
  <section>
    <div class="page-h rv">
      <div>
        <h2>接入作业</h2>
        <div class="desc">
          IngestOrchestrator 编排入口 · 依赖分层：维表层 → 行情层 → 财务与事件层 · 每表作业写 ingest_log
        </div>
      </div>
      <div class="right">
        <button class="btn" @click="wizardOpen = true">⤓ 初始回填向导</button>
        <button class="btn pri" :disabled="ingest.triggeringDaily" @click="triggerDaily">
          <span v-if="ingest.triggeringDaily" class="spinner on-dark" style="margin-right: 2px"></span>▶ 一键每日增量
        </button>
      </div>
    </div>

    <div class="tabs">
      <button :class="{ on: tab === 'jobs' }" @click="switchTab('jobs')">作业编排与历史</button>
      <button :class="{ on: tab === 'quality' }" @click="switchTab('quality')">数据质量门 (S0)</button>
    </div>

    <div v-show="tab === 'jobs'">
      <OrchestrationCard />
      <JobHistoryTable />
    </div>

    <QualityPanel v-if="tab === 'quality'" />

    <BackfillWizard v-model="wizardOpen" />
  </section>
</template>
