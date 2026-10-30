<script setup lang="ts">
import { computed } from 'vue'
import type { Ranking } from '@/api/types'
import { rankContext } from '@/utils/ranking'

/**
 * 我的位置：大号名次和参与人数、我的分数；离前一名还差多少（进度条 + 一句话）；电脑上再列前后各一名。
 * 没上榜时写原因（后端按参与资格一条条查出来的），旁边给页面算好的一个动作（去验证、回到我的年级……）。
 */
const props = defineProps<{
  ranking: Ranking
  unit: string
  /** 分数说明，如「近 7 天首次通过」 */
  scoreLabel: string
  /** 手机上的一行范围：「2026 级 · 9/22–9/28 · 首次通过」 */
  scopeLine: string
  compact?: boolean
  action?: { label: string } | null
}>()

const emit = defineEmits<{ action: [] }>()

const context = computed(() => rankContext(props.ranking))
const me = computed(() => props.ranking.myRank)
const myScore = computed(() => me.value?.score ?? me.value?.solved ?? 0)
const aheadScore = computed(() => context.value?.ahead?.score ?? context.value?.ahead?.solved ?? 0)
const progress = computed(() => (aheadScore.value > 0 ? `${Math.min(100, Math.round((myScore.value / aheadScore.value) * 100))}%` : '0%'))
</script>

<template>
  <section class="position" :class="{ compact }" data-test="my-position" data-me-anchor>
    <span v-if="!compact" class="text-[13.5px] text-brand-600 font-semibold">我的位置</span>

    <template v-if="me">
      <div class="flex items-end gap-2.5">
        <span class="num big">#{{ me.rank }}</span>
        <span class="min-w-0 flex flex-col gap-0.5 pb-0.5">
          <span class="num text-sm text-brand-600">/ {{ ranking.participantCount }} 人<template v-if="compact"> · {{ myScore }} {{ unit }}</template></span>
          <span v-if="compact" class="truncate text-xs text-body">{{ scopeLine }}</span>
          <span v-else class="text-[13px] text-body">{{ scoreLabel }} <strong class="num text-ink">{{ myScore }}</strong> {{ unit }}</span>
        </span>
      </div>

      <div v-if="context?.ahead && context.gap" class="gap-box">
        <span v-if="!compact" class="flex justify-between text-[12.5px] text-body">
          <span>距第 {{ context.ahead.rank }} 名</span>
          <span class="num">{{ myScore }} / {{ aheadScore }} {{ unit }}</span>
        </span>
        <span class="track" role="img" :aria-label="`完成度 ${progress}`"><span :style="{ width: progress }" /></span>
        <span class="text-[13px] text-ink md:text-[13.5px]">
          再{{ unit === '次' ? '多' : '通过' }} <strong class="num text-brand-600">{{ context.gap }}</strong> {{ unit }}就能追平<template v-if="compact">第 {{ context.ahead.rank }} 名</template>
        </span>
      </div>
      <p v-else-if="me.rank === 1" class="m-0 text-[13.5px] text-ink">你是这个范围里的第一名</p>

      <div v-if="!compact && context && context.around.length > 1" class="flex flex-col gap-0.5">
        <div v-for="row in context.around" :key="row.userId" class="neighbor" :class="{ 'is-me': row.userId === me.userId }">
          <span class="num w-6 text-[13.5px] text-muted font-semibold">{{ row.rank }}</span>
          <span class="min-w-0 flex-1 truncate text-sm text-ink">{{ row.displayName }}<template v-if="row.userId === me.userId">（我）</template></span>
          <span class="num text-[14.5px] text-ink font-semibold">{{ row.score ?? row.solved ?? 0 }}</span>
        </div>
      </div>
    </template>

    <template v-else>
      <p class="m-0 text-sm text-ink leading-relaxed" data-test="not-ranked">{{ ranking.myRankNote }}</p>
      <el-button v-if="action" type="primary" class="self-start" @click="emit('action')">{{ action.label }}</el-button>
    </template>
  </section>
</template>

<style scoped>
.position {
  display: flex;
  flex-direction: column;
  gap: 14px;
  padding: 20px;
  border: 1px solid color-mix(in srgb, var(--brand-400) 45%, var(--border));
  border-radius: var(--radius);
  background: var(--brand-soft);
}

.position.compact {
  gap: 12px;
  padding: 16px;
}

.big {
  color: var(--brand-600);
  font-size: 54px;
  font-weight: 600;
  line-height: 0.9;
}

.compact .big {
  font-size: 44px;
}

.gap-box {
  display: flex;
  flex-direction: column;
  gap: 6px;
  padding: 12px;
  border-radius: 10px;
  background: var(--bg-elevated);
}

.compact .gap-box {
  padding: 0;
  background: transparent;
}

.track {
  height: 8px;
  overflow: hidden;
  border-radius: 4px;
  background: color-mix(in srgb, var(--brand-400) 25%, var(--bg-elevated));
}

.compact .track {
  background: var(--bg-elevated);
}

.track > span {
  display: block;
  height: 100%;
  border-radius: 4px;
  background: var(--brand-500);
}

.neighbor {
  display: flex;
  align-items: center;
  gap: 10px;
  height: 40px;
  padding: 0 10px;
  border-radius: 9px;
}

.neighbor.is-me {
  background: var(--bg-elevated);
}
</style>
