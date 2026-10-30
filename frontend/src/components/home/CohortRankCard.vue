<script setup lang="ts">
import { computed } from 'vue'
import type { Ranking } from '@/api/types'
import SectionCard from '@/components/ui/SectionCard.vue'
import Skeleton from '@/components/ui/Skeleton.vue'
import UserAvatar from '@/components/ui/UserAvatar.vue'
import { avatarTone } from '@/utils/avatar'
import { rankContext } from '@/utils/ranking'

/** 同届排行（近 7 天首次通过）：我的名次、离前一名差几题、前后各一名。 */
const props = defineProps<{
  ranking: Ranking | null
  cohort?: number | null
  loading?: boolean
  error?: string | null
}>()
defineEmits<{ retry: [] }>()

const context = computed(() => rankContext(props.ranking))
const scope = computed(() => (props.cohort ? `${props.cohort} 级` : '全校'))
</script>

<template>
  <SectionCard :title="cohort ? '同届排行 · 近 7 天' : '校内排行 · 近 7 天'">
    <template #actions><RouterLink to="/ranking">完整排行 →</RouterLink></template>

    <div v-if="loading" class="flex flex-col gap-3" aria-busy="true">
      <Skeleton class="h-11 w-2/5" />
      <Skeleton class="h-9 w-full" />
      <Skeleton v-for="n in 3" :key="n" class="h-10 w-full" />
    </div>
    <div v-else-if="error" class="flex flex-col items-start gap-2">
      <p class="m-0 text-[13px] text-danger-ink">{{ error }}</p>
      <el-button size="small" @click="$emit('retry')">重试</el-button>
    </div>
    <p v-else-if="!context" class="m-0 text-[13px] text-muted leading-[1.7]">
      {{ ranking?.myRankNote || '这段时间还没有你的排名' }}
    </p>
    <div v-else class="flex flex-col gap-3">
      <div class="flex items-end gap-3">
        <span class="num text-[40px] text-brand-600 font-semibold leading-none md:text-[44px]">#{{ context.me.rank }}</span>
        <span class="flex flex-col gap-0.5 pb-[3px]">
          <span class="num text-sm text-ink">/ {{ ranking!.participantCount }} 人 · {{ context.me.score ?? 0 }} 题</span>
          <span class="text-[12.5px] text-muted">{{ scope }} · 首次通过题数</span>
        </span>
      </div>
      <p class="m-0 rounded-[10px] bg-surface-2 px-3 py-2.5 text-[13px] text-body">
        <template v-if="context.gap">再通过 <strong class="num text-brand-600">{{ context.gap }}</strong> 题就能追平第 {{ context.ahead!.rank }} 名</template>
        <template v-else-if="context.me.rank === 1">你是第 1 名，保持住</template>
        <template v-else>和前一名同分，并列第 {{ context.me.rank }} 名</template>
      </p>
      <ol class="m-0 flex list-none flex-col p-0" aria-label="我和前后的同学">
        <li
          v-for="row in context.around"
          :key="row.userId"
          class="flex h-11 items-center gap-2.5 rounded-[10px] px-2.5 md:h-12"
          :class="{ 'bg-brand-soft': row.userId === context.me.userId }"
        >
          <span class="num w-6 text-sm text-muted font-semibold">{{ row.rank }}</span>
          <UserAvatar
            :src="row.avatarUrl"
            :name="row.displayName"
            class="size-7 flex shrink-0 items-center justify-center rounded-full text-xs font-semibold md:(size-[30px] text-[13px])"
            :class="row.userId === context.me.userId ? 'bg-brand-btn text-white' : avatarTone(row.userId)"
          />
          <span class="min-w-0 flex-1 truncate text-sm text-ink">{{ row.displayName }}</span>
          <span v-if="row.userId === context.me.userId" class="h-5 inline-flex items-center rounded-md bg-brand-btn px-1.5 text-[11px] text-white font-semibold">我</span>
          <span class="num w-9 text-right text-[15px] text-ink font-semibold">{{ row.score ?? 0 }}</span>
        </li>
      </ol>
    </div>
  </SectionCard>
</template>
