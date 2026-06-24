import { ref, readonly } from 'vue'

export type ToastType = 'info' | 'success' | 'warn' | 'error'

export interface ToastItem {
  id: number
  msg: string
  type: ToastType
}

const list = ref<ToastItem[]>([])
let seq = 0

export function useToast() {
  return {
    toasts: readonly(list),
    push(msg: string, type: ToastType = 'info', duration = 3000) {
      const id = ++seq
      list.value.push({ id, msg, type })
      setTimeout(() => {
        const i = list.value.findIndex((t) => t.id === id)
        if (i >= 0) list.value.splice(i, 1)
      }, duration)
    },
    info(msg: string) { this.push(msg, 'info') },
    success(msg: string) { this.push(msg, 'success') },
    warn(msg: string) { this.push(msg, 'warn') },
    error(msg: string) { this.push(msg, 'error') }
  }
}
