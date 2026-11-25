<script setup lang="ts">
import { useUiStore } from '../stores/ui'

const ui = useUiStore()
</script>

<template>
  <div class="mask" :class="{ show: ui.confirmState.open }" @click.self="ui.resolveConfirm(false)">
    <div class="modal" style="width: 400px">
      <div class="modal-h">
        <h3>{{ ui.confirmState.title }}</h3>
        <span class="x" @click="ui.resolveConfirm(false)">✕</span>
      </div>
      <div class="modal-b" style="font-size: 13px; color: var(--tx-2); white-space: pre-line">
        {{ ui.confirmState.body }}
      </div>
      <div class="modal-f">
        <button class="btn" @click="ui.resolveConfirm(false)">{{ ui.confirmState.cancelText }}</button>
        <button
          class="btn"
          :class="ui.confirmState.tone === 'danger' ? 'danger' : 'pri'"
          @click="ui.resolveConfirm(true)"
        >
          {{ ui.confirmState.okText }}
        </button>
      </div>
    </div>
  </div>
</template>
