<script setup lang="ts">
import { useToast } from '@/utils/toast'

const toast = useToast()

function alertClass(type: string) {
  switch (type) {
    case 'success': return 'alert-success'
    case 'warn': return 'alert-warning'
    case 'error': return 'alert-error'
    default: return 'alert-info'
  }
}
</script>

<template>
  <div class="toast toast-top toast-end z-[9999]">
    <div
      v-for="t in toast.toasts.value"
      :key="t.id"
      class="alert shadow-lg"
      :class="alertClass(t.type)"
    >
      <span>{{ t.msg }}</span>
    </div>
  </div>
</template>
