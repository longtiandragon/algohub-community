<script setup lang="ts">
import { computed } from 'vue'
import Skeleton from '@/components/ui/Skeleton.vue'

/**
 * 近 7 天每天通过（首次通过）：7 根柱，最后一根是今天、颜色浅一点；0 的那天留一条细灰线。
 * 柱上写数字，下面写星期和日期。
 */
const props = defineProps<{
  days: { date: string; value: number }[]
  loading?: boolean
  error?: string | null
  /** 还没有能确认通过时间的记录 */
  empty?: boolean
}>()
defineEmits<{ retry: [] }>()

const WEEKDAYS = ['日', '一', '二', '三', '四', '五', '六']
const max = computed(() => Math.max(1, ...props.days.map((day) => day.value)))
const bars = computed(() =>
  props.days.map((day, index) => {
    const today = index === props.days.length - 1
    const [, month, date] = day.date.split('-').map(Number)
    return {
      ...day,
      today,
      weekday: WEEKDAYS[new Date(`${day.date}T00:00:00Z`).getUTCDay()],
      label: today ? '今天' : `${month}/${date}`,
      // 占柱区高度的比例；0 的那天画一条 4px 的线
      ratio: day.value ? day.value / max.value : 0
    }
  })
)
const summary = computed(() => `近 7 天每天通过：${bars.value.map((bar) => `${bar.label}（周${bar.weekday}）${bar.value} 题`).join('，')}`)
</script>

<template>
  <div v-if="loading" class="flex h-[170px] items-end gap-2.5 md:(h-[230px] gap-[18px])" aria-busy="true">
    <Skeleton v-for="n in 7" :key="n" class="flex-1" :style="{ height: `${30 + ((n * 37) % 60)}%` }" />
  </div>
  <div v-else-if="error" class="flex flex-col items-start gap-2 py-6">
    <p class="m-0 text-[13px] text-danger-ink" data-test="home-error">{{ error }}</p>
    <el-button size="small" @click="$emit('retry')">重试</el-button>
  </div>
  <p v-else-if="empty" class="m-0 py-10 text-center text-[13px] text-muted">还没有能确认通过时间的记录，同步完成后这里会画出每天的柱子。</p>
  <div v-else role="img" :aria-label="summary">
    <div class="bars">
      <div v-for="bar in bars" :key="bar.date" class="bar-column">
        <span class="num bar-value" :class="bar.value ? 'text-ink' : 'text-muted'">{{ bar.value }}</span>
        <span class="bar" :class="{ 'is-today': bar.today, 'is-zero': !bar.value }" :style="{ '--ratio': bar.ratio }" />
      </div>
    </div>
    <div class="bar-labels">
      <span v-for="bar in bars" :key="bar.date" class="flex flex-1 flex-col items-center gap-px">
        <span class="text-xs md:text-[13px]" :class="bar.today ? 'text-brand-600 font-semibold' : 'text-body'">{{ bar.today ? '今天' : bar.weekday }}</span>
        <span class="num hidden text-[11.5px] text-muted md:inline">{{ bar.today ? '' : bar.label }}</span>
      </span>
    </div>
  </div>
</template>

<style scoped>
.bars {
  --bar-area: 110px;
  display: flex;
  align-items: flex-end;
  gap: 10px;
  height: calc(var(--bar-area) + 26px);
  padding: 0 2px;
  border-bottom: 1px solid var(--border-soft);
}

.bar-column {
  display: flex;
  flex: 1;
  flex-direction: column;
  align-items: center;
  gap: 6px;
}

.bar-value {
  font-size: 12.5px;
  font-weight: 600;
}

.bar {
  width: 100%;
  max-width: 56px;
  height: calc(var(--bar-area) * var(--ratio));
  min-height: 4px;
  border-radius: 6px 6px 2px 2px;
  background: var(--brand-500);
}

.bar.is-today {
  background: color-mix(in srgb, var(--brand-500) 45%, var(--bg-elevated));
}

.bar.is-zero {
  height: 4px;
  background: var(--border);
}

.bar-labels {
  display: flex;
  gap: 10px;
  padding: 8px 2px 0;
}

@media (min-width: 768px) {
  .bars {
    --bar-area: 140px;
    gap: 18px;
    height: calc(var(--bar-area) + 30px);
    padding: 0 6px;
  }

  .bar-column {
    gap: 8px;
  }

  .bar-value {
    font-size: 14px;
  }

  .bar {
    border-radius: 8px 8px 3px 3px;
  }

  .bar-labels {
    gap: 18px;
    padding: 8px 6px 0;
  }
}
</style>
