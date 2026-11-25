<script setup lang="ts">
withDefaults(
  defineProps<{
    modelValue: boolean
    title: string
    subtitle?: string
    width?: string
  }>(),
  { subtitle: '', width: '560px' }
)

const emit = defineEmits<{ (e: 'update:modelValue', value: boolean): void }>()

function close() {
  emit('update:modelValue', false)
}
</script>

<template>
  <Teleport to="body">
    <div class="mask" :class="{ show: modelValue }" @click.self="close">
      <div class="modal" :style="{ width }">
        <div class="modal-h">
          <h3>{{ title }}</h3>
          <span v-if="subtitle" class="sub" style="font-size: 12px; color: var(--tx-3)">{{ subtitle }}</span>
          <span class="x" @click="close">✕</span>
        </div>
        <div class="modal-b"><slot /></div>
        <div class="modal-f"><slot name="footer" /></div>
      </div>
    </div>
  </Teleport>
</template>
