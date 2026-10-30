<script setup lang="ts">
import Skeleton from '@/components/ui/Skeleton.vue'
import UserAvatar from '@/components/ui/UserAvatar.vue'
import { avatarTone } from '@/utils/avatar'
import { dayTime } from '@/utils/statistics'
import type { LeaderboardResponse } from './types'

/** 校内完成榜：名次、字标、名字（我高亮）、完成几题；名字下写最近一次通过的时间。 */
defineProps<{ board: LeaderboardResponse | null; total: number; meId: number | undefined; loading: boolean }>()
</script>

<template>
  <section class="flex flex-col gap-1 border border-line rounded-card border-solid bg-surface p-4 md:(px-5 py-[18px])" aria-labelledby="list-board-title" data-test="list-board">
    <div class="mb-2 flex items-center justify-between gap-3">
      <h2 id="list-board-title" class="m-0 text-[15px] text-ink font-semibold">校内完成榜</h2>
      <span v-if="board?.rows.length" class="text-[12.5px] text-muted">{{ board.rows.length }} 位同学在练</span>
    </div>
    <template v-if="loading && !board">
      <Skeleton v-for="i in 4" :key="i" class="my-1 h-9 w-full" />
    </template>
    <p v-else-if="!board?.rows.length" class="m-0 py-3 text-[13px] text-muted">还没有同学在这个题单里通过题目，做第一个吧。</p>
    <ol v-else class="m-0 flex list-none flex-col p-0">
      <li
        v-for="(row, index) in board.rows"
        :key="row.userId"
        class="flex items-center gap-2.5 rounded-[9px] px-2 py-1.5"
        :class="{ 'bg-brand-soft': row.userId === meId }"
      >
        <span class="num w-5 shrink-0 text-[13.5px] text-muted font-semibold">{{ index + 1 }}</span>
        <UserAvatar
          :src="row.avatarUrl"
          :name="row.displayName"
          class="size-7 flex shrink-0 items-center justify-center rounded-full text-xs font-semibold"
          :class="row.userId === meId ? 'bg-brand-btn text-white' : avatarTone(row.userId)"
        />
        <span class="min-w-0 flex flex-1 flex-col">
          <span class="truncate text-sm text-ink">{{ row.displayName || '匿名同学' }}<template v-if="row.userId === meId">（我）</template></span>
          <span v-if="row.lastSolvedAt" class="text-xs text-muted">最近通过 {{ dayTime(row.lastSolvedAt) }}</span>
        </span>
        <span class="num shrink-0 text-[13.5px] text-ink font-semibold">
          {{ row.solvedCount }}<span class="text-muted font-normal"> / {{ board.itemCount || total }}</span>
        </span>
      </li>
    </ol>
    <p v-if="board?.note" class="mb-0 mt-2 text-[12.5px] text-muted leading-[1.6]">{{ board.note }}</p>
  </section>
</template>
