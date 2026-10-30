<script setup lang="ts">
import { computed } from 'vue'
import type { EChartsOption } from 'echarts'
import EChart from '@/components/EChart.vue'
import { platformName } from '@/utils/platformBrand'

const props = defineProps<{ rows: { platform: string; solved: number }[]; textColor?: string }>()
const data = computed(() => props.rows.filter(row => row.solved > 0).map(row => ({ name: platformName(row.platform), value: row.solved })))
const option = computed<EChartsOption>(() => ({
  aria: { enabled: true },
  tooltip: { trigger: 'item', formatter: '{b}: {c} 题 ({d}%)' },
  legend: { type: 'scroll', bottom: 0, textStyle: { color: props.textColor } },
  series: [{ type: 'pie', radius: ['38%', '68%'], center: ['50%', '43%'], avoidLabelOverlap: true,
    label: { formatter: '{b}\n{d}%' }, data: data.value }]
}))
</script>

<template>
  <section class="border border-line rounded-card border-solid bg-surface p-4 md:(px-[22px] py-5)" data-test="platform-pie">
    <h2 class="m-0 text-base text-ink font-semibold">各平台通过占比</h2>
    <EChart v-if="data.length" :option="option" height="tall" />
    <p v-else class="text-sm text-muted">还没有已同步的通过题。</p>
    <p class="m-0 text-xs text-muted">按已绑定账号的通过题数统计；跨平台同题可能重复计数。</p>
  </section>
</template>
