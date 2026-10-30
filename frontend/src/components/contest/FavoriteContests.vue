<script setup lang="ts">
import PlatformLogo from '@/components/PlatformLogo.vue'
import InfoTip from '@/components/ui/InfoTip.vue'
import { contestDay, contestTime } from '@/utils/contest'
import { platformName } from '@/utils/platformBrand'
import StarIcon from './StarIcon.vue'
import type { ContestRow } from './types'

/**
 * 电脑右栏「我的收藏」：还没结束的收藏（最多列 5 场，时间顺序），可以在这里取消收藏；
 * 下面是「加入手机或电脑日历」（导出收藏的场次为 .ics）和一行说明，详细说明在 ⓘ 里。
 */
defineProps<{ favorites: ContestRow[]; count: number; now: number; exporting: boolean }>()
const emit = defineEmits<{ favorite: [row: ContestRow]; export: [] }>()
</script>

<template>
  <section class="side-card" data-test="favorite-contests">
    <div class="flex items-baseline justify-between gap-2">
      <h2 class="m-0 text-[15px] text-ink font-semibold">我的收藏</h2>
      <span class="text-xs text-muted">共 <span class="num">{{ count }}</span> 场</span>
    </div>
    <p v-if="!favorites.length" class="m-0 text-[13px] text-muted leading-relaxed">还没有收藏的比赛。点列表里的星星收藏，就能一起加入日历。</p>
    <ul v-else class="m-0 list-none p-0">
      <li v-for="row in favorites.slice(0, 5)" :key="row.id" class="fav-row">
        <PlatformLogo :platform="row.platform" :size="18" :title="platformName(row.platform)" />
        <span class="min-w-0 flex flex-1 flex-col">
          <span class="truncate text-[13.5px] text-ink" :title="row.title">{{ row.title }}</span>
          <span class="num text-xs text-muted">{{ contestDay(row.startAt, now) }} {{ contestTime(row.startAt) }}</span>
        </span>
        <button type="button" class="star" :aria-label="`取消收藏 ${row.title}`" title="取消收藏" @click="emit('favorite', row)">
          <StarIcon :on="true" :size="18" />
        </button>
      </li>
    </ul>
    <p v-if="favorites.length > 5" class="m-0 text-xs text-muted">还有 {{ favorites.length - 5 }} 场，点「只看收藏」查看全部</p>

    <div class="flex flex-col gap-2 border-t border-t-solid border-line-soft pt-3">
      <el-button type="primary" :loading="exporting" :disabled="!count" data-test="export-ics" @click="emit('export')">
        <span v-if="!exporting" class="i-lucide-calendar-plus mr-1.5 text-base" aria-hidden="true" />加入手机或电脑日历
      </el-button>
      <p class="m-0 flex items-center justify-center gap-1 text-xs text-muted">
        每场提前 30 分钟提醒，导出的是当时的快照
        <InfoTip title="加入日历说明">
          导出收藏的场次为 .ics 文件，手机、电脑的日历都能导入，每场提前 30 分钟提醒。导出的是当时的快照：源站改期或取消后，要重新导出一次；没给时长的场次只标开始时间。
        </InfoTip>
      </p>
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

.fav-row {
  display: flex;
  align-items: center;
  gap: 10px;
  min-height: 48px;
  border-top: 1px solid var(--border-soft);
}

.fav-row:first-child {
  border-top: 0;
}

.star {
  display: flex;
  align-items: center;
  justify-content: center;
  width: 36px;
  height: 36px;
  padding: 0;
  border: 0;
  border-radius: 9px;
  background: transparent;
  cursor: pointer;
}

.star:focus-visible {
  outline: 2px solid var(--brand-500);
  outline-offset: -2px;
}
</style>
