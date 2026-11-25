<script setup lang="ts">
/**
 * 字典下拉框：选项来自后端字典（useDict），不再手写 `<option>`。
 *
 * 用法：
 *   <DictSelect type="jobStatus" v-model="status" />            // 「全部」+ 枚举项
 *   <DictSelect type="jobStatus" v-model="status" :allow-empty="false" />
 *
 * 空值约定：allowEmpty 时会有一个 value="" 的「全部」选项，并原样 emit 空字符串；
 * 需要 null 语义的调用方自行转换（`status || null`），避免「'' 与 null」两套判断混用。
 */
import { computed } from 'vue'
import { useDict } from '../composables/useDict'

const props = withDefaults(
  defineProps<{
    type: string
    modelValue?: string | null
    /** 空值选项文案；allowEmpty=false 时不显示 */
    placeholder?: string
    allowEmpty?: boolean
    disabled?: boolean
  }>(),
  {
    modelValue: null,
    placeholder: '全部',
    allowEmpty: true,
    disabled: false
  }
)

const emit = defineEmits<{ 'update:modelValue': [value: string] }>()

const { options } = useDict(props.type)

const value = computed<string>({
  get: () => props.modelValue ?? '',
  set: (next) => emit('update:modelValue', next)
})
</script>

<template>
  <select v-model="value" class="sel dict-select" :disabled="disabled">
    <option v-if="allowEmpty" value="">{{ placeholder }}</option>
    <option v-for="option in options" :key="option.value" :value="option.value">
      {{ option.label }}
    </option>
  </select>
</template>

<style scoped>
.dict-select {
  min-width: 120px;
}
</style>
