<script setup lang="ts">
import { computed, provide } from 'vue'
import VChart, { THEME_KEY } from 'vue-echarts'
import { PALETTE } from './palette'
import type { BarSeriesItem } from './types'

const props = withDefaults(
  defineProps<{
    categories: string[]
    series: BarSeriesItem[]
    horizontal?: boolean
    yAxisName?: string
    height?: string
  }>(),
  { horizontal: false, height: '100%' }
)

provide(THEME_KEY, 'light')

const option = computed(() => {
  const series = props.series.map((s, i) => ({
    name: s.name,
    type: 'bar',
    itemStyle: s.data.some((d) => typeof d === 'object' && d && 'itemStyle' in d)
      ? undefined
      : { color: PALETTE[i % PALETTE.length] },
    data: s.data
  }))
  const categoryAxis = {
    type: 'category',
    data: props.categories,
    axisLabel: { fontSize: 11 }
  }
  const valueAxis = {
    type: 'value',
    name: props.yAxisName || '',
    axisLabel: { fontSize: 11 }
  }
  return {
    color: PALETTE,
    grid: { left: props.horizontal ? 110 : 50, right: 20, top: 30, bottom: 50 },
    tooltip: { trigger: 'axis', axisPointer: { type: 'shadow' } },
    legend: { top: 0, type: 'scroll' },
    xAxis: props.horizontal ? valueAxis : categoryAxis,
    yAxis: props.horizontal ? categoryAxis : valueAxis,
    series
  }
})
</script>

<template>
  <VChart :option="option" :style="{ height, width: '100%' }" autoresize />
</template>
