<script setup lang="ts">
import { computed } from 'vue'
import PlatformLogo from '@/components/PlatformLogo.vue'
import { contestDay, contestLength, contestTime } from '@/utils/contest'
import { beginnerFriendly } from '@/utils/contestLevel'
import { platformName } from '@/utils/platformBrand'
import ContestCountdown from './ContestCountdown.vue'
import StarIcon from './StarIcon.vue'
import { endOf, phaseOf, type ContestRow } from './types'

/**
 * 「下一场」：深靛蓝卡片，写开始时间、标题、平台、时长、适不适合新手，大字倒计时（时:分:秒），
 * 右下是收藏和「去报名」。正在进行的写「距结束」。卡片颜色固定，深浅色主题下一样。
 */
const props = defineProps<{ contest: ContestRow; now: number }>()
const emit = defineEmits<{ favorite: [row: ContestRow] }>()

const phase = computed(() => phaseOf(props.contest, props.now))
const end = computed(() => endOf(props.contest))
const label = computed(() =>
  phase.value === 'live'
    ? `正在进行 · ${contestTime(props.contest.startAt)} 开始`
    : `下一场 · ${contestDay(props.contest.startAt, props.now)} ${contestTime(props.contest.startAt)}`
)
const safeUrl = computed(() => (props.contest.url && /^https?:\/\//i.test(props.contest.url) ? props.contest.url : undefined))
</script>

<template>
  <section class="next-card" data-test="next-contest" :aria-label="`下一场比赛：${contest.title}`">
    <span class="text-[12.5px] text-[#C7D2FE]">{{ label }}</span>
    <h2 class="m-0 text-[19px] font-bold leading-snug md:text-[22px]">{{ contest.title }}</h2>
    <div class="flex flex-wrap items-center gap-2 text-[12.5px] text-[#E0E7FF]">
      <span class="flex items-center gap-1.5"><PlatformLogo :platform="contest.platform" :size="16" />{{ platformName(contest.platform) }}</span>
      <span v-if="contest.durationSec">· {{ contestLength(contest.durationSec) }}</span>
      <span v-if="beginnerFriendly(contest)" class="newbie">适合新手</span>
    </div>
    <div class="mt-1 flex items-end justify-between gap-3">
      <span class="flex flex-col">
        <template v-if="phase === 'upcoming'">
          <span class="text-[11.5px] text-[#C7D2FE]">距开始</span>
          <ContestCountdown :target="Date.parse(contest.startAt)" :now="now" class="text-[32px] font-semibold leading-tight" data-test="next-countdown" />
        </template>
        <template v-else-if="phase === 'live' && end">
          <span class="text-[11.5px] text-[#C7D2FE]">距结束</span>
          <ContestCountdown :target="end" :now="now" class="text-[32px] font-semibold leading-tight" data-test="next-countdown" />
        </template>
        <span v-else class="text-lg font-semibold">已开始</span>
      </span>
      <span class="flex items-center gap-2">
        <button
          type="button"
          class="star"
          :class="{ 'is-on': contest.favorited }"
          :aria-pressed="contest.favorited"
          :aria-label="contest.favorited ? '取消收藏' : '收藏这场比赛'"
          :title="contest.favorited ? '已收藏，点一下取消' : '收藏后可以加入日历'"
          @click="emit('favorite', contest)"
        >
          <StarIcon :on="contest.favorited" :size="20" />
        </button>
        <a v-if="safeUrl" :href="safeUrl" target="_blank" rel="noopener noreferrer" class="go">{{ phase === 'live' ? '进入比赛' : '去报名' }}</a>
      </span>
    </div>
  </section>
</template>

<style scoped>
.next-card {
  display: flex;
  flex-direction: column;
  gap: 10px;
  padding: 18px;
  border-radius: 16px;
  color: #fff;
  background-color: #1e1b4b;
  background-image:
    linear-gradient(rgba(255, 255, 255, 0.05) 1px, transparent 1px),
    linear-gradient(90deg, rgba(255, 255, 255, 0.05) 1px, transparent 1px);
  background-size: 22px 22px;
}

@media (min-width: 768px) {
  .next-card {
    padding: 22px 24px;
  }
}

.newbie {
  display: inline-flex;
  align-items: center;
  height: 22px;
  padding: 0 8px;
  border-radius: 6px;
  background: #bae6fd;
  color: #0c4a6e;
  font-size: 11.5px;
  font-weight: 600;
}

.star {
  display: flex;
  align-items: center;
  justify-content: center;
  width: 38px;
  height: 38px;
  padding: 0;
  border: 1px solid rgba(255, 255, 255, 0.28);
  border-radius: 10px;
  background: transparent;
  color: #e0e7ff;
  cursor: pointer;
}

.go {
  display: inline-flex;
  align-items: center;
  height: 38px;
  padding: 0 14px;
  border-radius: 10px;
  background: #fff;
  color: #312e81;
  font-size: 14px;
  font-weight: 600;
  text-decoration: none;
}

.go:hover {
  background: #eef2ff;
}

.star:focus-visible,
.go:focus-visible {
  outline: 2px solid #fff;
  outline-offset: 2px;
}
</style>
