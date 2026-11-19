<script setup lang="ts">
import { computed, onBeforeUnmount, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ElMessage, ElMessageBox } from 'element-plus'
import { notifyError } from '@/api/client'
import { assignmentDetail, cancelAssignment, identify, saveAssignment, saveRules, type Audience, type ReleaseStatus } from '@/api/weeklyPublisher'
import { loadSchools, weekMonday, type ContestMeta, type Targets } from '@/api/weeklyTraining'
import { campusDate, shiftDate } from '@/utils/statistics'
import { platformName } from '@/utils/platformBrand'
import { useTopBarOverride } from '@/composables/useTopBarOverride'
import SchoolStudentPicker from '@/components/ui/SchoolStudentPicker.vue'
import { studentsById, type DirectoryStudent } from '@/api/studentDirectory'
import WeekPicker from '@/components/weekly/WeekPicker.vue'

const router = useRouter(), route = useRoute()
const school = computed(() => Number(route.params.school))
const edit = computed(() => route.query.edit ? Number(route.query.edit) : undefined)
const today = campusDate(), minWeek = weekMonday(today)
const end = new Date(today + 'T00:00:00Z')
const dayOfMonth = end.getUTCDate()
end.setUTCDate(1); end.setUTCMonth(end.getUTCMonth() + 7); end.setUTCDate(0)
end.setUTCDate(Math.min(dayOfMonth, end.getUTCDate()))
const maxWeek = weekMonday(end.toISOString().slice(0, 10))
const schoolName = ref(''), ready = ref(false), error = ref(''), saving = ref(false), identifying = ref(false)
const url = ref(''), meta = ref<ContestMeta>(), years = ref<number[]>([]), targets = ref<Targets>({})
const audience = ref<Audience>('ALL'), students = ref<DirectoryStudent[]>([]), ids = ref<number[]>([]), previousIds = ref<number[]>([])
const picked = computed<DirectoryStudent[]>({
  get: () => students.value.filter(s => ids.value.includes(s.userId)),
  set(next) {
    students.value = [...new Map([...students.value, ...next].map(s => [s.userId, s])).values()]
    ids.value = next.map(s => s.userId)
  },
})
const week = ref(minWeek), timed = ref(false), publishTime = ref(''), editStatus = ref<ReleaseStatus>()
const history = ref<{weekStart: string; status: ReleaseStatus}[]>([])
const duplicateWeek = computed(() => history.value.some(item => item.weekStart === week.value))
const statusLabels = { DRAFT: '草稿', SCHEDULED: '定时发布', PUBLISHED: '已发布' }
const home = computed(() => ({ path: '/weekly', query: { school: school.value, week: week.value } }))
useTopBarOverride(() => ({title:'布置训练赛', parent: '/weekly?school=' + school.value, parentTitle:'每周训练赛'}))
let generation = 0, previewGeneration = 0
onBeforeUnmount(() => { generation++; previewGeneration++ })
function beijingInput(timestamp: string) {
  const t = new Date(Date.parse(timestamp) + 8 * 3600_000)
  return t.toISOString().slice(0,16)
}
function initializePublishTime() {
  if (!publishTime.value) publishTime.value = beijingInput(new Date().toISOString())
}
async function init() {
  const token = ++generation
  ++previewGeneration
  ready.value = false; error.value = ''; identifying.value = false
  history.value = []; meta.value = undefined; url.value = ''; targets.value = {}; students.value = []; ids.value = []; previousIds.value = []
  timed.value = false; publishTime.value = ''; audience.value = 'ALL'; editStatus.value = undefined
  const value = String(route.query.week || minWeek)
  week.value = /^\d{4}-\d{2}-\d{2}$/.test(value) && value >= minWeek && value <= maxWeek ? weekMonday(value) : minWeek
  try {
    const schools = await loadSchools()
    if (token !== generation) return
    const current = schools.find(s => s.id === school.value && s.manage)
    if (!current) throw Error('需要本校训练赛管理权限')
    schoolName.value = current.name
    const existing = edit.value ? await assignmentDetail(school.value, edit.value) : undefined
    if (token !== generation) return
    const people = await studentsById(school.value, existing?.studentIds || [])
    if (token !== generation) return
    students.value = people
    const latest = Number(today.slice(0,4))
    years.value = Array.from({length:4}, (_, i) => latest - i)
    if (existing) {
      if (existing.status === 'PUBLISHED') throw Error('这场训练赛已发布')
      url.value = existing.meta.url; meta.value = existing.meta; targets.value = { ...existing.targets }
      audience.value = existing.audience; ids.value = [...(existing.studentIds || [])]; week.value = existing.weekStart
      years.value = [...new Set([...years.value, ...Object.keys(existing.targets).map(Number)])].sort((a,b)=>b-a)
      timed.value = existing.status === 'SCHEDULED'; editStatus.value = existing.status
      if (existing.publishAt) publishTime.value = beijingInput(existing.publishAt)
    }
    ready.value = true
  } catch (e) { if (token === generation) error.value = e instanceof Error ? e.message : '无法加载配置' }
}
watch(() => [school.value, edit.value], init, { immediate: true })
function resetPreview() { history.value = []; ++previewGeneration; identifying.value = false; meta.value = undefined; previousIds.value = [] }
async function recognize() {
  if (!url.value.trim()) return
  const token = ++previewGeneration
  identifying.value = true
  try {
    const data = await identify(school.value, url.value.trim())
    if (token !== previewGeneration) return
    const previous = await studentsById(school.value, data.lastStudentIds || [])
    if (token !== previewGeneration) return
    history.value = data.history || []
    students.value = previous
    meta.value = data.meta; years.value = data.years; targets.value = Object.fromEntries(data.years.filter(y => y in data.targets).map(y => [y,data.targets[y]]))
    audience.value = data.audience || 'ALL'; ids.value = []; previousIds.value = data.lastStudentIds || []
  } catch (e) { if (token === previewGeneration) notifyError(e) }
  finally { if (token === previewGeneration) identifying.value = false }
}
const selected = computed(() => Object.entries(targets.value).sort(([a],[b])=>Number(b)-Number(a)))
const selectedNames = computed(() => students.value.filter(s=>ids.value.includes(s.userId)).map(s=>s.realName || s.name))
const eligiblePrevious = computed(() => previousIds.value.filter(id=>students.value.some(s => s.userId === id && s.year != null && s.year in targets.value)))
const validRules = computed(() => meta.value && selected.value.length && selected.value.every(([,n])=>Number.isInteger(n)&&n>0&&n<=meta.value!.tasks.length))
const valid = computed(() => !duplicateWeek.value && validRules.value && (audience.value === 'ALL' || ids.value.length > 0) && week.value >= minWeek && week.value <= maxWeek)
function toggle(year: number) {
  if (year in targets.value) { delete targets.value[year]; ids.value = ids.value.filter(id=>students.value.some(s=>s.userId===id && s.year!==year)) }
  else targets.value[year] = 1
}
async function defaults() {
  if (!validRules.value || saving.value) return
  saving.value = true
  try { await saveRules(school.value,{ url: url.value.trim(), targets: targets.value, audience: audience.value }); ElMessage.success('已保存本校同类比赛的默认配置') }
  catch(e) { notifyError(e) } finally { saving.value = false }
}
async function submit(status: ReleaseStatus) {
  if (!valid.value || saving.value) return
  const publishAt = status === 'SCHEDULED' && publishTime.value ? publishTime.value + ':00+08:00' : null
  if (status === 'SCHEDULED' && (!publishAt || !(Date.parse(publishAt)>Date.now()))) { ElMessage.warning('请选择未来的北京时间'); return }
  saving.value = true
  try {
    await saveAssignment(school.value,edit.value,{url:url.value.trim(),targets:targets.value,weekStart:week.value,audience:audience.value,studentIds:audience.value==='ALL'?[]:ids.value,status,publishAt})
    ElMessage.success({DRAFT:'草稿已保存',SCHEDULED:'已安排定时发布',PUBLISHED:'训练赛已发布'}[status])
    await router.push(home.value)
  } catch(e) { notifyError(e) } finally {saving.value=false}
}
async function discard() {
  if (!edit.value || saving.value) return
  try { await ElMessageBox.confirm('取消后，此安排将从本周列表中移除。','取消这场安排？',{confirmButtonText:'确认取消',cancelButtonText:'保留'}) } catch {return}
  saving.value=true
  try { await cancelAssignment(school.value,edit.value); ElMessage.success('已取消安排'); await router.push(home.value) }
  catch(e) {notifyError(e)} finally {saving.value=false}
}
</script>
<template>
  <main class="weekly-editor">
    <RouterLink :to="home" class="back">← 每周训练赛</RouterLink>
    <header><h1>{{ edit ? '编辑训练赛' : '布置训练赛' }}</h1><span>{{ schoolName }}</span></header>
    <p v-if="error" role="alert">{{ error }} <el-button text @click="init">重试</el-button></p>
    <div v-else-if="!ready" v-loading="true" class="min-h-32" />
    <div v-else class="workspace">
      <fieldset class="form-panel" :disabled="saving">
        <section>
          <div class="heading link-heading">
            <label for="weekly-contest-url">比赛链接</label>
            <el-popover trigger="click" placement="bottom-start" :width="308" title="比赛链接示例">
              <template #reference>
                <button type="button" class="link-help" aria-label="查看比赛链接示例"><i class="i-lucide-circle-alert" aria-hidden="true" /></button>
              </template>
              <dl class="link-examples">
                <dt>Codeforces</dt><dd><code>https://codeforces.com/contest/{}</code></dd>
                <dt>AtCoder</dt><dd><code>https://atcoder.jp/contests/{}</code></dd>
                <dt>牛客</dt><dd><code>https://ac.nowcoder.com/acm/contest/{}</code></dd>
              </dl>
            </el-popover>
          </div>
          <div class="url"><el-input id="weekly-contest-url" v-model="url" :disabled="saving || !!edit" placeholder="粘贴 Codeforces、AtCoder 或牛客比赛链接" @input="resetPreview" @keyup.enter="recognize" />
          <el-button v-if="!edit" type="primary" :loading="identifying" :disabled="!url.trim() || saving" @click="recognize">识别比赛</el-button></div>
          <div v-if="meta" class="recognized"><i class="i-lucide-circle-check" /> {{ meta.title }} · {{ platformName(meta.platform) }} · {{ meta.tasks.length }} 题</div>
          <div v-if="history.length" class="assignment-history" role="status">
            <strong>{{ duplicateWeek ? '所选训练周已安排这场比赛，请选择其他周。' : '本校已安排过这场比赛，可以继续布置到所选训练周。' }}</strong>
            <ul><li v-for="item in history" :key="item.weekStart">{{ item.weekStart }} 至 {{ shiftDate(item.weekStart, 6) }} · {{ statusLabels[item.status] }}</li></ul>
          </div>
        </section>
        <section><h2>训练周</h2><WeekPicker v-model="week" :min-week="minWeek" :max-week="maxWeek" /></section>
        <section><h2>年级目标</h2><small v-if="!meta" class="secondary">识别比赛后，可设置各年级目标。</small>
          <div class="year-table"><div v-for="y in years" :key="y" class="year-row">
            <label><input type="checkbox" :disabled="!meta" :checked="y in targets" @change="toggle(y)" /> {{ y }} 级</label>
            <label v-if="y in targets">任选 <input class="count" type="number" min="1" :max="meta?.tasks.length" step="1" v-model.number="targets[y]" :aria-label="y+' 级目标题数'" /> 题</label><span v-else class="secondary">未启用</span>
          </div></div>
          <p v-if="meta && selected.some(([,n])=>n>meta!.tasks.length)" role="alert">本场共有 {{ meta.tasks.length }} 题，请调整超出题数的目标。</p>
        </section>
        <section><h2>开放范围</h2><div class="choices">
          <label><input v-model="audience" type="radio" value="ALL" /> 全校开放</label>
          <label><input v-model="audience" type="radio" value="SELECTED" /> 指定学生</label></div>
          <div v-if="audience==='SELECTED'" class="picker"><SchoolStudentPicker v-model="picked" multiple :school-id="school" :years="Object.keys(targets)" />
            <el-button v-if="eligiblePrevious.length" text type="primary" @click="ids=[...eligiblePrevious]">沿用上次名单（{{ eligiblePrevious.length }} 人）</el-button>
            <small>指定学生的过题只在比赛详情展示。</small></div>
        </section>
        <section><h2>发布方式</h2><div class="choices">
          <label><input v-model="timed" type="radio" :value="false" /> 立即发布</label><label><input v-model="timed" type="radio" :value="true" @change="initializePublishTime" /> 定时发布</label>
          </div><label v-if="timed" class="time">发布时间（北京时间） <input type="datetime-local" v-model="publishTime" aria-label="发布时间（北京时间）" /></label>
        </section>
      </fieldset>
      <aside><h2>配置预览</h2><strong class="preview-title">{{ meta?.title || '待识别比赛' }}</strong>
        <dl><dt>训练周</dt><dd>{{ week }} 至 {{ shiftDate(week,6) }}</dd>
          <dt>年级目标</dt><dd><div v-for="[y,n] in selected" :key="y">{{ y }} 级 · 任选 {{ n }} 题</div><span v-if="!selected.length">待选择</span></dd>
          <dt>开放范围</dt><dd>{{ audience==='ALL'?'全校开放':'指定学生' }}<div v-if="audience==='SELECTED'">{{ selectedNames.slice(0,3).join('、') || '待选择' }}{{ selectedNames.length>3?'等 '+selectedNames.length+' 名学生':'' }}</div></dd>
          <dt>发布时间</dt><dd>{{ timed ? publishTime.replace('T',' ') || '待选择' : '立即发布' }}</dd></dl>
        <div class="actions"><el-button type="primary" :loading="saving" :disabled="!valid || (timed && !publishTime)" @click="submit(timed?'SCHEDULED':'PUBLISHED')">{{ timed?'确认定时发布':'立即发布' }}</el-button>
        <el-button :disabled="!valid || saving" @click="submit('DRAFT')">保存草稿</el-button>
        <el-button text :disabled="!validRules || saving" @click="defaults">保存为默认配置</el-button>
        <el-button v-if="edit && editStatus !== 'PUBLISHED'" text type="danger" :disabled="saving" @click="discard">取消这场安排</el-button></div>
      </aside>
    </div>
  </main>
</template>
<style scoped>
.assignment-history{margin-top:10px;padding:10px 12px;border-left:3px solid var(--el-color-warning);background:var(--el-color-warning-light-9);font-size:14px}
.assignment-history ul{margin:4px 0 0;padding-left:18px}
.heading.link-heading{display:flex;align-items:center;gap:4px}
.link-help{display:inline-grid;place-items:center;width:28px;height:28px;border:0;background:transparent;color:var(--el-text-color-secondary);cursor:pointer;font-size:17px}
.link-help:hover,.link-help:focus-visible{color:var(--el-color-primary)}
.link-examples{margin:0}.link-examples dt{font-weight:600}.link-examples dt:not(:first-child){margin-top:8px}.link-examples dd{margin:0;overflow-wrap:anywhere;font-size:12px}
.weekly-editor{max-width:1150px;margin:auto;padding:8px 0 32px;color:var(--el-text-color-primary)}
.back{color:var(--el-text-color-secondary);text-decoration:none;font-size:14px}.weekly-editor header{margin:15px 0 23px;display:flex;align-items:baseline;gap:16px;flex-wrap:wrap}.weekly-editor h1{font-size:clamp(24px,3vw,34px);margin:0;letter-spacing:-.035em}
.weekly-editor header span,.secondary,.picker small,.weekly-editor dt{color:var(--el-text-color-secondary)}
.workspace{display:grid;grid-template-columns:minmax(0,1fr) minmax(250px,.43fr);gap:20px;align-items:start}
.form-panel,aside{background:var(--el-bg-color);border:1px solid var(--el-border-color-light);border-radius:12px}
.form-panel{margin:0;padding:0;min-width:0}.form-panel section,aside{padding:20px 23px}.form-panel section+section{border-top:1px solid var(--el-border-color-lighter)}
h2,.heading{display:block;margin:0 0 13px;font-size:16px;font-weight:650}.url,.choices,.year-row,.recognized,.time{display:flex;align-items:center;gap:10px}
.recognized{font-size:14px;margin-top:12px;color:var(--el-color-primary);padding:9px 12px;background:var(--el-color-primary-light-9);border-radius:6px}
.year-table{border-top:1px solid var(--el-border-color-light)}.year-row{justify-content:space-between;min-height:42px;border-bottom:1px solid var(--el-border-color-lighter)}.choices{flex-wrap:wrap;gap:24px}.choices label,.year-row label{display:flex;align-items:center;gap:8px;cursor:pointer}.weekly-editor input{accent-color:var(--el-color-primary)}.count,.time input{border:1px solid var(--el-border-color);border-radius:5px;padding:6px;color:inherit;background:var(--el-bg-color)}.count{width:64px;text-align:center}
.picker,.time{margin-top:14px}.picker small{display:block;margin-top:8px}.time{flex-wrap:wrap}aside{position:sticky;top:18px}aside h2{font-size:18px}.preview-title{display:block;margin-bottom:16px;overflow-wrap:anywhere}.weekly-editor dl{margin:0}.weekly-editor dt{font-size:13px;margin:14px 0 4px}.weekly-editor dd{margin:0;font-size:14px;line-height:1.6}.actions{margin-top:20px;padding-top:16px;border-top:1px solid var(--el-border-color);display:grid;gap:6px}.actions .el-button{margin:0}
@media(max-width:800px){.workspace{grid-template-columns:1fr}aside{position:static}.form-panel section,aside{padding:16px}.weekly-editor header{margin:12px 0}.url{align-items:stretch}}
</style>
