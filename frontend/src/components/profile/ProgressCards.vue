<script setup lang="ts">
import { computed, ref } from 'vue'
import type { HeatmapData } from '@/api/types'
import SegmentedControl from '@/components/ui/SegmentedControl.vue'
import Skeleton from '@/components/ui/Skeleton.vue'
import { useBreakpoint } from '@/composables/useBreakpoint'
import { campusDate, shiftDate } from '@/utils/statistics'

/**
 * 每周新增通过（最近 12 周，手机上 8 周；一周从周一算，本周到今天为止）和累计通过（近一年 / 今年 1 月 1 日起）。
 * 只算能确认首次通过时间的记录；没记录的日子不代表没练。两张卡片用每日记录那份近一年数据，不另外读。
 */
const props = defineProps<{
  /** 每日记录用的那份近一年数据（覆盖最近 12 周、今天往前 365 天和今年 1 月 1 日起） */
  data: HeatmapData | null
  loading?: boolean
}>()

const today = campusDate()
const year = today.slice(0, 4)
const monday = shiftDate(today, -((new Date(`${today}T00:00:00Z`).getUTCDay() + 6) % 7))
const monthDay = (date: string) => `${Number(date.slice(5, 7))}/${Number(date.slice(8, 10))}`

const { isMobile } = useBreakpoint()
const weekCount = computed(() => (isMobile.value ? 8 : 12))
/** 最高的柱子多高：电脑 130，手机 96 */
const barScale = computed(() => (isMobile.value ? 96 : 130))

const values = computed(() => props.data?.metrics.FIRST_AC ?? {})
const weeks = computed(() =>
  Array.from({ length: weekCount.value }, (_, i) => {
    const last = weekCount.value - 1
    const start = shiftDate(monday, -7 * (last - i))
    let value = 0
    for (let d = 0; d < 7; d++) {
      const date = shiftDate(start, d)
      if (date > today) break
      value += values.value[date] ?? 0
    }
    return { start, value, current: i === last, label: i === last ? '本周' : i % 2 === 0 ? monthDay(start) : '' }
  })
)
const weekMax = computed(() => Math.max(1, ...weeks.value.map((w) => w.value)))

/** 近一年：今天往前共 365 天（含今天）；今年：当年 1 月 1 日到今天。都按北京时间 */
const mode = ref<'LAST_YEAR' | 'YEAR'>('LAST_YEAR')
const modeOptions = [
  { label: '近一年', value: 'LAST_YEAR' as const },
  { label: `${year} 年`, value: 'YEAR' as const }
]

const cumulative = computed(() => {
  const from = mode.value === 'LAST_YEAR' ? shiftDate(today, -364) : `${year}-01-01`
  const points: { date: string; sum: number }[] = []
  let sum = 0
  for (let date = from; date <= today; date = shiftDate(date, 1)) {
    sum += values.value[date] ?? 0
    points.push({ date, sum })
  }
  return { points, total: sum }
})

const WIDTH = 470
const HEIGHT = 170
const shape = computed(() => {
  const { points, total } = cumulative.value
  const n = Math.max(1, points.length - 1)
  const top = Math.max(1, total)
  const xy = points.map((p, i) => [(i / n) * WIDTH, HEIGHT - 4 - (p.sum / top) * (HEIGHT - 34)])
  const line = xy.map(([x, y]) => `${x.toFixed(1)},${y.toFixed(1)}`).join(' ')
  return { line, area: `0,${HEIGHT} ${line} ${WIDTH},${HEIGHT}` }
})
const xLabels = computed(() => {
  const points = cumulative.value.points
  if (points.length < 2) return points.map((p) => monthDay(p.date))
  return [0, 0.25, 0.5, 0.75, 1].map((f) => monthDay(points[Math.round(f * (points.length - 1))].date))
})
const cumulativeLabel = computed(() => `${mode.value === 'LAST_YEAR' ? '近一年' : `${year} 年`}累计通过 ${cumulative.value.total} 题`)
</script>

<template>
  <div class="grid grid-cols-1 gap-4 lg:grid-cols-2">
    <section class="progress-card" data-test="weekly-card">
      <div class="flex items-center justify-between gap-3">
        <h2 class="m-0 text-base text-ink font-semibold">每周新增通过</h2>
        <span class="text-[12.5px] text-muted">最近 {{ weekCount }} 周</span>
      </div>
      <Skeleton v-if="!data && loading" class="h-[170px]" />
      <template v-else>
        <ol class="bars" :aria-label="`最近 ${weekCount} 周每周新增通过`">
          <li v-for="week in weeks" :key="week.start" class="bar-col" :aria-label="`${monthDay(week.start)} 起一周：${week.value} 题${week.current ? '（本周）' : ''}`">
            <span class="num text-[11.5px] text-body" aria-hidden="true">{{ week.value }}</span>
            <span
              class="bar"
              :class="{ current: week.current }"
              :style="{ height: `${week.value ? Math.max(4, Math.round((week.value / weekMax) * barScale)) : 2}px` }"
              aria-hidden="true"
            />
          </li>
        </ol>
        <div class="flex gap-2" aria-hidden="true">
          <!-- 手机上一列不到 20px，标签不换行，往两边空着的邻列里伸 -->
          <span v-for="week in weeks" :key="week.start" class="num min-w-0 flex flex-1 justify-center whitespace-nowrap text-[11px] text-muted">{{ week.label }}</span>
        </div>
      </template>
    </section>

    <section class="progress-card" data-test="cumulative-card">
      <div class="flex items-center justify-between gap-3">
        <h2 class="m-0 text-base text-ink font-semibold">累计通过</h2>
        <SegmentedControl v-model="mode" :options="modeOptions" label="累计的时间范围" class="small" />
      </div>
      <Skeleton v-if="!data && loading" class="h-[170px]" />
      <template v-else>
        <div class="relative h-[170px] border-b border-b-solid border-line-soft">
          <svg width="100%" height="170" :viewBox="`0 0 ${WIDTH} ${HEIGHT}`" preserveAspectRatio="none" class="block" role="img" :aria-label="cumulativeLabel">
            <path :d="`M0 ${HEIGHT / 4} H${WIDTH} M0 ${HEIGHT / 2} H${WIDTH} M0 ${(HEIGHT * 3) / 4} H${WIDTH}`" class="grid-line" />
            <polygon :points="shape.area" class="area" />
            <polyline :points="shape.line" class="line" vector-effect="non-scaling-stroke" />
          </svg>
          <span class="num total">{{ cumulative.total }} 题</span>
        </div>
        <div class="num flex justify-between text-[11px] text-muted" aria-hidden="true">
          <span v-for="(label, i) in xLabels" :key="i">{{ label }}</span>
        </div>
        <p v-if="!cumulative.total" class="m-0 text-[12.5px] text-muted">这段时间还没有能确认通过时间的记录；不代表没有练习。</p>
      </template>
    </section>
  </div>
</template>

<style scoped>
.progress-card {
  display: flex;
  flex-direction: column;
  gap: 14px;
  min-width: 0;
  padding: 16px;
  border: 1px solid var(--border);
  border-radius: var(--radius);
  background: var(--bg-elevated);
}

@media (min-width: 768px) {
  .progress-card {
    padding: 20px 22px;
  }
}

.bars {
  display: flex;
  align-items: flex-end;
  gap: 8px;
  height: 170px;
  margin: 0;
  padding: 0;
  list-style: none;
  border-bottom: 1px solid var(--border-soft);
}

@media (max-width: 767px) {
  .bars {
    height: 128px;
  }
}

.bar-col {
  display: flex;
  flex: 1;
  flex-direction: column;
  align-items: center;
  gap: 5px;
}

.bar {
  width: 100%;
  border-radius: 5px 5px 2px 2px;
  background: color-mix(in srgb, var(--brand-400) 55%, var(--bg-elevated));
}

.bar.current {
  background: var(--brand-500);
}

.grid-line {
  fill: none;
  stroke: var(--border-soft);
  stroke-width: 1;
}

.area {
  fill: color-mix(in srgb, var(--brand-500) 12%, transparent);
  stroke: none;
}

.line {
  fill: none;
  stroke: var(--brand-500);
  stroke-width: 2.5;
  stroke-linejoin: round;
  stroke-linecap: round;
}

.total {
  position: absolute;
  top: 0;
  right: 0;
  padding: 3px 8px;
  border-radius: 6px;
  background: var(--brand-soft);
  color: var(--brand-600);
  font-size: 13px;
  font-weight: 600;
}

/* 卡片标题旁的分段按钮小一号 */
.small :deep(.segmented-item) {
  height: 28px;
  padding: 0 10px;
  font-size: 12.5px;
}
</style>
