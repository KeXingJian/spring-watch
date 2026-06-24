<script setup lang="ts">
import { computed } from 'vue'
import LineChart from './LineChart.vue'
import BarChart from './BarChart.vue'
import PieChart from './PieChart.vue'
import type { LineSeriesItem, BarSeriesItem, PieDatum, ChartType } from './types'

const props = defineProps<{
  type: ChartType
  // line / bar 共用 series,具体形态由 type 决定(调用方负责正确传值)
  series?: LineSeriesItem[] | BarSeriesItem[]
  smooth?: boolean
  area?: boolean
  stack?: boolean
  yAxisName?: string
  yAxisName2?: string
  dualAxis?: boolean
  // bar
  categories?: string[]
  horizontal?: boolean
  // pie
  data?: PieDatum[]
  donut?: boolean
  legendOrient?: 'vertical' | 'horizontal'
  height?: string
}>()

const castLineSeries = computed<LineSeriesItem[] | undefined>(() =>
  props.type === 'line' ? (props.series as LineSeriesItem[] | undefined) : undefined
)
const castBarSeries = computed<BarSeriesItem[] | undefined>(() =>
  props.type === 'bar' ? (props.series as BarSeriesItem[] | undefined) : undefined
)
const castPie = computed<PieDatum[] | undefined>(() => props.data)
</script>

<template>
  <LineChart
    v-if="type === 'line' && castLineSeries"
    :series="castLineSeries"
    :smooth="smooth"
    :area="area"
    :stack="stack"
    :y-axis-name="yAxisName"
    :y-axis-name2="yAxisName2"
    :dual-axis="dualAxis"
    :height="height"
  />
  <BarChart
    v-else-if="type === 'bar' && castBarSeries"
    :categories="categories || []"
    :series="castBarSeries"
    :horizontal="horizontal"
    :y-axis-name="yAxisName"
    :height="height"
  />
  <PieChart
    v-else-if="type === 'pie' && castPie"
    :data="castPie"
    :donut="donut"
    :legend-orient="legendOrient"
    :height="height"
  />
</template>
