<script setup lang="ts">
import { computed, onBeforeUnmount, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { useAuthStore } from '@/stores/auth'
import { notifyError } from '@/api/client'
import { loadWeeklyHome, weekMonday, type TrainingSchool, type WeeklyCard, type WeeklyRankingRow } from '@/api/weeklyTraining'
import { campusDate } from '@/utils/statistics'
import { platformName, platformLogoUrl } from '@/utils/platformBrand'
import PageHeader from '@/components/ui/PageHeader.vue'
import WeekPicker from '@/components/weekly/WeekPicker.vue'

const route = useRoute(), router = useRouter()
const auth = useAuthStore()
const schools = ref<TrainingSchool[]>([]), schoolLoading = ref(true)
const school = computed(() => schools.value.find(s => s.id === Number(route.query.school)) ?? schools.value[0])
const week = computed(() => {
  const value = String(route.query.week ?? '')
  const valid = /^\d{4}-\d{2}-\d{2}$/.test(value) && Number.isFinite(Date.parse(value + 'T00:00:00Z'))
  return weekMonday(valid ? value : campusDate())
})
const allTime = ref(false)
const items = ref<WeeklyCard[]>([]), ranking = ref<WeeklyRankingRow[]>([])
const contests = computed(() => items.value.filter(c => c.status === 'PUBLISHED'))
const unpublished = computed(() => items.value.filter(c => c.status !== 'PUBLISHED'))
const loading = ref(false), error = ref(false)
const recent = new Map<boolean, { fetched: number; data: Awaited<ReturnType<typeof loadWeeklyHome>> }>()
let token = 0
onBeforeUnmount(() => { token++ })
async function refresh(reuse = false) {
  const request = ++token
  loading.value = true; error.value = false
  try {
    const mode = allTime.value
    const cached = reuse ? recent.get(mode) : undefined
    const fresh = cached && Date.now() - cached.fetched < 15000
    const result = fresh ? cached.data : await loadWeeklyHome(Number(route.query.school) || undefined, week.value, allTime.value)
    if (request !== token) return
    if (!fresh) recent.set(mode, { fetched: Date.now(), data: result })
    schools.value = result.schools; items.value = result.contests; ranking.value = result.ranking
  } catch (e) {
    if (request === token) { error.value = true; notifyError(e) }
  } finally {
    if (request === token) { loading.value = false; schoolLoading.value = false }
  }
}
watch([() => auth.me?.id, () => route.query.school, week], () => {
  recent.clear(); items.value = []; ranking.value = []; schools.value = []; schoolLoading.value = true
  void refresh()
}, { immediate: true })
watch(allTime, () => refresh(true))
function select(value: {school?: number; week?: string}) { void router.replace({query:{...route.query,...value}}) }
function publishLink(edit?: number) { return {path:'/weekly/' + school.value?.id + '/publish',query:{week:week.value,...(edit ? {edit} : {})}} }
function publishedDate(value: string | null) {
  return value ? new Date(value).toLocaleString('zh-CN',{timeZone:'Asia/Shanghai',month:'numeric',day:'numeric',hour:'2-digit',minute:'2-digit',hour12:false}) : ''
}
</script>
<template>
  <div class="page weekly-home mx-auto max-w-[1200px]">
    <PageHeader title="每周训练赛" :description="school?.name">
      <template #actions><RouterLink v-if="school?.manage" class="publish-link" :to="publishLink()"><i class="i-lucide-plus" />布置训练赛</RouterLink></template>
    </PageHeader>
    <div v-if="schoolLoading" v-loading="true" class="min-h-32" />
    <div v-else-if="error && !school" role="alert">训练赛读取失败 <el-button text @click="refresh()">重试</el-button></div>
    <el-empty v-else-if="!school" description="暂无可查看的学校" />
    <template v-else>
      <div class="toolbar">
        <WeekPicker :model-value="week" @update:model-value="select({week:$event})" />
        <el-select v-if="schools.length > 1" :model-value="school.id" aria-label="选择学校" class="!w-48" @change="select({school:$event})">
          <el-option v-for="s in schools" :key="s.id" :value="s.id" :label="s.name" />
        </el-select>
        <button type="button" class="reload" aria-label="刷新完成情况" :disabled="loading" @click="refresh()"><i class="i-lucide-refresh-cw" /></button>
      </div>
      <div v-if="error" role="alert">训练赛读取失败 <el-button text @click="refresh()">重试</el-button></div>
      <div v-else class="panels">
        <div class="stack">
          <section class="panel">
            <header class="section-title"><h2>{{ week === weekMonday() ? '本周比赛' : '该周比赛' }}</h2><span>{{ contests.length }} 场</span></header>
            <p v-if="!loading && !contests.length" class="empty">该周暂无已发布的训练赛</p>
            <article v-for="contest in contests" :key="contest.id" class="contest">
              <RouterLink :to="{path:'/weekly/'+school.id+'/'+contest.id,query:{week}}" class="contest-link">
                <span class="platform-icon"><img v-if="platformLogoUrl(contest.meta.platform)" :src="platformLogoUrl(contest.meta.platform)!" alt="" /><i v-else class="i-lucide-code-2" /></span>
                <span class="contest-text"><strong>{{ contest.meta.title }}</strong><small>{{ platformName(contest.meta.platform) }} · {{ contest.meta.category }}<span class="audience">{{ contest.audience === 'SELECTED' ? '指定学生' : '全校开放' }}</span></small><small v-if="contest.myProgress">我的目标：至少 {{ contest.myProgress.goal }} 题</small></span>
                <i class="i-lucide-chevron-right" aria-hidden="true" />
              </RouterLink>
            </article>
          </section>
          <section v-if="school.manage && unpublished.length" class="panel">
            <header class="section-title"><h2>发布管理</h2><span>{{ unpublished.length }} 场</span></header>
            <RouterLink v-for="item in unpublished" :key="item.id" class="manage-row" :to="publishLink(item.id)">
              <span><strong>{{ item.meta.title }}</strong><small>{{ item.status === 'SCHEDULED' ? '定时发布 · '+publishedDate(item.publishAt) : '草稿' }}</small></span><span>编辑 ›</span>
            </RouterLink>
          </section>
        </div>
        <section v-loading="loading" class="panel ranking">
          <header class="section-title"><h2>累计过题榜</h2><div class="range-switch" aria-label="排行榜统计范围"><button :aria-pressed="!allTime" @click="allTime=false">本周</button><button :aria-pressed="allTime" @click="allTime=true">累计</button></div></header>
          <p class="note">{{ allTime ? "所有训练周" : "所选训练周" }} · 仅统计全校开放训练赛，同题去重</p>
          <table v-if="ranking.length"><thead><tr><th>名次</th><th>学生</th><th>年级</th><th>累计过题</th></tr></thead>
            <tbody><tr v-for="row in ranking" :key="row.userId"><td><span class="rank" :class="{leading:row.rank <= 3}">{{ row.rank }}</span></td><td>{{ row.realName || row.name }}</td><td>{{ row.year }} 级</td><td>{{ row.solved }}</td></tr></tbody>
          </table>
          <p v-else-if="!loading" class="empty">暂无可展示的过题记录</p>
        </section>
      </div>
    </template>
  </div>
</template>
<style scoped>
.weekly-home { color:var(--el-text-color-primary); }
.publish-link,.toolbar,.section-title,.contest-link,.manage-row { display:flex; align-items:center; }
.publish-link { gap:6px; background:var(--el-color-primary); color:white; padding:9px 14px; border-radius:7px; font-weight:600; font-size:14px; }
.toolbar { gap:10px; flex-wrap:wrap; margin:2px 0 18px; }
.reload { border:0; background:transparent; color:var(--el-text-color-secondary); padding:8px; display:grid; place-items:center; margin-left:auto; cursor:pointer; }
.panels { display:grid; grid-template-columns:minmax(0,1.1fr) minmax(0,1fr); align-items:start; gap:18px; }
.stack { display:grid; gap:16px; }
.panel { min-width:0; background:var(--el-bg-color); border:1px solid var(--el-border-color-light); border-radius:12px; overflow:hidden; }
.section-title { justify-content:space-between; padding:20px 20px 16px; gap:8px; }
h2 { margin:0; line-height:1.3; font-size:18px; font-weight:700; }
.section-title>span,.contest-text small,.note,.empty,.manage-row small { color:var(--el-text-color-secondary); font-size:13px; }
.contest,.manage-row { border-top:1px solid var(--el-border-color-lighter); }
.contest-link,.manage-row { gap:12px; padding:17px 20px; color:inherit; }
.contest-link:hover,.manage-row:hover { background:var(--el-fill-color-light); }
.platform-icon { width:40px;height:40px;display:grid;place-items:center;border:1px solid var(--el-border-color-lighter);border-radius:9px;flex:none;background:white; }
.platform-icon img { width:23px;height:23px;object-fit:contain; }
.contest-text { flex:1;min-width:0; }
.contest-text strong,.manage-row strong { display:block; font-size:16px; line-height:1.45; overflow-wrap:anywhere; }
.contest-text small,.manage-row small { display:block; margin-top:4px; }
.audience { border-left:1px solid var(--el-border-color); margin-left:8px; padding-left:8px; }
.contest-link>i { color:var(--el-text-color-secondary);flex:none; }
.manage-row { justify-content:space-between; }
.manage-row>span:last-child { color:var(--el-color-primary);font-size:13px;white-space:nowrap; }
.ranking .section-title { padding-bottom:4px; }
.ranking .section-title i { color:var(--el-text-color-secondary); }
.note { margin:0 20px 17px; }
table { width:100%;border-collapse:collapse;font-size:14px; }
th,td { text-align:left;padding:13px 8px;border-top:1px solid var(--el-border-color-lighter); }
th { background:var(--el-fill-color-light);color:var(--el-text-color-secondary);font-size:12px;font-weight:550; }
th:first-child,td:first-child { padding-left:20px;width:62px; }
th:last-child,td:last-child { text-align:right;padding-right:20px;white-space:nowrap; }
td:last-child { font-weight:700;font-variant-numeric:tabular-nums; }
.rank { display:inline-grid;place-items:center;width:24px;height:24px;border-radius:6px;font-variant-numeric:tabular-nums; }
.leading { color:var(--el-color-primary);background:var(--el-color-primary-light-9);font-weight:650; }
.empty { margin:0;padding:28px 18px;text-align:center; }
@media(max-width:860px) { .panels { grid-template-columns:minmax(0,1fr); } }
.range-switch {display:flex;padding:3px;border-radius:7px;background:var(--el-fill-color-light)}
.range-switch button {border:0;border-radius:5px;padding:4px 11px;background:transparent;color:var(--el-text-color-secondary);cursor:pointer}
.range-switch button[aria-pressed=true] {background:var(--el-bg-color);color:var(--el-color-primary);font-weight:600}
</style>
