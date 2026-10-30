<script setup lang="ts">
import { computed } from 'vue'
import PlatformLogo from '@/components/PlatformLogo.vue'
import { contestLength, contestTime } from '@/utils/contest'
import { beginnerFriendly } from '@/utils/contestLevel'
import { platformName } from '@/utils/platformBrand'
import { beijingTime, campusDate, shiftDate } from '@/utils/statistics'
import StarIcon from './StarIcon.vue'
import { endOf, phaseOf, type ContestRow } from './types'

/**
 * 比赛列表：按北京时间的日子分组（「今天 · 9月28日」「明天 · 9月29日」「9月30日 周三」，右边写几场），
 * 每场是开始时间、标题（点开去源站）、平台图标 · 时长、适合新手、多久后开始，右边是收藏星。
 * 6 小时内开赛的「多久后」用品牌色加粗，进行中的写还剩多久。每天一组有 id，小月历点某天能滚到这里。
 * 保持接口返回的顺序，只按日子切分。
 */
const props = defineProps<{ rows: ContestRow[]; now: number }>()
const emit = defineEmits<{ favorite: [row: ContestRow] }>()

function dayTitle(key: string, today: string) {
  const [year, month, day] = key.split('-').map(Number)
  const monthDay = `${month}月${day}日`
  if (key === today) return `今天 · ${monthDay}`
  if (key === shiftDate(today, 1)) return `明天 · ${monthDay}`
  if (key === shiftDate(today, -1)) return `昨天 · ${monthDay}`
  const weekday = '日一二三四五六'[new Date(`${key}T00:00:00Z`).getUTCDay()]
  return `${key.slice(0, 4) === today.slice(0, 4) ? monthDay : `${year}年${monthDay}`} 周${weekday}`
}

const days = computed(() => {
  const today = campusDate(new Date(props.now))
  const list: { key: string; title: string; rows: ContestRow[] }[] = []
  for (const row of props.rows) {
    const key = campusDate(new Date(row.startAt))
    let day = list.find((d) => d.key === key)
    if (!day) {
      day = { key, title: dayTitle(key, today), rows: [] }
      list.push(day)
    }
    day.rows.push(row)
  }
  return list
})

/** 1 小时 12 分 */
function span(ms: number) {
  const minutes = Math.max(1, Math.ceil(ms / 60_000))
  const hours = Math.floor(minutes / 60)
  return [hours ? `${hours} 小时` : '', minutes % 60 ? `${minutes % 60} 分` : ''].filter(Boolean).join(' ')
}

/** 多久后开始：45 分钟后 / 3 小时后（今天）/ 明天 / 2 天后；进行中写还剩多久 */
function when(row: ContestRow) {
  const phase = phaseOf(row, props.now)
  if (phase === 'live') {
    const end = endOf(row)
    return end ? `进行中 · 还剩 ${span(end - props.now)}` : '进行中'
  }
  if (phase === 'ended') return '已结束'
  if (phase === 'started') return '已开始'
  const ms = Date.parse(row.startAt) - props.now
  if (ms < 3_600_000) return `${Math.ceil(ms / 60_000)} 分钟后`
  const today = campusDate(new Date(props.now))
  const day = campusDate(new Date(row.startAt))
  if (day === today) return `${Math.floor(ms / 3_600_000)} 小时后`
  const diff = Math.round((Date.parse(`${day}T00:00:00Z`) - Date.parse(`${today}T00:00:00Z`)) / 86_400_000)
  return diff === 1 ? '明天' : `${diff} 天后`
}
function whenTone(row: ContestRow) {
  const phase = phaseOf(row, props.now)
  if (phase === 'live') return 'is-live'
  if (phase !== 'upcoming') return ''
  return Date.parse(row.startAt) - props.now <= 6 * 3_600_000 ? 'is-soon' : ''
}
const safeUrl = (url: string | null) => (url && /^https?:\/\//i.test(url) ? url : undefined)
</script>

<template>
  <div class="flex flex-col gap-4" data-test="contest-list">
    <section v-for="day in days" :id="`contest-day-${day.key}`" :key="day.key" class="day" data-test="contest-day">
      <div class="flex items-baseline justify-between px-0.5">
        <h2 class="m-0 text-[13.5px] text-ink font-semibold">{{ day.title }}</h2>
        <span class="text-xs text-muted">{{ day.rows.length }} 场</span>
      </div>
      <ul class="m-0 list-none overflow-hidden border border-line rounded-card border-solid bg-surface p-0">
        <li v-for="row in day.rows" :key="row.id" class="row" :class="{ 'is-ended': phaseOf(row, now) === 'ended' }" data-test="contest-row">
          <time class="num time" :datetime="row.startAt" :title="`北京时间 ${beijingTime(row.startAt)}`">{{ contestTime(row.startAt) }}</time>
          <div class="min-w-0 flex flex-1 flex-col gap-1.5">
            <a v-if="safeUrl(row.url)" :href="safeUrl(row.url)" target="_blank" rel="noopener noreferrer" class="title">{{ row.title }}</a>
            <span v-else class="title">{{ row.title }}</span>
            <span class="flex flex-wrap items-center gap-x-2 gap-y-1 text-xs text-muted">
              <PlatformLogo :platform="row.platform" :size="16" :title="platformName(row.platform)" />
              <span v-if="row.durationSec">{{ contestLength(row.durationSec) }}</span>
              <span v-if="beginnerFriendly(row)" class="newbie" data-test="newbie">适合新手</span>
              <span class="when" :class="whenTone(row)">{{ when(row) }}</span>
            </span>
          </div>
          <button
            type="button"
            class="star"
            :class="{ 'is-on': row.favorited }"
            :aria-pressed="row.favorited"
            :aria-label="row.favorited ? `取消收藏 ${row.title}` : `收藏 ${row.title}`"
            :title="row.favorited ? '已收藏，点一下取消' : '收藏后可以加入日历'"
            data-test="contest-star"
            @click="emit('favorite', row)"
          >
            <StarIcon :on="row.favorited" />
          </button>
        </li>
      </ul>
    </section>
  </div>
</template>

<style scoped>
.day {
  display: flex;
  flex-direction: column;
  gap: 6px;
  scroll-margin-top: 16px;
}

@media (max-width: 767px) {
  .day {
    scroll-margin-top: calc(64px + env(safe-area-inset-top));
  }
}

.row {
  display: flex;
  align-items: flex-start;
  gap: 12px;
  padding: 14px;
}

.row + .row {
  border-top: 1px solid var(--border-soft);
}

.row.is-ended {
  opacity: 0.6;
}

.time {
  flex-shrink: 0;
  width: 46px;
  padding-top: 1px;
  color: var(--text-strong);
  font-size: 17px;
  font-weight: 600;
}

.title {
  color: var(--text-strong);
  font-size: 14.5px;
  line-height: 1.45;
}

a.title:hover {
  color: var(--brand-600);
}

.newbie {
  display: inline-flex;
  align-items: center;
  height: 19px;
  padding: 0 6px;
  border-radius: 5px;
  background: var(--info-soft);
  color: var(--info-ink);
  font-size: 11px;
  font-weight: 600;
}

.when.is-soon {
  color: var(--brand-600);
  font-weight: 600;
}

.when.is-live {
  color: var(--success-ink);
  font-weight: 600;
}

.star {
  display: flex;
  flex-shrink: 0;
  align-items: center;
  justify-content: center;
  width: 40px;
  height: 40px;
  margin: -8px -8px 0 0;
  padding: 0;
  border: 0;
  border-radius: 10px;
  background: transparent;
  color: var(--text-faint);
  cursor: pointer;
}

.star:hover {
  color: var(--text-muted);
}

.star:focus-visible {
  outline: 2px solid var(--brand-500);
  outline-offset: -2px;
}
</style>
