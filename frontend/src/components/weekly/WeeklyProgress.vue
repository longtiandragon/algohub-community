<script setup lang="ts">
import { computed, ref } from 'vue'
import type { TrainingStudent, WeeklyContest } from '@/api/weeklyTraining'
import SectionCard from '@/components/ui/SectionCard.vue'
import { platformName, platformLogoUrl } from '@/utils/platformBrand'

const props = defineProps<{ contest: WeeklyContest; school: number }>()
const year = ref(''), incomplete = ref(false), expanded = ref(false)
const grades = computed(() => Object.entries(props.contest.targets).sort(([a], [b]) => Number(b) - Number(a)).map(([year, goal]) => {
  const students = props.contest.students.filter(s => s.year === Number(year))
  return { year, goal, total: students.length, met: students.filter(s => s.status === 'MET').length }
}))
const filtered = computed(() => props.contest.students.filter(s =>
  (!year.value || s.year === Number(year.value)) && (!incomplete.value || s.status !== 'MET')))
const labels: Record<TrainingStudent['status'], string> = {
  MET: '已达标', PENDING: '未达标', UNBOUND: '未绑定', SYNCING: '待同步', ERROR: '同步异常'
}
</script>
<template>
  <SectionCard class="min-w-0">
    <div class="flex flex-wrap items-start justify-between gap-3">
      <div class="min-w-0">
        <div class="mb-1.5 flex items-center gap-2 text-xs text-muted">
          <img v-if="platformLogoUrl(contest.meta.platform)" :src="platformLogoUrl(contest.meta.platform)!" width="16" height="16" alt="" />
          <span>{{ platformName(contest.meta.platform) }} · {{ contest.meta.category }}</span>
        </div>
        <h3 class="m-0 break-words text-lg font-semibold text-ink">{{ contest.meta.title }}</h3>
      </div>
      <RouterLink :to="'/weekly/' + school + '/' + contest.id" class="inline-flex min-h-9 items-center gap-1 text-sm font-semibold text-brand-600">
        比赛详情 <i aria-hidden="true" class="i-lucide-arrow-up-right" />
      </RouterLink>
    </div>
    <div class="my-4 grid gap-3 sm:grid-cols-2 lg:grid-cols-3">
      <div v-for="g in grades" :key="g.year" class="rounded-lg bg-surface-2 px-3 py-2.5">
        <div class="flex items-baseline justify-between gap-2"><strong>{{ g.year }} 级</strong><span class="text-xs text-muted">任选 {{ g.goal }} 题</span></div>
        <div class="my-1.5 text-sm">达标 <b>{{ g.met }}</b> / {{ g.total }} 人</div>
        <el-progress :percentage="g.total ? Math.round(100 * g.met / g.total) : 0" :show-text="false" :stroke-width="4" />
      </div>
    </div>
    <details class="border-t border-line pt-2" @toggle="expanded = ($event.currentTarget as HTMLDetailsElement).open">
      <summary class="w-fit cursor-pointer py-2 text-sm font-semibold text-ink">学生完成情况</summary>
      <template v-if="expanded">
        <div class="my-2 flex flex-wrap items-center gap-3">
          <el-select v-model="year" aria-label="按年级筛选" class="!w-36">
            <el-option label="全部年级" value="" />
            <el-option v-for="g in grades" :key="g.year" :label="g.year + ' 级'" :value="g.year" />
          </el-select>
          <el-checkbox v-model="incomplete">仅看尚未达标</el-checkbox>
        </div>
        <table class="w-full table-fixed text-left text-sm">
          <thead class="text-muted"><tr><th>学生</th><th>年级</th><th>已过 / 目标</th><th>状态</th></tr></thead>
          <tbody><tr v-for="s in filtered" :key="s.userId" class="border-t border-line">
            <td class="break-words">{{ s.name }}</td><td>{{ s.year }}</td>
            <td>{{ s.solved > 0 || ['MET','PENDING'].includes(s.status) ? s.solved : '—' }} / {{ s.goal }}</td>
            <td :class="s.status === 'MET' ? 'text-success-ink' : 'text-muted'">{{ labels[s.status] }}</td>
          </tr></tbody>
        </table>
        <p v-if="!filtered.length" class="text-sm text-muted">暂无符合条件的学生</p>
      </template>
    </details>
  </SectionCard>
</template>
<style scoped>
th, td { padding: 8px 4px; }
th:first-child { width: 34%; }
</style>
