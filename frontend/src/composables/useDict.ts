import { computed, onMounted } from 'vue'
import { useDictStore, type DictOption } from '../stores/dict'
import type { DictItem } from '../api/dict'

/**
 * 字典组合式函数：在组件 setup 里 `const { options, labelOf } = useDict('jobStatus')`。
 *
 * 首次使用时自动触发字典加载（Store 内部会合并并发请求），组件无需关心加载时机。
 * 注意：必须在组件 setup 中调用（内部使用 onMounted）。
 */
export function useDict(type: string) {
  const store = useDictStore()

  onMounted(() => {
    void store.ensureLoaded()
  })

  const items = computed<DictItem[]>(() => store.items(type))
  const options = computed<DictOption[]>(() => store.options(type))
  const loaded = computed(() => store.state === 'ready')

  function labelOf(code?: string | null): string {
    return store.labelOf(type, code)
  }

  function itemOf(code?: string | null): DictItem | undefined {
    return store.itemOf(type, code)
  }

  return { items, options, loaded, labelOf, itemOf }
}
