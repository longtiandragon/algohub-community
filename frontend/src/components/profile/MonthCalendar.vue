<script setup lang="ts">
import { computed, ref, watch } from 'vue'
import { get } from '@/api/client'
import type { HeatmapData } from '@/api/types'
import PlatformLogo from '@/components/PlatformLogo.vue'
import Skeleton from '@/components/ui/Skeleton.vue'
import { HEAT_LEGEND, heatLevel, heatmapWindow, heatRange, metricAmount, metricLabel, metricTotal } from '@/utils/dailyMetric'
import { platformName } from '@/utils/platformBrand'
import { problemCodeBare } from '@/utils/problemLabel'
import { beijingClock, campusDate } from '@/utils/statistics'

/**
 * 手机上的每日记录：一个月一张日历（周一开头），用热力图五档按固定阈值上色（和电脑的格子一样：0 / 1 / 2～3 / 4～5 / 6 及以上）；
 * 今天描品牌色的边，选中的一天外面一圈深色；今天以后的日子是虚线框，别的月份的日子留空。
 * ‹ › 切月，范围和电脑的格子一样是近一年（第一列所在的月份到这个月），用页面读的同一份数据。
 * 点有记录的一天，下面列出当天通过的题（同一题只列一次）；「看这一天的全部提交」去做题记录。
 */
const props = defineProps<{
  /** 近一年的每日数据（从 heatmapWindow().firstMonth 的 1 号读起） */
  data: HeatmapData | null
  platform: string
  metric: string
  userId?: number
  loading?: boolean
  error?: string
}>()

const emit = defineEmits<{
  'update:platform': [value: string]
  /** 去「做题记录」看这一天 */
  open: [date: string]
  retry: []
}>()

interface DayRow {
  id: number
  platform: string
  title: string | null
  problemKey: string | null
  submittedAt: string | null
}
type Cell =
  | { key: string; blank: true }
  | { key: string; blank: false; date: string; day: number; value: number; future: boolean; level: number }

const today = campusDate()
const THIS_MONTH = today.slice(0, 7)
const FIRST_MONTH = heatmapWindow(today).firstMonth
const month = ref(THIS_MONTH)

function shiftMonth(value: string, delta: number) {
  const [y, m] = value.split('-').map(Number)
  const index = y * 12 + m - 1 + delta
  return `${Math.floor(index / 12)}-${String((index % 12) + 1).padStart(2, '0')}`
}
const canPrev = computed(() => month.value > FIRST_MONTH)
const canNext = computed(() => month.value < THIS_MONTH)
/** 数据到了没有（换平台时保留旧数据，不算没到） */
const ready = computed(() => !!props.data)
const monthDays = computed(() => {
  const [y, m] = month.value.split('-').map(Number)
  const values = ready.value ? props.data!.metrics[props.metric] ?? {} : {}
  const count = new Date(Date.UTC(y, m, 0)).getUTCDate()
  return Array.from({ length: count }, (_, i) => {
    const date = `${month.value}-${String(i + 1).padStart(2, '0')}`
    return { date, day: i + 1, value: date <= today ? values[date] ?? 0 : 0, future: date > today }
  })
})
const activeDays = computed(() => monthDays.value.filter((d) => d.value > 0))
const total = computed(() => activeDays.value.reduce((sum, d) => sum + d.value, 0))
const cells = computed<Cell[]>(() => {
  const offset = (new Date(`${month.value}-01T00:00:00Z`).getUTCDay() + 6) % 7
  const trailing = (7 - ((offset + monthDays.value.length) % 7)) % 7
  return [
    ...Array.from({ length: offset }, (_, i) => ({ key: `lead-${i}`, blank: true as const })),
    ...monthDays.value.map((d) => ({
      ...d,
      key: d.date,
      blank: false as const,
      level: heatLevel(d.value)
    })),
    ...Array.from({ length: trailing }, (_, i) => ({ key: `tail-${i}`, blank: true as const }))
  ]
})

const title = computed(() => `${month.value.slice(0, 4)} 年 ${Number(month.value.slice(5))} 月`)
const summary = computed(() => {
  if (!ready.value) return '读取中…'
  return activeDays.value.length ? `${activeDays.value.length} 天有记录 · ${metricTotal(total.value)}` : '这个月还没有记录'
})
function dayLabel(date: string) {
  const weekday = '日一二三四五六'[new Date(`${date}T00:00:00Z`).getUTCDay()]
  return `${Number(date.slice(5, 7))} 月 ${Number(date.slice(8, 10))} 日 周${weekday}`
}

/** 选中的一天：默认今天（有记录的话），否则这个月最近有记录的一天 */
const selected = ref('')
watch(
  activeDays,
  (days) => {
    if (days.some((d) => d.date === selected.value)) return
    selected.value = days.at(-1)?.date ?? ''
  },
  { immediate: true }
)
const selectedValue = computed(() => activeDays.value.find((d) => d.date === selected.value)?.value ?? 0)

/** 当天通过的题：按时间从晚到早，同一题只列一次 */
const dayRows = ref<DayRow[]>([])
const dayLoading = ref(false)
const dayError = ref('')
let dayRequest = 0
watch(
  [selected, () => props.platform, () => props.metric],
  async ([date]) => {
    const request = ++dayRequest
    dayRows.value = []
    dayError.value = ''
    if (!date) {
      dayLoading.value = false
      return
    }
    dayLoading.value = true
    try {
      const data = await get<{ rows: DayRow[] }>(props.userId ? `/history/user/${props.userId}` : '/history', { platform: props.platform, accepted: true, from: date, to: date, page: 1, size: 50 })
      if (request !== dayRequest) return
      const seen = new Set<string>()
      dayRows.value = data.rows.filter((row) => {
        const key = `${row.platform}:${row.problemKey}`
        if (seen.has(key)) return false
        seen.add(key)
        return true
      })
    } catch {
      if (request === dayRequest) dayError.value = '这一天的题没读到，稍后再试'
    } finally {
      if (request === dayRequest) dayLoading.value = false
    }
  },
  { immediate: true }
)
const DAY_LIMIT = 6
const label = (row: DayRow) => problemCodeBare({ platform: row.platform, problemKey: row.problemKey ?? '', title: row.title })
</script>

<template>
  <section class="calendar" data-test="month-calendar">
    <div class="flex items-center justify-between gap-2">
      <button type="button" class="nav" aria-label="上个月" :disabled="!canPrev" @click="month = shiftMonth(month, -1)">
        <span class="i-lucide-chevron-left text-lg" aria-hidden="true" />
      </button>
      <div class="min-w-0 flex flex-col items-center" aria-live="polite">
        <h2 class="num m-0 text-base text-ink font-semibold" data-test="calendar-title">{{ title }}</h2>
        <span class="text-xs text-muted" data-test="calendar-summary">{{ summary }}</span>
      </div>
      <button type="button" class="nav" aria-label="下个月" :disabled="!canNext" @click="month = shiftMonth(month, 1)">
        <span class="i-lucide-chevron-right text-lg" aria-hidden="true" />
      </button>
    </div>

    <p v-if="platform || metric !== 'FIRST_AC'" class="m-0 flex flex-wrap items-center justify-center gap-x-2 text-xs text-muted">
      {{ platform ? `只看${platformName(platform)} · ` : '' }}{{ metricLabel(metric) }}
      <button v-if="platform" type="button" class="link" @click="emit('update:platform', '')">看全部平台</button>
    </p>

    <div v-if="error" class="flex items-center justify-center gap-3 text-[13px] text-danger-ink">
      {{ error }}<el-button size="small" @click="emit('retry')">重试</el-button>
    </div>

    <div class="grid grid-cols-7 gap-1 text-center text-xs text-muted" aria-hidden="true">
      <span v-for="weekday in ['一', '二', '三', '四', '五', '六', '日']" :key="weekday">{{ weekday }}</span>
    </div>
    <div class="grid grid-cols-7 gap-1" :class="{ 'opacity-60': loading || !ready }" role="group" :aria-label="`${title}每天的记录`">
      <template v-for="cell in cells" :key="cell.key">
        <span v-if="cell.blank" class="day" aria-hidden="true" />
        <button
          v-else-if="cell.value > 0"
          type="button"
          class="day num has-value"
          :class="[`lv-${cell.level}`, { today: cell.date === today, selected: cell.date === selected }]"
          :data-date="cell.date"
          :aria-label="`${dayLabel(cell.date)}，${metricAmount(cell.value)}`"
          :aria-pressed="cell.date === selected"
          @click="selected = cell.date"
        >
          {{ cell.day }}
        </button>
        <span
          v-else
          class="day num"
          :class="cell.future ? 'future' : ['lv-0', { today: cell.date === today }]"
          :data-date="cell.date"
          :title="cell.future ? undefined : `${dayLabel(cell.date)}：没有记录`"
        >{{ cell.day }}</span>
      </template>
    </div>

    <ul class="m-0 flex list-none items-center justify-end gap-2.5 p-0 text-xs text-muted" aria-label="颜色分档">
      <li v-for="(label, level) in HEAT_LEGEND" :key="level" class="flex items-center gap-1" :title="heatRange(level)" :aria-label="heatRange(level)">
        <span class="legend" :class="`lv-${level}`" aria-hidden="true" /><span class="num" aria-hidden="true">{{ label }}</span>
      </li>
    </ul>

    <div v-if="selected" class="detail" data-test="calendar-day">
      <h3 class="m-0 mb-1 text-sm text-ink font-semibold">{{ dayLabel(selected) }} · {{ metricAmount(selectedValue) }}</h3>
      <div v-if="dayLoading" class="flex flex-col gap-2 py-2">
        <Skeleton class="h-4 w-3/4" />
        <Skeleton class="h-4 w-1/2" />
      </div>
      <p v-else-if="dayError" class="m-0 py-2 text-[13px] text-danger-ink">{{ dayError }}</p>
      <p v-else-if="!dayRows.length" class="m-0 py-2 text-[13px] text-muted">这一天没有能列出的通过记录（可能只有提交、没有通过）。</p>
      <ul v-else class="m-0 list-none p-0">
        <li v-for="row in dayRows.slice(0, DAY_LIMIT)" :key="row.id" class="day-row">
          <PlatformLogo :platform="row.platform" :size="22" :title="platformName(row.platform)" />
          <span class="min-w-0 flex flex-1 flex-col">
            <span class="truncate text-sm text-ink">{{ label(row).name || row.problemKey }}</span>
            <span class="mono text-[11.5px] text-muted">{{ label(row).code }}</span>
          </span>
          <span v-if="row.submittedAt" class="num text-[12.5px] text-muted">{{ beijingClock(row.submittedAt) }}</span>
        </li>
      </ul>
      <p v-if="dayRows.length > DAY_LIMIT" class="m-0 pt-1 text-xs text-muted">还有 {{ dayRows.length - DAY_LIMIT }} 道</p>
      <button type="button" class="link mt-1.5 self-start text-[13.5px]" @click="emit('open', selected)">看这一天的全部提交 →</button>
    </div>
    <p v-else-if="ready && !error" class="m-0 border-t border-t-solid border-line-soft pt-3 text-[13px] text-muted leading-relaxed">
      这个月还没有已同步的记录；不代表源站没有做题记录。
    </p>
  </section>
</template>

<style scoped>
.calendar {
  display: flex;
  flex-direction: column;
  gap: 10px;
  padding: 16px;
  border: 1px solid var(--border);
  border-radius: var(--radius);
  background: var(--bg-elevated);
}

.nav {
  display: flex;
  flex-shrink: 0;
  align-items: center;
  justify-content: center;
  width: 36px;
  height: 36px;
  padding: 0;
  border: 0;
  border-radius: 9px;
  background: var(--bg);
  color: var(--text-strong);
  cursor: pointer;
}

.nav:disabled {
  color: var(--text-faint);
  cursor: default;
}

.nav:focus-visible,
.day:focus-visible,
.link:focus-visible {
  outline: 2px solid var(--brand-500);
  outline-offset: 2px;
}

.day {
  display: flex;
  box-sizing: border-box;
  align-items: center;
  justify-content: center;
  height: 40px;
  padding: 0;
  border: 0;
  border-radius: 9px;
  background: transparent;
  color: var(--text-strong);
  font-size: 13.5px;
}

.day.has-value {
  font-weight: 600;
  cursor: pointer;
}

.legend {
  display: block;
  width: 12px;
  height: 12px;
  border-radius: 3px;
}

.lv-0 { background: var(--heat-0); }
.lv-1 { background: var(--heat-1); }
.lv-2 { background: var(--heat-2); }
.lv-3 { background: var(--heat-3); }
.lv-4 { background: var(--heat-4); color: #fff; }

/* 深色下第 3、4 档是亮绿，字用深色才看得清（范围标记只加在最后的 .day 上） */
html[data-theme='dark'] .day.lv-3,
html[data-theme='dark'] .day.lv-4 {
  color: var(--bg);
}

.day.future {
  border: 1px dashed var(--border);
  color: var(--text-muted);
}

.day.today {
  box-shadow: inset 0 0 0 2px var(--brand-500);
}

.day.selected {
  box-shadow: 0 0 0 2px var(--text-strong);
}

.day.today.selected {
  box-shadow: inset 0 0 0 2px var(--brand-500), 0 0 0 2px var(--text-strong);
}

.detail {
  display: flex;
  flex-direction: column;
  gap: 2px;
  margin-top: 6px;
  padding-top: 12px;
  border-top: 1px solid var(--border-soft);
}

.day-row {
  display: flex;
  align-items: center;
  gap: 10px;
  min-height: 46px;
  border-top: 1px solid var(--border-soft);
}

.link {
  padding: 0;
  border: 0;
  background: none;
  color: var(--brand-600);
  font: inherit;
  cursor: pointer;
}
</style>
