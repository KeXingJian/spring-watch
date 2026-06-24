<script setup lang="ts">
import { onMounted, onBeforeUnmount, watch } from 'vue'

const props = withDefaults(
  defineProps<{
    modelValue: boolean
    title?: string
    width?: number
    closeOnMask?: boolean
  }>(),
  { closeOnMask: true }
)
const emit = defineEmits<{
  (e: 'update:modelValue', v: boolean): void
}>()

function close() {
  emit('update:modelValue', false)
}

function onKeydown(e: KeyboardEvent) {
  if (e.key === 'Escape' && props.modelValue) close()
}

onMounted(() => window.addEventListener('keydown', onKeydown))
onBeforeUnmount(() => window.removeEventListener('keydown', onKeydown))

watch(
  () => props.modelValue,
  (v) => {
    document.body.style.overflow = v ? 'hidden' : ''
  }
)

function onMaskClick(e: MouseEvent) {
  if (props.closeOnMask && e.target === e.currentTarget) close()
}
</script>

<template>
  <Teleport to="body">
    <div v-if="modelValue" class="modal modal-open" @click="onMaskClick">
      <div class="modal-box max-w-none" :style="width ? { width: width + 'px' } : { minWidth: '420px' }">
        <div class="flex items-center justify-between pb-3 mb-3 border-b border-base-300">
          <h3 class="font-semibold text-base">
            <slot name="title">{{ title }}</slot>
          </h3>
          <button class="btn btn-ghost btn-sm btn-circle" @click="close" aria-label="close">
            <svg viewBox="0 0 20 20" fill="currentColor" class="w-5 h-5">
              <path d="M6.225 4.811a1 1 0 0 0-1.414 1.414L8.586 10l-3.775 3.775a1 1 0 1 0 1.414 1.414L10 11.414l3.775 3.775a1 1 0 0 0 1.414-1.414L11.414 10l3.775-3.775a1 1 0 0 0-1.414-1.414L10 8.586 6.225 4.811z" />
            </svg>
          </button>
        </div>
        <div class="max-h-[70vh] overflow-auto pr-1">
          <slot />
        </div>
        <div v-if="$slots.footer" class="modal-action mt-4">
          <slot name="footer" />
        </div>
      </div>
      <div class="modal-backdrop bg-black/40" @click="close" />
    </div>
  </Teleport>
</template>
