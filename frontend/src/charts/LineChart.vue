<script setup lang="ts">
import { computed, provide } from 'vue'
import VChart, { THEME_KEY } from 'vue-echarts'
import { PALETTE } from './palette'
import type { LineSeriesItem } from './types'

const props = withDefaults(
  defineProps<{
    series: LineSeriesItem[]
    smooth?: boolean
    area?: boolean
    stack?: boolean
    yAxisName?: string
    yAxisName2?: string
    dualAxis?: boolean
    height?: string
  }>(),
  {
    smooth: true,
    area: false,
    stack: false,
    height: '100%'
  }
)

provide(THEME_KEY, 'light')

const option = computed(() => {
  const data = props.series.map((s, i) => ({
    name: s.name,
    type: 'line',
    smooth: props.smooth,
    showSymbol: false,
    areaStyle: s.area || props.area ? { opacity: 0.2 } : null,
    stack: s.stack || props.stack ? 'total' : null,
    emphasis: { focus: 'series' },
    lineStyle: { width: 2 },
    itemStyle: { color: PALETTE[i % PALETTE.length] },
    data: s.points
  }))
  return {
    color: PALETTE,
    grid: { left: 50, right: props.dualAxis ? 50 : 20, top: 30, bottom: 50 },
    tooltip: { trigger: 'axis', axisPointer: { type: 'cross' } },
    legend: { top: 0, type: 'scroll' },
    xAxis: { type: 'time', boundaryGap: false, axisLabel: { fontSize: 11 } },
    yAxis: props.dualAxis
      ? [
          { type: 'value', name: props.yAxisName || '', axisLabel: { fontSize: 11 } },
          { type: 'value', name: props.yAxisName2 || '', axisLabel: { fontSize: 11 } }
        ]
      : { type: 'value', name: props.yAxisName || '', axisLabel: { fontSize: 11 } },
    dataZoom: [{ type: 'inside' }, { type: 'slider', height: 18, bottom: 8 }],
    series: data
  }
})
</script>

<template>
  <VChart :option="option" :style="{ height, width: '100%' }" autoresize />
</template>
