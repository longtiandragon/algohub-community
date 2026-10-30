<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import type { EChartsOption } from 'echarts'
import { get } from '@/api/client'
import type { RatingSeries, RatedContestRow } from '@/api/types'
import PlatformLogo from '@/components/PlatformLogo.vue'
import EmptyState from '@/components/ui/EmptyState.vue'
import InfoTip from '@/components/ui/InfoTip.vue'
import ResponsiveList from '@/components/ui/ResponsiveList.vue'
import Skeleton from '@/components/ui/Skeleton.vue'
import type { ListColumn } from '@/components/ui/types'
import { platformName } from '@/utils/platformBrand'
import { campusDate } from '@/utils/statistics'
import EChart from './EChart.vue'

/**
 * 比赛与 Rating：每个账号一张小卡（当前、最高、计分场数），Codeforces、AtCoder 的计分比赛曲线，和最近 8 场。
 * 不计分的场次只进表格不进曲线；一场都没参加过就照实说，不画一条 0 分线。最近的比赛电脑上是表格，手机上是卡片。
 */
const props = defineProps<{
  userId?: number
  theme: { text: string; muted: string; border: string; elevated: string; brand: string; success: string; warning: string; danger: string }
}>()

const series = ref<RatingSeries[]>([])
const loading = ref(true)
const error = ref('')

onMounted(async () => {
  try {
    series.value = await get<RatingSeries[]>(props.userId ? `/stats/rating-history/${props.userId}` : '/stats/rating-history')
  } catch {
    error.value = '比赛与 Rating 记录加载失败，已有记录不受影响，稍后刷新再看。'
  } finally {
    loading.value = false
  }
})

const totalContests = computed(() => series.value.reduce((sum, s) => sum + s.contests.length, 0))

/** 每个账号的小卡：当前、最高、计分几场、另有几场不计分 */
const cards = computed(() =>
  series.value.map((s) => {
    const rated = s.contests.filter((c) => c.rated && c.newRating != null)
    return {
      key: s.accountId,
      platform: s.platform,
      handle: s.handle,
      current: rated.at(-1)?.newRating ?? null,
      max: rated.length ? Math.max(...rated.map((c) => c.newRating ?? 0)) : null,
      rated: rated.length,
      unrated: s.contests.length - rated.length
    }
  })
)

type Point = { value: [string, number]; contest: RatedContestRow; accountLabel: string }
const escape = (line: string) => line.replace(/[<>&]/g, (ch) => ({ '<': '&lt;', '>': '&gt;', '&': '&amp;' })[ch] ?? ch)

const option = computed<EChartsOption>(() => {
  const t = props.theme
  const colors = [t.brand, t.success, t.warning, t.danger]
  return {
    aria: { enabled: true },
    color: colors,
    legend: { top: 0, textStyle: { color: t.text } },
    tooltip: {
      trigger: 'item',
      confine: true,
      backgroundColor: t.elevated,
      borderColor: t.border,
      textStyle: { color: t.text },
      formatter: (params: unknown) => {
        const point = (params as { data: Point }).data
        const c = point.contest
        const delta = c.newRating != null && c.oldRating != null ? c.newRating - c.oldRating : null
        const lines = [
          `${point.accountLabel} · ${dateText(c.finishedAt)}`,
          c.contestName,
          `Rating ${c.oldRating ?? '—'} → ${c.newRating ?? '—'}` + (delta == null ? '' : `（${delta >= 0 ? '+' : ''}${delta}）`)
        ]
        if (c.place != null) lines.push(`名次 ${c.place}`)
        if (c.performance != null) lines.push(`表现分 ${c.performance}`)
        return lines.map(escape).join('<br>')
      }
    },
    grid: { left: 48, right: 20, top: 36, bottom: 32 },
    xAxis: { type: 'time', axisLabel: { color: t.muted }, axisLine: { lineStyle: { color: t.border } } },
    yAxis: { type: 'value', scale: true, axisLabel: { color: t.muted }, splitLine: { lineStyle: { color: t.border } } },
    series: series.value.map((s) => {
      const label = `${platformName(s.platform)} · ${s.handle}`
      return {
        name: label,
        type: 'line',
        showSymbol: true,
        symbolSize: 6,
        data: s.contests
          .filter((c) => c.rated && c.newRating != null)
          .map((c): Point => ({ value: [c.finishedAt, c.newRating as number], contest: c, accountLabel: label }))
      }
    })
  }
})

type RecentRow = RatedContestRow & { platform: string; handle: string; key: string }
const recent = computed<RecentRow[]>(() =>
  series.value
    .flatMap((s) => s.contests.map((c) => ({ ...c, platform: s.platform, handle: s.handle, key: `${s.accountId}-${c.contestKey}` })))
    .sort((a, b) => b.finishedAt.localeCompare(a.finishedAt))
    .slice(0, 8)
)
const columns: ListColumn[] = [
  { key: 'contest', label: '比赛', minWidth: 260 },
  { key: 'place', label: '名次', width: 90, align: 'right' },
  { key: 'change', label: 'Rating 变化', width: 190 },
  { key: 'performance', label: '表现分', width: 90, align: 'right' },
  { key: 'date', label: '日期', width: 110 }
]

function delta(c: RatedContestRow) {
  return c.rated && c.newRating != null && c.oldRating != null ? c.newRating - c.oldRating : null
}
const deltaText = (value: number) => `${value >= 0 ? '+' : ''}${value}`
/** 比赛日期（北京时间）：今年的写「9/26」，往年的带年份 */
function dateText(value: string) {
  const day = campusDate(new Date(value))
  const [y, m, d] = day.split('-').map(Number)
  return day.slice(0, 4) === campusDate().slice(0, 4) ? `${m}/${d}` : `${y}/${m}/${d}`
}
</script>

<template>
  <section class="rating-card" data-test="rating-card">
    <div class="flex items-center justify-between gap-3">
      <h2 class="m-0 flex items-center gap-1 text-base text-ink font-semibold">
        比赛与 Rating
        <InfoTip title="这些记录从哪来">
          同步 Codeforces、AtCoder 账号时一起读到的比赛记录。不计分的场次只列在表格里，不画进曲线；表现分只有 AtCoder 提供。
        </InfoTip>
      </h2>
      <span v-if="totalContests" class="text-[12.5px] text-muted">共 <span class="num">{{ totalContests }}</span> 场</span>
    </div>

    <el-alert v-if="error" :title="error" type="warning" :closable="false" show-icon />
    <div v-else-if="loading" class="flex flex-col gap-3">
      <Skeleton class="h-[76px]" />
      <Skeleton class="h-[260px]" />
    </div>
    <EmptyState
      v-else-if="!series.length"
      icon="i-lucide-trophy"
      title="还没有比赛记录"
      description="Codeforces、AtCoder 账号同步后，这里显示参加过的比赛和 Rating 变化。"
      action="看看最近的比赛"
      to="/contests"
      :bordered="false"
    />
    <template v-else>
      <ul class="m-0 grid grid-cols-1 list-none gap-3 p-0 md:grid-cols-2 xl:grid-cols-3">
        <li v-for="card in cards" :key="card.key" class="stat" data-test="rating-stat">
          <div class="flex min-w-0 items-center gap-2">
            <PlatformLogo :platform="card.platform" :size="20" />
            <span class="text-[13.5px] text-ink">{{ platformName(card.platform) }}</span>
            <span class="mono truncate text-xs text-muted">{{ card.handle }}</span>
          </div>
          <div class="flex items-baseline gap-2">
            <span class="num text-[26px] text-ink font-semibold leading-tight">{{ card.current ?? '—' }}</span>
            <span class="text-[12.5px] text-muted">{{ card.current == null ? '还没有计分的比赛' : '当前' }}</span>
          </div>
          <span class="text-[12.5px] text-muted">
            <template v-if="card.max != null">最高 <span class="num">{{ card.max }}</span> · 计分 <span class="num">{{ card.rated }}</span> 场</template>
            <template v-if="card.unrated">{{ card.max != null ? ' · ' : '' }}<span class="num">{{ card.unrated }}</span> 场不计分</template>
          </span>
        </li>
      </ul>

      <EChart :option="option" />

      <h3 class="m-0 text-sm text-ink font-semibold">最近的比赛</h3>
      <ResponsiveList :items="recent" :columns="columns" row-key="key" class="rating-list">
        <template #cell-contest="{ row }">
          <span class="flex min-w-0 items-center gap-2.5">
            <PlatformLogo :platform="row.platform" :size="20" :title="platformName(row.platform)" />
            <span class="truncate text-[13.5px] text-ink" :title="row.contestName">{{ row.contestName }}</span>
          </span>
        </template>
        <template #cell-place="{ row }"><span class="num">{{ row.place ?? '—' }}</span></template>
        <template #cell-change="{ row }">
          <span v-if="!row.rated" class="text-muted">不计分</span>
          <span v-else-if="delta(row) == null" class="text-muted">—</span>
          <span v-else class="num whitespace-nowrap">
            {{ row.oldRating }} → {{ row.newRating }}
            <span :class="delta(row)! >= 0 ? 'up' : 'down'">{{ deltaText(delta(row)!) }}</span>
          </span>
        </template>
        <template #cell-performance="{ row }"><span class="num">{{ row.performance ?? '—' }}</span></template>
        <template #cell-date="{ row }"><span class="num">{{ dateText(row.finishedAt) }}</span></template>
        <template #card="{ row }">
          <div class="flex flex-col gap-1.5">
            <div class="flex min-w-0 items-center gap-2.5">
              <PlatformLogo :platform="row.platform" :size="22" :title="platformName(row.platform)" />
              <span class="min-w-0 flex-1 truncate text-sm text-ink">{{ row.contestName }}</span>
              <span class="num shrink-0 text-xs text-muted">{{ dateText(row.finishedAt) }}</span>
            </div>
            <div class="flex items-center gap-3 pl-8 text-[13px] text-muted">
              <span v-if="!row.rated">不计分</span>
              <span v-else-if="delta(row) != null" class="num">{{ row.oldRating }} → {{ row.newRating }} <span :class="delta(row)! >= 0 ? 'up' : 'down'">{{ deltaText(delta(row)!) }}</span></span>
              <span v-if="row.place != null">名次 <span class="num">{{ row.place }}</span></span>
              <span v-if="row.performance != null">表现分 <span class="num">{{ row.performance }}</span></span>
            </div>
          </div>
        </template>
      </ResponsiveList>
    </template>
  </section>
</template>

<style scoped>
.rating-card {
  display: flex;
  flex-direction: column;
  gap: 14px;
  padding: 16px;
  border: 1px solid var(--border);
  border-radius: var(--radius);
  background: var(--bg-elevated);
}

@media (min-width: 768px) {
  .rating-card {
    padding: 20px 22px;
  }
}

.stat {
  display: flex;
  flex-direction: column;
  gap: 6px;
  min-width: 0;
  padding: 12px 14px;
  border: 1px solid var(--border-soft);
  border-radius: 12px;
  background: var(--bg);
}

.up {
  color: var(--success-ink);
  font-weight: 600;
}

.down {
  color: var(--danger-ink);
  font-weight: 600;
}
</style>
