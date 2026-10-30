<script setup lang="ts">
import { onMounted, ref } from 'vue'
import { get } from '@/api/client'
import GoalRing from './GoalRing.vue'

/**
 * 手机上的本周概况：一句「近 7 天 23 位同学通过了 156 题」（同届时写几级），
 * 下面两块：连续练习几天、本周目标的圆环。点目标那块在底部弹层里看和改本周目标（goal 事件）。
 */
defineProps<{
  summary: { activeUsers?: number; totalEvents?: number } | null
  streak: { currentStreak: number } | null
  scopeLabel?: string
  year: number | null
}>()
const emit = defineEmits<{ goal: [] }>()

const goal = ref<{ hasGoal: boolean; solved: number; target: number | null; reached?: boolean } | null>(null)
async function reload() {
  try {
    goal.value = await get('/goals/weekly')
  } catch {
    goal.value = null
  }
}
onMounted(reload)
defineExpose({ reload })
</script>

<template>
  <section class="overview" data-test="week-overview">
    <p class="m-0 flex items-center gap-2 text-sm text-ink">
      <span class="i-lucide-users shrink-0 text-lg text-brand" aria-hidden="true" />
      <span v-if="summary">
        近 7 天 · {{ scopeLabel || (year ? `${year} 级` : '') }}
        <strong class="num">{{ summary.activeUsers ?? 0 }}</strong> 位同学通过了
        <strong class="num">{{ summary.totalEvents ?? 0 }}</strong> 题
      </span>
      <span v-else class="text-muted">正在读取近 7 天的动态情况…</span>
    </p>
    <div class="grid grid-cols-2 gap-2.5">
      <div class="tile streak" data-test="overview-streak">
        <span class="i-lucide-flame shrink-0 text-[22px]" aria-hidden="true" />
        <span class="flex flex-col">
          <span class="label">连续练习</span>
          <span class="text-[13px] text-ink"><strong class="num value">{{ streak?.currentStreak ?? '—' }}</strong> 天</span>
        </span>
      </div>
      <button type="button" class="tile goal" data-test="overview-goal" :aria-label="goal?.hasGoal ? `本周目标 ${goal.solved} / ${goal.target}，点开查看和修改` : '定本周目标'" @click="emit('goal')">
        <GoalRing :solved="goal?.solved ?? 0" :target="goal?.hasGoal ? goal.target : null" :size="30" :stroke="5" />
        <span class="flex flex-col items-start">
          <span class="label">本周目标</span>
          <span v-if="goal?.hasGoal" class="num text-[13px] text-ink"><strong class="value">{{ goal.solved }}</strong> / {{ goal.target }}</span>
          <span v-else class="text-[13px] text-ink font-semibold">定个目标</span>
        </span>
      </button>
    </div>
  </section>
</template>

<style scoped>
.overview {
  display: flex;
  flex-direction: column;
  gap: 12px;
  padding: 14px 16px;
  border: 1px solid var(--border);
  border-radius: var(--radius);
  background: var(--bg-elevated);
}

.tile {
  display: flex;
  align-items: center;
  gap: 10px;
  padding: 10px 12px;
  border: 0;
  border-radius: 12px;
  font: inherit;
  text-align: left;
}

.label {
  font-size: 11.5px;
}

.value {
  font-size: 20px;
}

.streak {
  background: var(--warning-tint);
  color: var(--warning-ink);
}

.streak .value {
  color: var(--warning-ink);
}

.goal {
  background: var(--brand-soft);
  color: var(--brand-600);
  cursor: pointer;
}

.goal:focus-visible {
  outline: 2px solid var(--brand-500);
  outline-offset: 2px;
}
</style>
