export interface LineSeriesItem {
  name: string
  points: [number | string, number | null][]
  stack?: boolean
  area?: boolean
}

export interface BarSeriesItem {
  name: string
  data: (number | { value: number; itemStyle?: { color?: string } })[]
}

export interface PieDatum {
  name: string
  value: number
  itemStyle?: { color?: string }
}

export type ChartType = 'line' | 'bar' | 'pie'
