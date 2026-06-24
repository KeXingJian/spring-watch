<script setup lang="ts">
import { computed, provide } from 'vue'
import VChart, { THEME_KEY } from 'vue-echarts'
import { PALETTE } from './palette'
import type { PieDatum } from './types'

const props = withDefaults(
  defineProps<{
    data: PieDatum[]
    name?: string
    donut?: boolean
    legendOrient?: 'vertical' | 'horizontal'
    height?: string
  }>(),
  { donut: false, legendOrient: 'vertical', height: '100%' }
)

provide(THEME_KEY, 'light')

const option = computed(() => ({
  color: PALETTE,
  tooltip: { trigger: 'item', formatter: '{b}: {c} ({d}%)' },
  legend: { orient: props.legendOrient, right: 10, top: 'center', type: 'scroll' },
  series: [
    {
      name: props.name || '',
      type: 'pie',
      radius: props.donut ? ['45%', '70%'] : '70%',
      center: ['38%', '50%'],
      avoidLabelOverlap: true,
      label: { show: true, formatter: '{b}\n{d}%' },
      labelLine: { show: true },
      data: props.data.map((d, i) =>
        d.itemStyle ? d : { ...d, itemStyle: { color: PALETTE[i % PALETTE.length] } }
      )
    }
  ]
}))
</script>

<template>
  <VChart :option="option" :style="{ height, width: '100%' }" autoresize />
</template>
