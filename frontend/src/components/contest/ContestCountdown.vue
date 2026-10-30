<script setup lang="ts">
import { computed } from 'vue'

/**
 * 倒计时：到 target 还有多久，写成「03:12:40」，超过一天写「2 天 03:12:40」。
 * now 由页面每秒更新一次传进来（整页只有一个定时器）。读屏不逐秒朗读（aria-live 关掉），
 * 另给一个整分钟的 aria-label。
 */
const props = defineProps<{ target: number; now: number }>()

const left = computed(() => Math.max(0, props.target - props.now))
const text = computed(() => {
  const ms = left.value
  const days = Math.floor(ms / 86_400_000)
  const hours = Math.floor((ms % 86_400_000) / 3_600_000)
  const minutes = Math.floor((ms % 3_600_000) / 60_000)
  const seconds = Math.floor((ms % 60_000) / 1000)
  const clock = [hours, minutes, seconds].map((v) => String(v).padStart(2, '0')).join(':')
  return days ? `${days} 天 ${clock}` : clock
})
const spoken = computed(() => {
  const minutes = Math.ceil(left.value / 60_000)
  const days = Math.floor(minutes / 1440)
  const hours = Math.floor((minutes % 1440) / 60)
  return [days ? `${days} 天` : '', hours ? `${hours} 小时` : '', `${minutes % 60} 分钟`].filter(Boolean).join(' ')
})
</script>

<template>
  <span class="num" aria-live="off" :aria-label="spoken" role="timer">{{ text }}</span>
</template>
