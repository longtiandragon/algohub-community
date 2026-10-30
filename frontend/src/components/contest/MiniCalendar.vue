<script setup lang="ts">
import { computed } from 'vue'
import { campusDate, shiftDate } from '@/utils/statistics'
import type { ContestRow } from './types'

/**
 * 接下来 4 周的小月历（从这周一开始，一行一周）：有比赛的日子下面标点（最多三个点），今天描边，
 * 这周已经过去的日子变淡。有比赛的日子可以点，发出 select（页面滚到那天的比赛）。
 */
const props = defineProps<{ rows: ContestRow[]; now: number }>()
const emit = defineEmits<{ select: [date: string] }>()

const today = computed(() => campusDate(new Date(props.now)))
const counts = computed(() => {
  const map: Record<string, number> = {}
  for (const row of props.rows) {
    const key = campusDate(new Date(row.startAt))
    map[key] = (map[key] ?? 0) + 1
  }
  return map
})
const cells = computed(() => {
  const start = shiftDate(today.value, -((new Date(`${today.value}T00:00:00Z`).getUTCDay() + 6) % 7))
  return Array.from({ length: 28 }, (_, i) => {
    const date = shiftDate(start, i)
    const day = Number(date.slice(8))
    return {
      date,
      label: day === 1 ? `${Number(date.slice(5, 7))}/1` : String(day),
      count: counts.value[date] ?? 0,
      past: date < today.value,
      today: date === today.value
    }
  })
})
const range = computed(() => {
  const first = cells.value[0].date
  const last = cells.value[27].date
  return `${Number(first.slice(5, 7))}/${Number(first.slice(8))} – ${Number(last.slice(5, 7))}/${Number(last.slice(8))}`
})
const dayText = (date: string, count: number) => `${Number(date.slice(5, 7))} 月 ${Number(date.slice(8))} 日，${count ? `${count} 场比赛` : '没有比赛'}`
</script>

<template>
  <section class="side-card" data-test="mini-calendar">
    <div class="flex items-baseline justify-between gap-2">
      <h2 class="m-0 text-[15px] text-ink font-semibold">接下来 4 周</h2>
      <span class="num text-xs text-muted">{{ range }}</span>
    </div>
    <div class="grid grid-cols-7 gap-1 text-center text-[11.5px] text-muted" aria-hidden="true">
      <span v-for="weekday in ['一', '二', '三', '四', '五', '六', '日']" :key="weekday">{{ weekday }}</span>
    </div>
    <div class="grid grid-cols-7 gap-1" role="group" aria-label="接下来 4 周的比赛">
      <template v-for="cell in cells" :key="cell.date">
        <button
          v-if="cell.count && !cell.past"
          type="button"
          class="cell has-contest"
          :class="{ today: cell.today }"
          :data-date="cell.date"
          :aria-label="dayText(cell.date, cell.count)"
          :title="dayText(cell.date, cell.count)"
          @click="emit('select', cell.date)"
        >
          <span class="num">{{ cell.label }}</span>
          <span class="dots" aria-hidden="true"><span v-for="n in Math.min(3, cell.count)" :key="n" /></span>
        </button>
        <span v-else class="cell" :class="{ past: cell.past, today: cell.today }" :data-date="cell.date">
          <span class="num">{{ cell.label }}</span>
          <span class="dots" aria-hidden="true" />
        </span>
      </template>
    </div>
  </section>
</template>

<style scoped>
.side-card {
  display: flex;
  flex-direction: column;
  gap: 10px;
  padding: 18px 20px;
  border: 1px solid var(--border);
  border-radius: var(--radius);
  background: var(--bg-elevated);
}

.cell {
  display: flex;
  flex-direction: column;
  align-items: center;
  justify-content: center;
  gap: 2px;
  height: 40px;
  padding: 0;
  border: 0;
  border-radius: 9px;
  background: transparent;
  color: var(--text-strong);
  font: inherit;
  font-size: 13px;
}

.cell.past {
  color: var(--text-muted);
}

.cell.has-contest {
  background: var(--brand-soft);
  color: var(--brand-600);
  font-weight: 600;
  cursor: pointer;
}

.cell.has-contest:hover {
  background: color-mix(in srgb, var(--brand-500) 18%, var(--bg-elevated));
}

.cell.today {
  box-shadow: inset 0 0 0 2px var(--brand-500);
}

.cell:focus-visible {
  outline: 2px solid var(--brand-500);
  outline-offset: 1px;
}

.dots {
  display: flex;
  gap: 2px;
  height: 4px;
}

.dots > span {
  width: 4px;
  height: 4px;
  border-radius: 50%;
  background: var(--brand-500);
}
</style>
