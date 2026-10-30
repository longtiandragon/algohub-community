<script setup lang="ts">
import { computed, ref, watch } from 'vue'
import type { EChartsOption } from 'echarts'
import EChart from '@/components/EChart.vue'
import EmptyState from '@/components/ui/EmptyState.vue'
import InfoTip from '@/components/ui/InfoTip.vue'
import SegmentedControl from '@/components/ui/SegmentedControl.vue'
import Skeleton from '@/components/ui/Skeleton.vue'
import { platformName } from '@/utils/platformBrand'

/**
 * 「分布」标签：难度分布（按所选平台自己的难度，不跨平台换算）和标签练习分布（通过题数前 15 个分类）。
 * 图表沿用 ECharts，颜色从设计系统读（theme），深浅色切换时跟着变。
 */
const props = defineProps<{
  difficulty: { platform: string; difficulty: string; source: string; solved: number }[]
  tags: { category: string; solved: number }[]
  coverage: { withTags: number; total: number } | null
  theme: { text: string; muted: string; border: string; elevated: string; brand: string }
  loading?: boolean
}>()

/** 没评级的题（后端记为"未知"，洛谷"暂无评定"）单独一档，不和有难度的混排 */
const UNRATED = new Set(['未知', '暂无评定', '未评级'])
/** AtCoder Problems 的估计难度是连续数值：按社区通用的颜色段归并，否则每个数值各占一根柱子 */
const ATCODER_BANDS = [
  { below: 400, label: '灰 <400', color: '#808080' },
  { below: 800, label: '茶 400–799', color: '#804000' },
  { below: 1200, label: '绿 800–1199', color: '#008000' },
  { below: 1600, label: '水 1200–1599', color: '#00a8a8' },
  { below: 2000, label: '青 1600–1999', color: '#1f5fd6' },
  { below: 2400, label: '黄 2000–2399', color: '#b8b800' },
  { below: 2800, label: '橙 2400–2799', color: '#ff8000' },
  { below: Infinity, label: '红 ≥2800', color: '#e03030' }
]
/** 洛谷现行九档（不含「暂无评定」），与官网主页「难易度统计」同序 */
const LUOGU_ORDER = ['入门', '普及−', '普及', '普及+/提高−', '提高', '提高+/省选−', '省选/NOI−', 'NOI/NOI+/CTS']
type DifficultyBar = { label: string; solved: number; order: number; color?: string; unrated?: boolean }
function lastNumber(label: string) {
  const all = label.match(/-?\d+/g)
  return all ? Number(all[all.length - 1]) : null
}
function barsOf(platform: string): DifficultyBar[] {
  const bars = new Map<string, DifficultyBar>()
  const add = (bar: DifficultyBar) => {
    const existing = bars.get(bar.label)
    if (existing) existing.solved += bar.solved
    else bars.set(bar.label, { ...bar })
  }
  for (const r of props.difficulty) {
    if (r.platform !== platform) continue
    if (UNRATED.has(r.difficulty)) {
      add({ label: '未评级', solved: r.solved, order: Number.MAX_SAFE_INTEGER, unrated: true })
      continue
    }
    const n = lastNumber(r.difficulty)
    if (platform === 'ATCODER' && n != null) {
      const i = ATCODER_BANDS.findIndex((band) => n < band.below)
      add({ label: ATCODER_BANDS[i].label, solved: r.solved, order: i, color: ATCODER_BANDS[i].color })
    } else if (n != null && /^(CF|Tracker)\s/.test(r.difficulty)) {
      add({ label: `${r.difficulty.startsWith('Tracker') ? 'Tracker ' : ''}${n}`, solved: r.solved, order: n })
    } else {
      const i = LUOGU_ORDER.indexOf(r.difficulty)
      add({ label: r.difficulty, solved: r.solved, order: i >= 0 ? i : 1e6 })
    }
  }
  return [...bars.values()].sort((a, b) => a.order - b.order)
}

/** 只列出真有难度数据的平台，默认选有评级题目最多的那个 */
const platforms = computed(() => {
  const rated = new Map<string, number>()
  for (const r of props.difficulty) {
    if (!UNRATED.has(r.difficulty)) rated.set(r.platform, (rated.get(r.platform) ?? 0) + r.solved)
  }
  return [...rated.entries()].sort((a, b) => b[1] - a[1]).map(([platform]) => platform)
})
const platform = ref('')
watch(
  platforms,
  (list) => {
    if (!list.includes(platform.value)) platform.value = list[0] ?? ''
  },
  { immediate: true }
)
/** 分段按钮上的名字去掉括号（「力扣（中国站）」写「力扣」） */
const platformOptions = computed(() => platforms.value.map((p) => ({ label: platformName(p).replace(/（.*）$/, ''), value: p })))
const bars = computed(() => barsOf(platform.value))
const ratedCount = computed(() => bars.value.filter((b) => !b.unrated).reduce((sum, b) => sum + b.solved, 0))

const tooltip = computed(() => ({
  trigger: 'axis' as const,
  axisPointer: { type: 'shadow' as const },
  backgroundColor: props.theme.elevated,
  borderColor: props.theme.border,
  textStyle: { color: props.theme.text }
}))

const difficultyOption = computed<EChartsOption>(() => {
  const t = props.theme
  const rows = bars.value
  return {
    tooltip: tooltip.value,
    // 横向条形图：难度名称完整显示在左侧，和旁边的标签分布一个读法
    grid: { left: 8, right: 44, top: 8, bottom: 24, containLabel: true },
    xAxis: { type: 'value', minInterval: 1, axisLabel: { color: t.muted }, splitLine: { lineStyle: { color: t.border } } },
    yAxis: {
      type: 'category',
      inverse: true,
      data: rows.map((r) => r.label),
      axisLabel: { color: t.text, fontSize: 12 },
      axisLine: { lineStyle: { color: t.border } },
      axisTick: { show: false }
    },
    series: [{
      name: '通过题数',
      type: 'bar',
      barMaxWidth: 18,
      data: rows.map((r) => ({ value: r.solved, itemStyle: { color: r.unrated ? t.border : r.color ?? t.brand, borderRadius: [0, 4, 4, 0] } })),
      label: { show: true, position: 'right', color: t.muted, fontSize: 11 }
    }]
  }
})

const topTags = computed(() => props.tags.slice(0, 15))
const tagOption = computed<EChartsOption>(() => {
  const t = props.theme
  const rows = [...topTags.value].reverse()
  return {
    tooltip: tooltip.value,
    grid: { left: 8, right: 36, top: 8, bottom: 24, containLabel: true },
    xAxis: { type: 'value', minInterval: 1, axisLabel: { color: t.muted }, splitLine: { lineStyle: { color: t.border } } },
    yAxis: {
      type: 'category',
      data: rows.map((r) => (r.category === 'UNMAPPED' ? '未分类标签' : r.category)),
      axisLabel: { color: t.text, fontSize: 12 },
      axisLine: { lineStyle: { color: t.border } },
      axisTick: { show: false }
    },
    series: [{
      name: '通过题数',
      type: 'bar',
      barMaxWidth: 18,
      data: rows.map((r) => r.solved),
      itemStyle: { color: t.brand, borderRadius: [0, 4, 4, 0] },
      label: { show: true, position: 'right', color: t.muted, fontSize: 11 }
    }]
  }
})
</script>

<template>
  <div class="grid grid-cols-1 items-start gap-4 lg:grid-cols-2">
    <section class="dist-card" data-test="difficulty-card">
      <div class="flex flex-wrap items-center justify-between gap-x-3 gap-y-2">
        <h2 class="m-0 flex items-center gap-1 text-base text-ink font-semibold">
          难度分布
          <InfoTip title="难度分布怎么算">
            按所选平台自己的难度统计，不跨平台换算；AtCoder 按 AtCoder Problems 的估计值归入颜色段。没有评级的题列在最后。只有提供逐题明细的平台才能统计难度。
          </InfoTip>
        </h2>
        <SegmentedControl v-if="platforms.length > 1 && platforms.length <= 4" v-model="platform" :options="platformOptions" label="按哪个平台的难度" class="small" />
        <el-select v-else-if="platforms.length > 4" v-model="platform" class="!w-36" aria-label="按哪个平台的难度">
          <el-option v-for="option in platformOptions" :key="option.value" :label="option.label" :value="option.value" />
        </el-select>
        <span v-else-if="platforms.length === 1" class="text-[12.5px] text-muted">{{ platformName(platforms[0]) }}</span>
      </div>
      <Skeleton v-if="loading && !platforms.length" class="h-[340px]" />
      <template v-else-if="platforms.length">
        <span class="text-[12.5px] text-muted" data-test="difficulty-count">{{ platformName(platform) }} 有评级的通过题 <span class="num">{{ ratedCount }}</span> 道</span>
        <EChart :option="difficultyOption" height="tall" />
      </template>
      <EmptyState
        v-else
        icon="i-lucide-chart-bar"
        title="还没有带难度的通过记录"
        description="同步 Codeforces、AtCoder 这类有逐题明细的平台后，这里按难度列出通过题数。"
        action="去平台账号"
        to="/accounts"
        :bordered="false"
      />
    </section>

    <section class="dist-card" data-test="tag-card">
      <div class="flex flex-wrap items-center justify-between gap-x-3 gap-y-2">
        <h2 class="m-0 flex items-center gap-1 text-base text-ink font-semibold">
          标签练习分布
          <InfoTip title="标签分布怎么看">
            表示练习接触过哪些方向，不代表掌握程度。按通过题数取前 15 个分类；没归进统一分类的原始标签记为「未分类标签」。
          </InfoTip>
        </h2>
        <span class="text-[12.5px] text-muted">通过题数前 15 个分类</span>
      </div>
      <Skeleton v-if="loading && !tags.length" class="h-[340px]" />
      <EChart v-else-if="tags.length" :option="tagOption" height="tall" />
      <EmptyState
        v-else
        icon="i-lucide-tags"
        title="还没有可统计的标签"
        description="已同步的题目还没有带上标签，或者所在平台不提供标签。"
        :bordered="false"
      />
      <p v-if="coverage && coverage.total" class="m-0 text-[12.5px] text-muted" data-test="tag-coverage">
        已通过的 <span class="num">{{ coverage.total }}</span> 道题里，<span class="num">{{ coverage.withTags }}</span> 道带标签。
      </p>
    </section>
  </div>
</template>

<style scoped>
.dist-card {
  display: flex;
  flex-direction: column;
  gap: 12px;
  min-width: 0;
  padding: 16px;
  border: 1px solid var(--border);
  border-radius: var(--radius);
  background: var(--bg-elevated);
}

@media (min-width: 768px) {
  .dist-card {
    padding: 20px 22px;
  }
}

/* 卡片标题旁的分段按钮小一号 */
.small :deep(.segmented-item) {
  height: 28px;
  padding: 0 10px;
  font-size: 12.5px;
}
</style>
