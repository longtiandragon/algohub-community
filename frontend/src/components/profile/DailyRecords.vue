<script setup lang="ts">
import { computed, nextTick, onBeforeUnmount, onMounted, ref, watch } from 'vue'
import type { HeatmapData } from '@/api/types'
import InfoTip from '@/components/ui/InfoTip.vue'
import { HEAT_LEGEND, heatLevel, heatmapWindow, heatRange, metricAmount } from '@/utils/dailyMetric'
import { platformName } from '@/utils/platformBrand'
import { campusDate, shiftDate } from '@/utils/statistics'

/**
 * 每日记录：近一年按周排成格子（一行是星期几，一列是一周），今天所在那周是最后一列，今天以后不画。
 * 颜色按固定阈值分五档（0 / 1 / 2～3 / 4～5 / 6 及以上），配色用设计系统的热力图五档。
 * 点有记录的一天发出 select（页面跳到「做题记录」并筛到那天）；有记录的日子是按钮，
 * 左右方向键在这些日子之间移动，Home / End 到第一天、最后一天。窄屏横向滚动，默认滚到最近。
 */
const props = defineProps<{
  /** 近一年的每日数据（可以比格子早几天开始） */
  data: HeatmapData | null
  platform: string
  metric: string
  /** 平台筛选的选项（已绑定的平台） */
  platforms: string[]
  selectedDate: string
  loading?: boolean
  error?: string
}>()

const emit = defineEmits<{
  'update:platform': [value: string]
  select: [date: string]
  retry: []
}>()

const today = campusDate()
const range = heatmapWindow(today)
const WEEKDAYS = ['一', '', '三', '', '五', '', '日']

const values = computed<Record<string, number>>(() => props.data?.metrics[props.metric] ?? {})

/** 一列一周（周一开头），从 range.from 排到今天所在那周；月份标在含这个月 1 号的那列 */
const weeks = computed(() => {
  const result: { key: string; month: string; days: { date: string; future: boolean; value: number; level: number }[] }[] = []
  for (let start = range.from; start <= today; start = shiftDate(start, 7)) {
    let month = ''
    const days = Array.from({ length: 7 }, (_, i) => {
      const date = shiftDate(start, i)
      const future = date > today
      if (!future && date.endsWith('-01')) month = `${Number(date.slice(5, 7))}月`
      const value = future ? 0 : values.value[date] ?? 0
      return { date, future, value, level: heatLevel(value) }
    })
    result.push({ key: start, month, days })
  }
  return result
})

/** 有记录的日子（按日期排），键盘在它们之间移动 */
const activeDates = computed(() =>
  Object.entries(values.value)
    .filter(([date, value]) => value > 0 && date >= range.from && date <= today)
    .map(([date]) => date)
    .sort()
)
const focusDate = computed(() => (activeDates.value.includes(props.selectedDate) ? props.selectedDate : activeDates.value.at(-1) ?? ''))

const unitText = (value: number) => metricAmount(value)
/** 跨了两个年份，悬停时带上年 */
function dayLabel(date: string) {
  const weekday = '日一二三四五六'[new Date(`${date}T00:00:00Z`).getUTCDay()]
  return `${date.slice(0, 4)} 年 ${Number(date.slice(5, 7))} 月 ${Number(date.slice(8, 10))} 日 周${weekday}`
}

const root = ref<HTMLElement | null>(null)
const scroller = ref<HTMLElement | null>(null)
function focusCell(date: string) {
  root.value?.querySelector<HTMLButtonElement>(`button[data-date="${date}"]`)?.focus()
}
function onKey(event: KeyboardEvent, date: string) {
  const list = activeDates.value
  const index = list.indexOf(date)
  let next = -1
  if (event.key === 'ArrowRight' || event.key === 'ArrowDown') next = Math.min(list.length - 1, index + 1)
  else if (event.key === 'ArrowLeft' || event.key === 'ArrowUp') next = Math.max(0, index - 1)
  else if (event.key === 'Home') next = 0
  else if (event.key === 'End') next = list.length - 1
  if (next < 0) return
  event.preventDefault()
  focusCell(list[next])
}

/**
 * 窄屏上滚到最右（最近几个月）。自己往左滚过就不再自动滚；
 * 没滚过时窗口变宽变窄（转屏、拖窗口）也保持在最右，不然浏览器按新宽度截断滚动位置，会停在半路。
 */
let pinned = true
function pinToEnd() {
  const el = scroller.value
  if (el && pinned) el.scrollLeft = el.scrollWidth
}
function onScroll() {
  const el = scroller.value
  if (el) pinned = el.scrollLeft + el.clientWidth >= el.scrollWidth - 4
}
watch(
  () => props.data,
  async () => {
    await nextTick()
    pinToEnd()
  },
  { immediate: true }
)
let resizeObserver: ResizeObserver | null = null
onMounted(() => {
  if (!scroller.value || typeof ResizeObserver === 'undefined') return
  resizeObserver = new ResizeObserver(pinToEnd)
  resizeObserver.observe(scroller.value)
})
onBeforeUnmount(() => resizeObserver?.disconnect())

const selectedValue = computed(() => values.value[props.selectedDate] ?? 0)
const hasPoints = computed(() => activeDates.value.length > 0)
</script>

<template>
  <section ref="root" class="records-card" data-test="daily-records">
    <!-- 指标固定为首次通过题数，只留平台筛选；标题和下拉在一行放得下（1280 起）才并排 -->
    <div class="flex flex-col gap-3 xl:(flex-row items-center justify-between)">
      <div class="min-w-0 flex flex-wrap items-baseline gap-x-2.5 gap-y-1">
        <h2 class="m-0 flex items-center gap-1 text-base text-ink font-semibold">
          每日记录
          <InfoTip title="每日记录怎么算">
            <p v-if="data?.metricNotes[metric]" class="m-0 mb-1.5">{{ data.metricNotes[metric] }}</p>
            <p class="m-0">按北京时间划分自然日，原始时间以 UTC 保存。只算有逐条历史、能确认通过时间的题。</p>
          </InfoTip>
        </h2>
        <span class="text-[13px] text-muted">近一年 · 点有记录的一天，跳到「做题记录」看那天的题</span>
      </div>
      <div class="flex flex-wrap gap-2">
        <!-- 空字符串是「全部平台」这个选项本身，不当成没选 -->
        <el-select :model-value="platform" :empty-values="[null, undefined]" class="!w-32" aria-label="平台" @update:model-value="emit('update:platform', $event)">
          <el-option label="全部平台" value="" />
          <el-option v-for="p in platforms" :key="p" :label="platformName(p)" :value="p" />
        </el-select>
        <span class="self-center text-[13px] text-muted">首次通过题数</span>
      </div>
    </div>

    <div v-if="error" class="flex items-center gap-3 text-[13px] text-danger-ink">
      {{ error }}<el-button size="small" @click="emit('retry')">重试</el-button>
    </div>

    <div ref="scroller" class="grid-scroll" :class="{ 'opacity-60': loading }" :style="{ '--weeks': weeks.length || 53 }" :aria-busy="loading ? 'true' : undefined" @scroll.passive="onScroll">
      <div class="heatmap" role="group" aria-label="近一年每日记录">
        <div class="weekdays" aria-hidden="true">
          <span v-for="(label, i) in WEEKDAYS" :key="i">{{ label }}</span>
        </div>
        <div v-for="week in weeks" :key="week.key" class="week">
          <span class="month" aria-hidden="true">{{ week.month }}</span>
          <template v-for="day in week.days" :key="day.date">
            <button
              v-if="day.value > 0"
              type="button"
              class="cell"
              :class="[`lv-${day.level}`, { selected: day.date === selectedDate }]"
              :data-date="day.date"
              :tabindex="day.date === focusDate ? 0 : -1"
              :title="`${dayLabel(day.date)}：${unitText(day.value)}`"
              :aria-label="`${dayLabel(day.date)}，${unitText(day.value)}`"
              :aria-pressed="day.date === selectedDate"
              @click="emit('select', day.date)"
              @keydown="onKey($event, day.date)"
            />
            <span
              v-else
              class="cell"
              :class="day.future ? 'future' : 'lv-0'"
              :title="day.future ? undefined : `${dayLabel(day.date)}：没有记录`"
            />
          </template>
        </div>
      </div>
    </div>

    <div class="flex flex-wrap items-center justify-between gap-2 text-xs text-muted">
      <span v-if="selectedDate && selectedValue">{{ dayLabel(selectedDate) }} · {{ unitText(selectedValue) }}（已选中）</span>
      <span v-else-if="data && !hasPoints">近一年这个指标还没有已同步的记录；不代表源站没有做题记录</span>
      <span v-else>颜色越深，当天越多</span>
      <!-- 图例写出每档从几开始，悬停看这一档的范围 -->
      <ul class="m-0 flex list-none items-center gap-2.5 p-0" aria-label="颜色分档" data-test="heat-legend">
        <li v-for="(label, level) in HEAT_LEGEND" :key="level" class="flex items-center gap-1" :title="heatRange(level)" :aria-label="heatRange(level)">
          <span class="legend" :class="`lv-${level}`" aria-hidden="true" /><span class="num" aria-hidden="true">{{ label }}</span>
        </li>
      </ul>
    </div>
  </section>
</template>

<style scoped>
.records-card {
  display: flex;
  flex-direction: column;
  gap: 14px;
  padding: 16px;
  border: 1px solid var(--border);
  border-radius: var(--radius);
  background: var(--bg-elevated);
}

@media (min-width: 768px) {
  .records-card {
    padding: 20px 22px;
  }
}

.grid-scroll {
  --cell: 14px;
  overflow-x: auto;
  transition: opacity 0.2s;
}

/* 宽屏上格子按卡片宽度放大到铺满（14～18px），窄屏保持 14px 横向滚动 */
@supports (width: 1cqw) {
  .grid-scroll {
    container-type: inline-size;
  }

  .heatmap {
    --cell: clamp(14px, calc((100cqw - 30px) / var(--weeks) - 3px), 18px);
  }
}

.heatmap {
  display: flex;
  gap: 3px;
  width: max-content;
  padding: 2px 2px 4px;
}

.weekdays,
.week {
  display: flex;
  flex-direction: column;
  gap: 3px;
}

.weekdays {
  padding-top: 20px;
  margin-right: 5px;
  color: var(--text-muted);
  font-size: 11px;
}

.weekdays span {
  height: var(--cell);
  line-height: var(--cell);
}

/* 和格子一样宽，文字往右伸到下一列的空位里，不把这一列撑宽 */
.month {
  width: var(--cell);
  height: 17px;
  color: var(--text-muted);
  font-size: 11px;
  line-height: 14px;
  white-space: nowrap;
}

.cell,
.legend {
  display: block;
  width: var(--cell, 14px);
  height: var(--cell, 14px);
  padding: 0;
  border: 0;
  border-radius: 3px;
}

button.cell {
  cursor: pointer;
}

button.cell:hover {
  outline: 1px solid var(--text-muted);
  outline-offset: 1px;
}

button.cell:focus-visible {
  outline: 2px solid var(--brand-500);
  outline-offset: 1px;
}

.cell.selected {
  box-shadow: 0 0 0 2px var(--text-strong);
}

.legend {
  width: 12px;
  height: 12px;
}

.lv-0 { background: var(--heat-0); }
.lv-1 { background: var(--heat-1); }
.lv-2 { background: var(--heat-2); }
.lv-3 { background: var(--heat-3); }
.lv-4 { background: var(--heat-4); }

/* 今天以后的格子留空 */
.future {
  background: transparent;
}
</style>
