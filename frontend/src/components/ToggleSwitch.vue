<script setup lang="ts">
const props = withDefaults(
  defineProps<{
    modelValue: boolean
    pending?: boolean
    disabled?: boolean
  }>(),
  { pending: false, disabled: false }
)

const emit = defineEmits<{ (e: 'update:modelValue', value: boolean): void }>()

function toggle() {
  if (props.disabled || props.pending) return
  emit('update:modelValue', !props.modelValue)
}
</script>

<template>
  <label
    class="sw"
    :class="{ on: modelValue }"
    :style="{ opacity: disabled ? 0.45 : 1, cursor: disabled || pending ? 'not-allowed' : 'pointer' }"
    @click.prevent.stop="toggle"
  >
    <span class="tr"></span>
    <span v-if="$slots.default" class="tx"><slot /></span>
  </label>
</template>
