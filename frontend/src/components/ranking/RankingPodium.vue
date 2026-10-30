<script setup lang="ts">
import { computed } from 'vue'
import type { RankingRow } from '@/api/types'
import UserAvatar from '@/components/ui/UserAvatar.vue'

/**
 * 前三名的领奖台：第一名在中间最高，第二、三名在两边。名次按榜上的名次（并列时两人都是 1），
 * 台子的高低按位置。奖牌色：金、银、铜。
 */
const props = defineProps<{
  rows: RankingRow[]
  unit: string
  meId?: number
  scope?: 'GLOBAL' | 'SCHOOL'
}>()

const order = computed(() => {
  const [first, second, third] = props.rows
  return [second, first, third].filter(Boolean) as RankingRow[]
})
const MEDAL = ['gold', 'silver', 'bronze']
const medal = (row: RankingRow) => MEDAL[Math.min(row.rank, 3) - 1]
const score = (row: RankingRow) => row.score ?? row.solved ?? 0
</script>

<template>
  <section
    aria-label="前三名"
    class="grid items-end gap-2 border border-line rounded-card border-solid bg-surface px-3 pt-4 md:(gap-4 px-7 pt-6)"
    :style="{ gridTemplateColumns: `repeat(${order.length}, minmax(0, 1fr))` }"
    data-test="podium"
  >
    <div
      v-for="row in order"
      :key="row.userId"
      class="min-w-0 flex flex-col items-center gap-1 md:gap-1.5"
      :data-me-anchor="row.userId === meId ? '' : undefined"
    >
      <RouterLink :to="`/users/${row.userId}`" :aria-label="`查看 ${row.displayName} 的档案`" class="avatar" :class="`medal-${medal(row)}`">
        <UserAvatar :src="row.avatarUrl" class="face">{{ row.displayName.slice(0, 1) }}</UserAvatar>
        <span class="num badge" aria-hidden="true">{{ row.rank }}</span>
      </RouterLink>
      <span class="sr-only">第 {{ row.rank }} 名</span>
      <RouterLink :to="`/users/${row.userId}`" class="no-underline hover:underline mt-1 max-w-full truncate text-[13.5px] text-ink font-semibold md:text-[15px]">
        {{ row.displayName }}<template v-if="row.userId === meId">（我）</template>
      </RouterLink>
      <span v-if="scope === 'GLOBAL' || row.className" class="hidden max-w-full truncate text-xs text-muted md:block">{{ scope === 'GLOBAL' ? row.schoolName || '未加入学校' : row.className }}</span>
      <span class="mb-1 flex items-baseline gap-0.5 md:mb-1.5">
        <span class="num text-lg text-ink font-semibold md:text-[26px]">{{ score(row) }}</span>
        <span class="text-[12.5px] text-muted">{{ unit }}</span>
      </span>
      <span class="step" :class="[`medal-${medal(row)}`, `place-${row.position}`]" aria-hidden="true" />
    </div>
  </section>
</template>

<style scoped>
.avatar {
  position: relative;
  display: flex;
  align-items: center;
  justify-content: center;
  width: 46px;
  height: 46px;
  box-sizing: border-box;
  border: 3px solid var(--medal);
  border-radius: 50%;
  background: color-mix(in srgb, var(--medal) 14%, var(--bg-elevated));
  color: var(--text-strong);
  font-size: 17px;
  font-weight: 600;
}

.face {
  display: flex;
  align-items: center;
  justify-content: center;
  width: 100%;
  height: 100%;
  border-radius: 50%;
}

.badge {
  position: absolute;
  right: -6px;
  bottom: -4px;
  display: flex;
  align-items: center;
  justify-content: center;
  width: 20px;
  height: 20px;
  border: 2px solid var(--bg-elevated);
  border-radius: 50%;
  background: var(--medal);
  /* 奖牌色两种主题一样，上面的数字用固定的深色才看得清（白字在金色上对比度不到 3） */
  color: #121629;
  font-size: 11px;
  font-weight: 700;
}

.medal-gold {
  --medal: var(--medal-gold);
}

.medal-silver {
  --medal: var(--medal-silver);
}

.medal-bronze {
  --medal: var(--medal-bronze);
}

.step {
  width: 100%;
  border-radius: 8px 8px 0 0;
  background: color-mix(in srgb, var(--medal) 16%, var(--bg-elevated));
}

.place-1 {
  height: 52px;
}

.place-2 {
  height: 34px;
}

.place-3 {
  height: 22px;
}

@media (min-width: 768px) {
  .avatar {
    width: 60px;
    height: 60px;
    font-size: 22px;
  }

  .badge {
    width: 24px;
    height: 24px;
    font-size: 13px;
  }

  .step {
    border-radius: 10px 10px 0 0;
  }

  .place-1 {
    height: 70px;
  }

  .place-2 {
    height: 46px;
  }

  .place-3 {
    height: 30px;
  }
}
</style>
