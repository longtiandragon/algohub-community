<script setup lang="ts">
import { onMounted, onBeforeUnmount, ref, watch } from 'vue'
import type { EChartsOption } from 'echarts'
import type { ECharts } from 'echarts/core'

const props = defineProps<{ option: EChartsOption; height?: string }>()
const emit = defineEmits<{ pointClick: [value: { data?: unknown; name?: string }] }>()
const el = ref<HTMLDivElement>()
let chart: ECharts | null = null
let resizeObserver: ResizeObserver | null = null

const chartError = ref(false)
let visibility: IntersectionObserver | undefined
let disposed = false
async function initialize() {
  if (!el.value || chart || disposed) return
  chartError.value = false
  try {
    const { init } = await import('@/utils/chartRuntime')
    if (!el.value || disposed || chart) return
    chart = init(el.value)
    chart.on('click', value => emit('pointClick', value as { data?: unknown; name?: string }))
    render()
  } catch { chartError.value = true }
}
function render() {
  if (!chart) return
  // 画布里的字默认用 ECharts 自带的 sans-serif，Windows 上会落到日文字体（例如"绿"画成"緑"）：跟页面用同一套中文字体
  const fontFamily = getComputedStyle(document.body).fontFamily
  chart.setOption({ textStyle: { fontFamily }, ...props.option }, true)
}

function onResize() {
  chart?.resize()
}

onMounted(() => {
  visibility = new IntersectionObserver(entries => {
    if (entries.some(entry => entry.isIntersecting)) {
      visibility?.disconnect()
      void initialize()
    }
  }, { rootMargin: '100px' })
  if (el.value) visibility.observe(el.value)
  resizeObserver = new ResizeObserver(onResize)
  if (el.value) resizeObserver.observe(el.value)
  window.addEventListener('resize', onResize)
})

onBeforeUnmount(() => {
  window.removeEventListener('resize', onResize)
  resizeObserver?.disconnect()
  disposed = true
  visibility?.disconnect()
  chart?.dispose()
  chart = null
})

watch(() => props.option, render, { deep: true })
</script>

<template>
  <div ref="el" :class="height === 'tall' ? 'chart-tall' : 'chart'" />
  <button v-if="chartError" type="button" @click="initialize">重试加载图表</button>
</template>
