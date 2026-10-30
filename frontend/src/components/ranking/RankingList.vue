<script setup lang="ts">
import type { RankingRow } from '@/api/types'
import UserAvatar from '@/components/ui/UserAvatar.vue'

/**
 * 领奖台之后的榜单：名次（并列同号）、同学、班级、分数条和数字、账号可信度图标。
 * 电脑上是表格样的网格，手机上是一行一人（班级写在名字下面）。「我」那一行品牌色浅底，
 * 带 data-me-anchor，页面用它判断手机底部要不要显示「我的名次」。
 */
const props = defineProps<{
  rows: RankingRow[]
  /** 分数条按这个算满格（通常是第一名的分数） */
  maxScore: number
  unit: string
  /** 表头的分数列名，如「近 7 天首次通过」 */
  scoreLabel: string
  meId?: number
  scope?: 'GLOBAL' | 'SCHOOL'
  /** 榜上已显示多少名、一共多少人参与 */
  shown: number
  total: number
  canMore: boolean
  loadingMore?: boolean
}>()

const emit = defineEmits<{ more: [] }>()

const score = (row: RankingRow) => row.score ?? row.solved ?? 0
const width = (row: RankingRow) => `${props.maxScore > 0 ? Math.max(2, Math.round((score(row) / props.maxScore) * 100)) : 0}%`
/** 头像底色按同学轮换，只是为了好认，没有含义 */
const TONES = ['tone-a', 'tone-b', 'tone-c', 'tone-d', 'tone-e']
const tone = (row: RankingRow) => (row.userId === props.meId ? 'tone-me' : TONES[row.userId % TONES.length])
const trust = (row: RankingRow) => (row.hasUnverifiedSource ? '含还没验证归属的账号' : '账号归属已验证')
const affiliation = (row: RankingRow) => props.scope === 'GLOBAL' ? row.schoolName || '未加入学校' : row.className || '班级未填'
</script>

<template>
  <section class="overflow-hidden border border-line rounded-card border-solid bg-surface" data-test="ranking-list">
    <div class="list-head" aria-hidden="true">
      <span>名次</span><span>同学</span><span>{{ scope === 'GLOBAL' ? '学校' : '班级' }}</span><span>{{ scoreLabel }}</span>
    </div>
    <ol class="m-0 list-none p-0" :aria-label="`${scoreLabel}排行`">
      <li
        v-for="row in rows"
        :key="row.userId"
        class="row"
        :class="{ 'is-me': row.userId === meId }"
        :data-me-anchor="row.userId === meId ? '' : undefined"
        data-test="ranking-row"
      >
        <span class="num rank">{{ row.rank }}<span class="sr-only"> 名</span></span>
        <RouterLink :to="`/users/${row.userId}`" class="min-w-0 flex items-center gap-2.5 no-underline hover:underline md:gap-2.5" :aria-label="`查看 ${row.displayName} 的档案`">
          <UserAvatar :src="row.avatarUrl" class="avatar" :class="tone(row)">{{ row.displayName.slice(0, 1) }}</UserAvatar>
          <span class="min-w-0 flex flex-col gap-px">
            <span class="flex items-center gap-1.5 text-[14.5px] text-ink">
              <span class="truncate">{{ row.displayName }}</span>
              <span v-if="row.userId === meId" class="me-tag">我</span>
              <span
                v-if="row.hasUnverifiedSource"
                class="i-lucide-circle-alert shrink-0 text-sm text-warning md:hidden"
                role="img"
                :aria-label="trust(row)"
              />
            </span>
            <span class="truncate text-xs text-muted md:hidden">{{ affiliation(row) }}</span>
          </span>
        </RouterLink>
        <span class="class-cell truncate text-[13px] text-body">{{ affiliation(row) }}</span>
        <span class="flex items-center gap-2.5">
          <span class="bar" aria-hidden="true"><span :style="{ width: width(row) }" /></span>
          <span class="num score">{{ score(row) }}<span class="sr-only"> {{ unit }}</span></span>
        </span>
      </li>
    </ol>
    <div class="flex items-center justify-between gap-3 px-4 py-3 md:(px-5 py-3.5)">
      <span class="text-[13px] text-muted">显示前 <span class="num">{{ shown }}</span> 名 · 共 <span class="num">{{ total }}</span> 人</span>
      <el-button v-if="canMore" :loading="loadingMore" @click="emit('more')">再看 20 名</el-button>
    </div>
  </section>
</template>

<style scoped>
.list-head {
  display: none;
}

.row {
  display: grid;
  grid-template-columns: 26px minmax(0, 1fr) auto;
  align-items: center;
  gap: 12px;
  min-height: 60px;
  padding: 0 14px;
  border-bottom: 1px solid var(--border-soft);
}

.row.is-me {
  background: var(--brand-soft);
}

.rank {
  color: var(--text-muted);
  font-size: 14.5px;
  font-weight: 600;
}

.avatar {
  display: flex;
  flex-shrink: 0;
  align-items: center;
  justify-content: center;
  width: 32px;
  height: 32px;
  border-radius: 50%;
  font-size: 13px;
  font-weight: 600;
}

.tone-a { background: var(--brand-soft); color: var(--brand-600); }
.tone-b { background: var(--success-soft); color: var(--success-ink); }
.tone-c { background: var(--warning-tint); color: var(--warning-ink); }
.tone-d { background: var(--info-soft); color: var(--info-ink); }
.tone-e { background: var(--danger-soft); color: var(--danger-ink); }
.tone-me { background: var(--brand-btn); color: #fff; }

.me-tag {
  display: inline-flex;
  flex-shrink: 0;
  align-items: center;
  height: 20px;
  padding: 0 6px;
  border-radius: 6px;
  background: var(--brand-btn);
  color: #fff;
  font-size: 11px;
  font-weight: 600;
}

/* 手机：班级在名字下面，只留分数，不画分数条和可信度列 */
.class-cell,
.trust-cell,
.bar {
  display: none;
}

.score {
  min-width: 28px;
  color: var(--text-strong);
  font-size: 16px;
  font-weight: 600;
  text-align: right;
}

@media (min-width: 768px) {
  .list-head,
  .row {
    display: grid;
    grid-template-columns: 56px minmax(0, 1.4fr) minmax(0, 1.1fr) minmax(0, 1.6fr);
    gap: 12px;
    align-items: center;
    padding: 0 20px;
  }

  .list-head {
    height: 42px;
    border-bottom: 1px solid var(--border-soft);
    background: var(--bg-subtle);
    color: var(--text-muted);
    font-size: 12.5px;
  }

  .row {
    min-height: 56px;
  }

  .rank {
    font-size: 15px;
  }

  .avatar {
    width: 30px;
    height: 30px;
    font-size: 12.5px;
  }

  .class-cell,
  .trust-cell {
    display: flex;
  }

  .class-cell {
    display: block;
  }

  .bar {
    display: block;
    flex: 1;
    height: 8px;
    overflow: hidden;
    border-radius: 4px;
    background: var(--border-soft);
  }

  .bar > span {
    display: block;
    height: 100%;
    border-radius: 4px;
    background: color-mix(in srgb, var(--brand-400) 70%, var(--bg-elevated));
  }

  .row.is-me .bar > span {
    background: var(--brand-500);
  }

  .score {
    font-size: 15px;
  }
}
</style>
