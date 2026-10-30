<script setup lang="ts">
import { computed } from 'vue'
import InfoTip from '@/components/ui/InfoTip.vue'
import { campusDate, shiftDate } from '@/utils/statistics'

/**
 * 电脑右栏「我的连续练习」：最近 7 天每天一个圆点（有首次通过就是实心，今天在最右），
 * 下面写已连续几天、最长几天、今天通过几题。按北京时间自然日，只算能确认通过时间的记录。
 */
const props = defineProps<{
  streak: { currentStreak: number; longestStreak: number; solvedToday: number } | null
  /** 最近 7 天每天的首次通过题数（键是 YYYY-MM-DD） */
  days: Record<string, number> | null
}>()

const dots = computed(() => {
  const today = campusDate()
  return Array.from({ length: 7 }, (_, i) => {
    const date = shiftDate(today, i - 6)
    const value = props.days?.[date] ?? 0
    return { date, value, today: date === today, weekday: date === today ? '今' : '日一二三四五六'[new Date(`${date}T00:00:00Z`).getUTCDay()] }
  })
})
const label = (dot: { date: string; value: number }) => `${Number(dot.date.slice(5, 7))} 月 ${Number(dot.date.slice(8))} 日：${dot.value ? `通过 ${dot.value} 题` : '没有记录'}`
</script>

<template>
  <section class="side-card" data-test="streak-card">
    <h2 class="m-0 flex items-center gap-1 text-[15px] text-ink font-semibold">
      我的连续练习
      <InfoTip title="怎么算">按北京时间自然日算，有能确认时间的首次通过就算练了；今天还没通过时从昨天往前数，不会一早就断。账号同步后才计入。</InfoTip>
    </h2>
    <div class="flex items-baseline gap-1.5">
      <span class="num text-[28px] text-ink font-semibold leading-none">{{ streak?.currentStreak ?? '—' }}</span>
      <span class="text-[13px] text-muted">天连续</span>
    </div>
    <ol class="m-0 grid grid-cols-7 list-none gap-1 p-0" aria-label="最近 7 天">
      <li v-for="dot in dots" :key="dot.date" class="flex flex-col items-center gap-1">
        <span class="dot" :class="{ 'is-on': dot.value > 0, 'is-today': dot.today }" role="img" :aria-label="label(dot)" :title="label(dot)" />
        <span class="text-[11px]" :class="dot.today ? 'text-ink font-semibold' : 'text-muted'" aria-hidden="true">{{ dot.weekday }}</span>
      </li>
    </ol>
    <p class="m-0 text-[12.5px] text-muted">
      最长 <span class="num">{{ streak?.longestStreak ?? '—' }}</span> 天 · 今天通过 <span class="num">{{ streak?.solvedToday ?? '—' }}</span> 题
    </p>
  </section>
</template>

<style scoped>
.side-card {
  display: flex;
  flex-direction: column;
  gap: 12px;
  padding: 18px 20px;
  border: 1px solid var(--border);
  border-radius: var(--radius);
  background: var(--bg-elevated);
}

.dot {
  width: 22px;
  height: 22px;
  box-sizing: border-box;
  border: 2px solid var(--border);
  border-radius: 50%;
  background: transparent;
}

.dot.is-on {
  border-color: var(--brand-500);
  background: var(--brand-500);
}

.dot.is-today:not(.is-on) {
  border-style: dashed;
  border-color: var(--brand-500);
}
</style>
