<script setup lang="ts">
import { computed } from 'vue'
import BookmarkButton from '@/components/BookmarkButton.vue'
import DifficultyTag from '@/components/DifficultyTag.vue'
import PlatformLogo from '@/components/PlatformLogo.vue'
import EmptyState from '@/components/ui/EmptyState.vue'
import Skeleton from '@/components/ui/Skeleton.vue'
import { useBreakpoint } from '@/composables/useBreakpoint'
import UserAvatar from '@/components/ui/UserAvatar.vue'
import { avatarTone } from '@/utils/avatar'
import { platformName } from '@/utils/platformBrand'
import { problemCode } from '@/utils/problemLabel'
import { beijingClock, beijingTime, campusDate, shiftDate } from '@/utils/statistics'
import type { BookmarkMark, FeedItem } from './types'

/**
 * 校内动态的列表：按北京时间的日子分组（「今天 · 9月28日」「9月25日 周五」），日期行吸顶；
 * 每条是头像、「谁 通过了 题号 题名」，下面一行平台图标、难度、时间（今天写「12 分钟前」，别的日子写「22:41」），
 * 时间不是准确首次通过的带一个小标签；自己的那条写「（我）」、底色浅一点。电脑上右边有收藏星。
 * 保持接口返回的顺序，只按日子切分，不再排序。
 */
const props = defineProps<{
  items: FeedItem[]
  marks: Record<number, BookmarkMark>
  meId: number | undefined
  /** 当前时间（页面每分钟更新一次，相对时间跟着变） */
  now: number
  loading: boolean
  loaded: boolean
  hasMore: boolean
  /** 空列表时的说明：全校 / 同届 / 某个平台 */
  emptyText: string
}>()

const emit = defineEmits<{
  more: []
  mark: [problemId: number, value: BookmarkMark]
}>()

const { isMobile } = useBreakpoint()

function dayTitle(key: string, today: string) {
  const [year, month, day] = key.split('-').map(Number)
  const monthDay = `${month}月${day}日`
  if (key === today) return `今天 · ${monthDay}`
  if (key === shiftDate(today, -1)) return `昨天 · ${monthDay}`
  const weekday = '日一二三四五六'[new Date(`${key}T00:00:00Z`).getUTCDay()]
  return `${key.slice(0, 4) === today.slice(0, 4) ? monthDay : `${year}年${monthDay}`} 周${weekday}`
}

const groups = computed(() => {
  const today = campusDate(new Date(props.now))
  const list: { key: string; title: string; items: FeedItem[] }[] = []
  for (const item of props.items) {
    const key = campusDate(new Date(item.acAt))
    let group = list.at(-1)
    if (!group || group.key !== key) {
      group = list.find((g) => g.key === key)
      if (!group) {
        group = { key, title: dayTitle(key, today), items: [] }
        list.push(group)
      }
    }
    group.items.push(item)
  }
  return list
})

/** 今天的写相对时间，别的日子（分组标题已有日期）写「时:分」 */
function timeText(value: string) {
  const at = new Date(value).getTime()
  if (campusDate(new Date(at)) !== campusDate(new Date(props.now))) return beijingClock(value)
  const minutes = Math.floor(Math.max(0, props.now - at) / 60000)
  if (minutes < 1) return '刚刚'
  if (minutes < 60) return `${minutes} 分钟前`
  return `${Math.floor(minutes / 60)} 小时前`
}

const mine = (item: FeedItem) => props.meId !== undefined && item.userId === props.meId
const safeUrl = (url: string | null) => (url && /^https?:\/\//i.test(url) ? url : undefined)
</script>

<template>
  <div class="flex flex-col gap-4" data-test="feed">
    <div v-if="!loaded && loading" class="flex flex-col gap-3" aria-busy="true">
      <div v-for="n in 4" :key="n" class="flex items-start gap-3 border border-line rounded-card border-solid bg-surface p-4">
        <Skeleton class="size-[34px] rounded-full" />
        <div class="flex flex-1 flex-col gap-2">
          <Skeleton class="h-4 w-4/5" />
          <Skeleton class="h-3 w-2/5" />
        </div>
      </div>
    </div>

    <EmptyState v-else-if="loaded && !items.length" icon="i-lucide-activity" title="还没有动态" :description="emptyText" />

    <template v-else>
      <section v-for="group in groups" :key="group.key" class="flex flex-col gap-1.5" data-test="feed-day">
        <div class="day-head">
          <h2 class="m-0 text-[13.5px] text-ink font-semibold">{{ group.title }}</h2>
          <span class="text-xs text-muted">{{ group.items.length }} 条</span>
        </div>
        <ul class="m-0 list-none overflow-hidden border border-line rounded-card border-solid bg-surface p-0">
          <li v-for="item in group.items" :key="item.id" class="feed-item" :class="{ 'is-mine': mine(item), 'is-following': item.following }" data-test="feed-item">
            <RouterLink :to="`/users/${item.userId}`" :aria-label="`查看 ${item.displayName} 的档案`"><UserAvatar :src="item.avatarUrl" :name="item.displayName" class="avatar" :class="mine(item) ? 'bg-brand-btn text-white' : avatarTone(item.userId)" /></RouterLink>
            <div class="min-w-0 flex flex-1 flex-col gap-1.5">
              <p class="m-0 text-sm text-body leading-normal">
                <RouterLink :to="`/users/${item.userId}`" class="text-ink font-semibold no-underline hover:underline">{{ item.displayName || '匿名同学' }}</RouterLink><span v-if="mine(item)" class="text-muted">（我）</span>
                <span v-if="item.following" class="follow-label">已关注</span>
                通过了
                <a v-if="safeUrl(item.url)" :href="safeUrl(item.url)" target="_blank" rel="noopener noreferrer" class="problem">
                  <span class="mono code">{{ problemCode(item).code }}</span> {{ problemCode(item).name }}
                </a>
                <span v-else class="problem"><span class="mono code">{{ problemCode(item).code }}</span> {{ problemCode(item).name }}</span>
              </p>
              <span class="flex flex-wrap items-center gap-2">
                <PlatformLogo :platform="item.platform" :size="23" :title="platformName(item.platform)" />
                <DifficultyTag :platform="item.platform" :value="item.difficultyValue" :label="item.difficultyLabel" />
                <time class="text-xs text-muted" :datetime="item.acAt" :title="`${beijingTime(item.acAt)}（北京时间）`">{{ timeText(item.acAt) }}</time>
                <span v-if="item.firstAcConfidence !== 'EXACT' && item.firstAcConfidence !== 'USER_REPORTED'" class="time-tag" :title="item.timeAccuracy">{{ item.timeTag || '时间待确认' }}</span>
              </span>
            </div>
            <BookmarkButton
              v-if="!isMobile && item.problemId"
              :model-value="marks[item.problemId] ?? null"
              :problem-id="item.problemId"
              :accepted="mine(item)"
              class="shrink-0"
              @update:model-value="emit('mark', item.problemId, $event)"
            />
          </li>
        </ul>
      </section>

      <el-button v-if="hasMore" size="large" class="w-full" :loading="loading" data-test="feed-more" @click="emit('more')">加载更早的动态</el-button>
    </template>
  </div>
</template>

<style scoped>
.follow-label { display: inline-block; margin: 0 5px; padding: 1px 6px; border-radius: 5px; color: var(--brand-600); background: var(--brand-soft); font-size: 11px; font-weight: 600; }
.feed-item.is-following { border-left: 3px solid var(--brand-400); }
/* 日期行吸顶：电脑、平板贴着窗口顶；手机贴着顶栏下沿 */
.day-head {
  position: sticky;
  top: 0;
  z-index: 2;
  display: flex;
  align-items: baseline;
  justify-content: space-between;
  padding: 6px 2px;
  background: var(--bg);
}

@media (max-width: 767px) {
  .day-head {
    top: calc(56px + env(safe-area-inset-top));
  }
}

.feed-item {
  display: flex;
  align-items: flex-start;
  gap: 12px;
  padding: 14px;
}

.feed-item + .feed-item {
  border-top: 1px solid var(--border-soft);
}

.feed-item.is-mine {
  background: color-mix(in srgb, var(--brand-soft) 55%, var(--bg-elevated));
}

.avatar {
  display: flex;
  flex-shrink: 0;
  align-items: center;
  justify-content: center;
  width: 34px;
  height: 34px;
  border-radius: 50%;
  font-size: 13.5px;
  font-weight: 600;
}

.problem {
  color: var(--text-strong);
}

a.problem:hover {
  color: var(--brand-600);
}

.code {
  color: var(--brand-600);
  font-size: 12.5px;
}

.time-tag {
  padding: 0 6px;
  border-radius: var(--radius-xs);
  background: var(--warning-tint);
  color: var(--warning-ink);
  font-size: 11.5px;
  line-height: 20px;
  white-space: nowrap;
  cursor: help;
}
</style>
