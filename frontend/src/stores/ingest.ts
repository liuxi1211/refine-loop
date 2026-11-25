import { defineStore } from 'pinia'
import { computed, ref } from 'vue'
import { asApiError, type ApiError } from '../api/http'
import * as ingestApi from '../api/ingest'
import type { BackfillPlan, IngestJobView, IngestTriggerAck, OrchestrationProgress } from '../api/types'
import { useUiStore } from './ui'

/** 接入作业页：运行中编排（5s 轮询由视图驱动）、作业历史、一键每日增量、初始回填向导 */
export const useIngestStore = defineStore('ingest', () => {
  /* ---------------- 运行中编排 ---------------- */
  const orchestration = ref<OrchestrationProgress | null>(null)
  const orchestrationLoading = ref(false)
  const orchestrationLoaded = ref(false)
  const orchestrationError = ref<ApiError | null>(null)

  /* ---------------- 作业历史 ---------------- */
  const jobs = ref<IngestJobView[]>([])
  const jobsTotal = ref(0)
  const jobsPage = ref(1)
  const jobsSize = ref(20)
  const jobsStatus = ref('')
  const jobsTable = ref('')
  const jobsLoading = ref(false)
  const jobsLoaded = ref(false)
  const jobsError = ref<ApiError | null>(null)

  /* ---------------- 触发动作 ---------------- */
  const triggeringDaily = ref(false)
  const retryingIds = ref<number[]>([])
  const creatingPlan = ref(false)

  const totalJobPages = computed(() => Math.max(1, Math.ceil(jobsTotal.value / jobsSize.value)))

  async function fetchOrchestration(options: { silent?: boolean; background?: boolean } = {}) {
    if (!options.background) {
      orchestrationLoading.value = true
      orchestrationError.value = null
    }
    try {
      orchestration.value = await ingestApi.fetchCurrentOrchestration(!!options.silent)
      orchestrationLoaded.value = true
      orchestrationError.value = null
    } catch (err) {
      if (!options.background) orchestrationError.value = asApiError(err)
    } finally {
      if (!options.background) orchestrationLoading.value = false
    }
  }

  async function fetchJobs(options: { silent?: boolean; background?: boolean } = {}) {
    if (!options.background) {
      jobsLoading.value = true
      jobsError.value = null
    }
    try {
      const data = await ingestApi.fetchJobs(
        {
          status: jobsStatus.value || undefined,
          table: jobsTable.value || undefined,
          page: jobsPage.value,
          size: jobsSize.value
        },
        !!options.silent
      )
      jobs.value = Array.isArray(data.items) ? data.items : []
      jobsTotal.value = Number(data.total ?? 0)
      if (Number(data.page) > 0) jobsPage.value = Number(data.page)
      jobsLoaded.value = true
      jobsError.value = null
    } catch (err) {
      if (!options.background) jobsError.value = asApiError(err)
    } finally {
      if (!options.background) jobsLoading.value = false
    }
  }

  function setJobStatus(status: string) {
    jobsStatus.value = status
    jobsPage.value = 1
    void fetchJobs()
  }

  function setJobTable(table: string) {
    jobsTable.value = table
    jobsPage.value = 1
    void fetchJobs()
  }

  function setJobPage(page: number) {
    const target = Math.min(Math.max(1, page), totalJobPages.value)
    if (target === jobsPage.value) return
    jobsPage.value = target
    void fetchJobs()
  }

  /** 一键每日增量：返回 ack 供视图决定是否跳转；失败返回 null（toast 已提示） */
  async function triggerDaily(): Promise<IngestTriggerAck | null> {
    if (triggeringDaily.value) return null
    const ui = useUiStore()
    triggeringDaily.value = true
    try {
      const ack = await ingestApi.triggerDaily()
      ui.pushToast('ok', ack?.message || '每日增量编排已提交：按 维表 → 行情 → 财务事件 分层调度')
      return ack
    } catch {
      return null
    } finally {
      triggeringDaily.value = false
    }
  }

  async function retryJob(id: number): Promise<boolean> {
    if (retryingIds.value.includes(id)) return false
    const ui = useUiStore()
    retryingIds.value.push(id)
    try {
      const ack = await ingestApi.retryJob(id)
      ui.pushToast('ok', ack?.message || `作业 #${id} 重试已入队：限流器将等待下一分钟窗口`)
      await fetchJobs({ silent: true, background: true })
      void fetchOrchestration({ silent: true, background: true })
      return true
    } catch {
      return false
    } finally {
      retryingIds.value = retryingIds.value.filter((item) => item !== id)
    }
  }

  async function createBackfillPlan(
    mode: ingestApi.BackfillPlanMode,
    tables: string[] = []
  ): Promise<BackfillPlan | null> {
    if (creatingPlan.value) return null
    creatingPlan.value = true
    try {
      const plan = await ingestApi.createBackfillPlan(mode, tables)
      useUiStore().pushToast('ok', plan?.message || '回填计划已创建')
      return plan
    } catch {
      return null
    } finally {
      creatingPlan.value = false
    }
  }

  return {
    // state
    orchestration,
    orchestrationLoading,
    orchestrationLoaded,
    orchestrationError,
    jobs,
    jobsTotal,
    jobsPage,
    jobsSize,
    jobsStatus,
    jobsTable,
    jobsLoading,
    jobsLoaded,
    jobsError,
    triggeringDaily,
    retryingIds,
    creatingPlan,
    // getters
    totalJobPages,
    // actions
    fetchOrchestration,
    fetchJobs,
    setJobStatus,
    setJobTable,
    setJobPage,
    triggerDaily,
    retryJob,
    createBackfillPlan
  }
})
