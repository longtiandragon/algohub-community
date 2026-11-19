<script setup lang="ts">
import { computed, ref } from 'vue'
import type { TrainingStudent, WeeklyContest } from '@/api/weeklyTraining'

const props = defineProps<{ contest: WeeklyContest }>()
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
  <details class="px-5 pb-3" @toggle="expanded = ($event.currentTarget as HTMLDetailsElement).open">
      <summary class="cursor-pointer py-2 text-sm font-semibold text-ink">学生完成情况</summary>
      <div v-if="expanded" class="grade-progress"><span v-for="g in grades" :key="g.year"><strong>{{ g.year }} 级</strong> {{ g.met }}/{{ g.total }} 人达标</span></div>
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

</template>
<style scoped>
.grade-progress { display:flex; gap:8px; flex-wrap:wrap; }
.grade-progress span { font-size:12px; background:var(--el-fill-color-light); padding:4px 8px; border-radius:5px; }
.grade-progress strong { margin-right:4px; }
th, td { padding: 8px 4px; }
th:first-child { width: 34%; }
</style>
