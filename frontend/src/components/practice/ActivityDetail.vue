<script setup lang="ts">
import DifficultyTag from '@/components/DifficultyTag.vue'
import StatusBadge from '@/components/ui/StatusBadge.vue'
import UserAvatar from '@/components/ui/UserAvatar.vue'
import { avatarTone } from '@/utils/avatar'
import { problemCodeBare } from '@/utils/problemLabel'
import { dayTime } from '@/utils/statistics'
import { activityRange, httpUrl, type Activity, type ActivityProblemStatus } from './types'

/** 活动详情：起止时间、进度口径、题目（每题在活动里算不算）、活动榜（我高亮）。外面由页面包成抽屉或底部弹层。 */
defineProps<{ activity: Activity; meId: number | undefined }>()

const STATUS: Record<ActivityProblemStatus, { label: string; tone: 'success' | 'warning' | 'neutral' | 'info' }> = {
  SOLVED_DURING: { label: '活动期间通过', tone: 'success' },
  SOLVED_BEFORE: { label: '活动前已通过', tone: 'info' },
  SOLVED_AFTER: { label: '活动结束后通过', tone: 'warning' },
  SOLVED_TIME_UNKNOWN: { label: '通过时间未知', tone: 'warning' },
  TODO: { label: '还没通过', tone: 'neutral' }
}
</script>

<template>
  <div class="flex flex-col gap-6" data-test="activity-detail">
    <div class="flex flex-col gap-1.5">
      <p class="num m-0 text-[13.5px] text-body">{{ activityRange(activity.startAt, activity.endAt) }}</p>
      <p v-if="activity.listTitle" class="m-0 text-[13px] text-muted">
        题单：<RouterLink v-if="activity.listId" :to="`/problem-lists/${activity.listId}`" class="text-brand-600 hover:underline">{{ activity.listTitle }}</RouterLink>
        <template v-else>{{ activity.listTitle }}</template>
      </p>
      <p v-if="activity.description" class="m-0 text-sm text-body leading-[1.7] [overflow-wrap:anywhere]">{{ activity.description }}</p>
      <p v-if="activity.progressNote" class="m-0 text-xs text-muted leading-[1.6]">{{ activity.progressNote }}</p>
    </div>

    <section v-if="activity.problems?.length" class="flex flex-col gap-2" aria-labelledby="act-problems">
      <h3 id="act-problems" class="m-0 text-sm text-ink font-semibold">题目<span class="ml-1 text-xs text-muted font-normal">{{ activity.problems.length }} 道</span></h3>
      <ol class="m-0 list-none border border-line-soft rounded-control border-solid p-0">
        <li v-for="p in activity.problems" :key="p.id" class="act-row">
          <span class="num w-5 shrink-0 text-right text-xs text-muted">{{ p.position }}</span>
          <span class="shrink-0 text-[13px] text-brand-600 font-mono">{{ problemCodeBare(p).code || p.problemKey }}</span>
          <a v-if="httpUrl(p.url)" :href="httpUrl(p.url)" target="_blank" rel="noopener noreferrer" class="min-w-0 flex-1 truncate text-sm text-ink hover:text-brand-600">{{ problemCodeBare(p).name }}</a>
          <span v-else class="min-w-0 flex-1 truncate text-sm text-ink">{{ problemCodeBare(p).name }}</span>
          <DifficultyTag class="hidden md:inline-flex" :platform="p.platform" :value="p.difficultyValue" :label="p.difficultyLabel" />
          <StatusBadge :tone="STATUS[p.status].tone" class="shrink-0">{{ STATUS[p.status].label }}</StatusBadge>
        </li>
      </ol>
    </section>

    <section class="flex flex-col gap-2" aria-labelledby="act-board">
      <h3 id="act-board" class="m-0 text-sm text-ink font-semibold">活动榜</h3>
      <p class="m-0 text-xs text-muted">只数活动期间通过的题；关了校内展示的同学不在榜上。</p>
      <p v-if="!activity.leaderboard?.length" class="m-0 py-2 text-[13px] text-muted">活动期间还没有人通过题单里的题。</p>
      <ol v-else class="m-0 flex list-none flex-col p-0">
        <li v-for="(row, i) in activity.leaderboard" :key="row.userId" class="flex items-center gap-2.5 rounded-[9px] px-2 py-1.5" :class="{ 'bg-brand-soft': row.userId === meId }">
          <span class="num w-5 shrink-0 text-[13.5px] text-muted font-semibold">{{ i + 1 }}</span>
          <UserAvatar :src="row.avatarUrl" :name="row.displayName" class="size-7 flex shrink-0 items-center justify-center rounded-full text-xs font-semibold" :class="row.userId === meId ? 'bg-brand-btn text-white' : avatarTone(row.userId)" />
          <span class="min-w-0 flex flex-1 flex-col">
            <span class="truncate text-sm text-ink">{{ row.displayName }}<template v-if="row.userId === meId">（我）</template></span>
            <span v-if="row.lastSolvedAt" class="text-xs text-muted">最近通过 {{ dayTime(row.lastSolvedAt) }}</span>
          </span>
          <span class="num shrink-0 text-[13.5px] text-ink font-semibold">{{ row.solvedCount }} 题</span>
        </li>
      </ol>
    </section>

    <p v-if="activity.note" class="m-0 text-xs text-muted leading-[1.6]">{{ activity.note }}</p>
  </div>
</template>

<style scoped>
.act-row {
  display: flex;
  align-items: center;
  gap: 10px;
  min-height: 44px;
  padding: 4px 12px 4px 8px;
  border-bottom: 1px solid var(--border-soft);
}

.act-row:last-child {
  border-bottom: 0;
}
</style>
