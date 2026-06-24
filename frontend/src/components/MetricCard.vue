<script setup lang="ts">
import { computed } from 'vue'
import { formatBytes, formatMs, formatNumber, formatPercent } from '@/utils/format'

const props = defineProps<{
  title: string
  value: number | null | undefined
  unit?: string
  sub?: string
  threshold?: number
  thresholdHigherIsBad?: boolean
  format?: 'bytes' | 'percent' | 'ms' | 'number'
  fixed?: number
}>()

const level = computed(() => {
  if (props.value == null || isNaN(props.value) || props.threshold == null) return ''
  const higher = props.thresholdHigherIsBad !== false
  if (higher) {
    if (props.value >= props.threshold) return 'danger'
    if (props.value >= props.threshold * 0.7) return 'warn'
  } else {
    if (props.value <= props.threshold) return 'danger'
  }
  return ''
})

const formatted = computed(() => {
  if (props.value == null || isNaN(props.value)) return '-'
  if (props.format === 'bytes') return formatBytes(props.value)
  if (props.format === 'percent') return formatPercent(props.value, props.fixed)
  if (props.format === 'ms') return formatMs(props.value)
  return formatNumber(props.value, props.fixed)
})
</script>

<template>
  <div class="metric-card">
    <div class="title">{{ title }}</div>
    <div>
      <span class="value" :class="level">{{ formatted }}</span>
      <span v-if="unit" class="unit">{{ unit }}</span>
    </div>
    <div v-if="sub" class="sub">{{ sub }}</div>
  </div>
</template>
