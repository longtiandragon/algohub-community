<script setup lang="ts">
import { provideStudentRemarks } from '@/composables/useStudentRemarks'
import StudentRemarkCard from '@/components/school/StudentRemarkCard.vue'
import RemarkName from '@/components/school/RemarkName.vue'
import { computed, onBeforeUnmount, ref, watch } from 'vue'
import { useRoute } from 'vue-router'
import { loadDetail, type ContestDetail, type ScoreCell, type ScoreRow } from '@/api/weeklyTraining'
import { notifyError } from '@/api/client'
import { platformName } from '@/utils/platformBrand'

import { useTopBarOverride } from '@/composables/useTopBarOverride'

const route = useRoute()
useTopBarOverride(() => ({ title: '训练赛详情', parent: '/weekly?school=' + route.params.school, parentTitle: '每周训练赛' }))
const contest = ref<ContestDetail>(), loading = ref(false), error = ref('')
const openMark = ref<string>()
let request = 0
onBeforeUnmount(() => { request++ })
async function load() {
  const token = ++request
  contest.value = undefined; openMark.value = undefined; loading.value = true; error.value = ''
  try {
    const data = await loadDetail(Number(route.params.school), Number(route.params.id))
    if (token === request) contest.value = data
  } catch (e) {
    if (token === request) { error.value = '无法读取这场校内训练赛'; notifyError(e) }
  } finally { if (token === request) loading.value = false }
}
watch(() => [route.params.school, route.params.id], load, { immediate: true })
const showScore = computed(() => contest.value?.meta.platform !== 'CODEFORCES' || ['CF', 'IOI'].includes(contest.value.meta.scoring ?? ''))
const groups = computed(() => {
  const rows = contest.value?.rows ?? []
  return [
    { label: '参赛成绩', rows: rows.filter(r => r.kind !== 'PRACTICE') },
    { label: '补题记录', rows: rows.filter(r => r.kind === 'PRACTICE') }
  ].filter(group => group.rows.length)
})
function time(seconds: number) {
  const h = Math.floor(seconds / 3600)
  const m = Math.floor(seconds / 60) % 60, s = Math.floor(seconds) % 60
  return [h, m, s].map(n => String(n).padStart(2, '0')).join(':')
}
function result(cell?: ScoreCell) {
  if (!cell) return ''
  if (cell.wrong == null) return cell.solved ? '已通过' : '未通过'
  const mistakes = Math.max(0, cell.wrong)
  if (cell.solved) return mistakes ? '+' + mistakes : '+'
  return mistakes ? '−' + mistakes : '未通过'
}
function kindName(kind: ScoreRow['kind']) {
  return { OFFICIAL: '正式参赛', VIRTUAL: '虚拟参赛', PRACTICE: '补题', STAR: '打星参赛' }[kind]
}
function toggleMark(key: string) { openMark.value = openMark.value === key ? undefined : key }
provideStudentRemarks(() => Number(route.params.school))
</script>
<template>
  <div class="page mx-auto min-w-0 max-w-[1200px]">
    <RouterLink :to="{ path: '/weekly', query: { school: route.params.school, week: contest?.weekStart } }" class="mb-4 hidden items-center gap-1 text-sm text-brand-600 md:inline-flex">
      <i class="i-lucide-arrow-left" aria-hidden="true" /> 每周训练赛
    </RouterLink>
    <p v-if="error" role="alert">{{ error }} <el-button text @click="load">重试</el-button></p>
    <div v-else-if="loading" v-loading="true" class="min-h-32" aria-label="正在读取校内榜单" />
    <template v-else-if="contest">
      <header class="contest-head"><div>
        <div class="text-sm text-muted">{{ platformName(contest.meta.platform) }} · {{ contest.meta.category }} · {{ contest.weekStart }} 训练周</div>
        <h1 class="my-1.5 break-words text-2xl text-ink">{{ contest.meta.title }}</h1>
        </div><div class="actions"><a :href="contest.meta.url" target="_blank" rel="noopener noreferrer" class="inline-flex items-center gap-1 text-sm text-brand-600">原站比赛 <i class="i-lucide-external-link" aria-hidden="true" /></a><el-button type="primary" @click="load">刷新榜单</el-button></div>
      </header>
      <section aria-label="校内榜单">
        
        <p v-if="contest.standingsRefreshFailed" role="status" class="m-0 px-4 py-3 text-sm text-muted">
          部分成绩暂未更新，已保留最近同步的记录。{{ contest.meta.platform === 'ATCODER' ? '如持续出现，请管理员检查源站登录授权。' : '系统会自动重试。' }}
        </p>
        <p v-if="!groups.length" class="m-0 px-4 py-3 text-sm text-muted">{{ contest.meta.platform === 'ATCODER' ? '已同步的过题计入训练进度，当前暂无可展示的参赛榜单。' : '当前暂无可展示的参赛或补题记录。' }}</p>
        <div v-if="groups.length || Object.values(contest.problemStats).some(s => s.tried > 0)" tabindex="0" role="region" aria-label="校内逐题榜单，可横向滚动" class="standings-scroll">
          <section v-for="group in groups" :key="group.label" class="score-section"><h2>{{ group.label }}</h2><table class="standings"><colgroup><col class="rank-col"><col class="student-col"><col><col v-if="showScore"><col class="time-col"><col v-for="task in contest.meta.tasks" :key="task.index"></colgroup>
            <thead><tr><th scope="col">校内名次</th><th scope="col" class="name">学生</th><th scope="col">通过</th><th v-if="showScore" scope="col">分数</th><th scope="col">罚时</th><th v-for="task in contest.meta.tasks" :key="task.index" scope="col">{{ task.index }}</th></tr></thead>
            <tbody>
              <tr v-for="(row, i) in group.rows" :key="row.userId + ':' + row.kind + ':' + i">
                <td>{{ row.kind === 'PRACTICE' ? '' : row.rank ?? '—' }}</td>
                <th scope="row" class="name">
                  <span v-if="row.kind === 'PRACTICE'" aria-label="补题">*</span><RemarkName :user-id="row.userId" :name="row.name">
                  <button v-if="row.kind === 'VIRTUAL' || row.kind === 'STAR'" type="button" class="mark" :title="kindName(row.kind)" :aria-label="kindName(row.kind)"
                    @click="toggleMark(group.label + i)" @blur="openMark = undefined">{{ row.kind === 'VIRTUAL' ? '#' : '打星' }}</button></RemarkName>
                  <small v-if="openMark === group.label + i" role="status">{{ kindName(row.kind) }}</small>
                </th>
                <td>{{ Object.values(row.cells).filter(c => c.solved).length }}</td>
                <td v-if="showScore">{{ row.score ?? '—' }}</td>
                <td>{{ row.kind !== 'PRACTICE' && row.penalty != null ? time(row.penalty) : '—' }}</td>
                <td v-for="task in contest.meta.tasks" :key="task.index" :class="{ solved: row.cells[task.index]?.solved, failed: row.cells[task.index] && !row.cells[task.index].solved }">
                  <template v-if="row.cells[task.index]">
                    <span>{{ result(row.cells[task.index]) }}</span>
                    <small v-if="showScore && row.cells[task.index].score != null">{{ row.cells[task.index].score }} 分</small>
                    <small v-if="row.kind !== 'PRACTICE' && row.cells[task.index].seconds != null">{{ time(row.cells[task.index].seconds!) }}</small>
                  </template>
                </td>
              </tr>
            </tbody>
            </table></section><table class="standings totals"><colgroup><col class="rank-col"><col class="student-col"><col><col v-if="showScore"><col class="time-col"><col v-for="task in contest.meta.tasks" :key="task.index"></colgroup><tfoot><tr><th :colspan="showScore ? 5 : 4" scope="row">通过 / 尝试人数</th><td v-for="task in contest.meta.tasks" :key="task.index">{{ contest.problemStats[task.index]?.solved ?? 0 }} / {{ contest.problemStats[task.index]?.tried ?? 0 }}</td></tr></tfoot>
          </table>
        </div>
      </section>
    </template>
  <StudentRemarkCard />
  </div>
</template>
<style scoped>

.contest-head { display:flex; align-items:center; justify-content:space-between; gap:1rem; margin:1rem 0 1.6rem; }
.contest-head h1 { font-size:clamp(1.6rem,3vw,2.5rem); letter-spacing:-.035em; line-height:1.2; }
.actions { display:flex; align-items:center; gap:1rem; white-space:nowrap; }
.score-section { margin-bottom:1.5rem; }
.score-section h2 { font-size:1.18rem; line-height:1.2; border-left:4px solid var(--el-color-primary); padding-left:.65rem; margin:.4rem 0 .8rem; }
.standings-scroll { overflow-x:auto; }
.standings { width:100%; table-layout:fixed; min-width:760px; border-collapse:separate; border-spacing:0; border:1px solid var(--el-border-color-lighter); border-radius:9px; overflow:hidden; background:var(--el-bg-color); font-variant-numeric:tabular-nums; }
.standings td,.standings th { text-align:center; padding:.55rem .35rem; font-size:.875rem; }
.standings .rank-col { width:7% } .standings .student-col { width:16% } .standings .time-col { width:9% }
.standings .name,.standings tfoot th { text-align:left; overflow-wrap:anywhere; }
.standings tbody td,.standings tbody th { height:54px; border-top:1px solid var(--el-border-color-lighter); }
.standings thead,.standings tfoot { background:var(--el-fill-color-light); color:var(--el-text-color-secondary); }
.standings .solved { color:var(--success-ink,var(--el-color-success)); background:var(--el-color-success-light-9); font-weight:600; }
.standings .failed { color:var(--danger-ink,var(--el-color-danger)); background:var(--el-color-danger-light-9); }
.standings small { display:block; font-size:.73rem; font-weight:400; }
.mark { border:0; background:transparent; color:var(--el-color-primary); font-weight:700; padding:4px; cursor:pointer; }
@media(max-width:680px){.contest-head{align-items:flex-start;flex-direction:column}}
</style>
