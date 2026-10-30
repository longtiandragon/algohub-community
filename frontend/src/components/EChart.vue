<script setup lang="ts">
import { onMounted, onBeforeUnmount, ref, watch } from 'vue'
import * as echarts from 'echarts'

const props = defineProps<{ option: echarts.EChartsOption; height?: string }>()
const emit = defineEmits<{ pointClick: [value: { data?: unknown; name?: string }] }>()
const el = ref<HTMLDivElement>()
let chart: echarts.ECharts | null = null
let resizeObserver: ResizeObserver | null = null

function render() {
  if (!el.value) return
  if (!chart) {
    chart = echarts.init(el.value)
    chart.on('click', (value) => emit('pointClick', value as { data?: unknown; name?: string }))
  }
  // 画布里的字默认用 ECharts 自带的 sans-serif，Windows 上会落到日文字体（例如"绿"画成"緑"）：跟页面用同一套中文字体
  const fontFamily = getComputedStyle(document.body).fontFamily
  chart.setOption({ textStyle: { fontFamily }, ...props.option }, true)
}

function onResize() {
  chart?.resize()
}

onMounted(() => {
  render()
  resizeObserver = new ResizeObserver(onResize)
  if (el.value) resizeObserver.observe(el.value)
  window.addEventListener('resize', onResize)
})

onBeforeUnmount(() => {
  window.removeEventListener('resize', onResize)
  resizeObserver?.disconnect()
  chart?.dispose()
  chart = null
})

watch(() => props.option, render, { deep: true })
</script>

<template>
  <div ref="el" :class="height === 'tall' ? 'chart-tall' : 'chart'" />
</template>
