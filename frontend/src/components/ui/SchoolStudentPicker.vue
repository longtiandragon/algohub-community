<script setup lang="ts">
import { computed, onBeforeUnmount, ref, watch } from 'vue'
import { notifyError } from '@/api/client'
import { directory, type DirectoryStudent } from '@/api/studentDirectory'
import ResponsiveDialog from '@/components/ui/ResponsiveDialog.vue'

const props = defineProps<{ schoolId: number; modelValue: DirectoryStudent[]; multiple?: boolean; years?: string[]; restriction?: 'LEADER'; disabled?: boolean }>()
const emit = defineEmits<{ 'update:modelValue': [students: DirectoryStudent[]] }>()
const open = ref(false), search = ref(''), term = ref(''), year = ref<number>(), className = ref<string>()
const page = ref(1), selectedOnly = ref(false), loading = ref(false), bulk = ref(false), failed = ref(false)
const draft = ref(new Map<number, DirectoryStudent>())
const items = ref<DirectoryStudent[]>([]), total = ref(0)
const groups = ref<{year: number | null; className: string | null; count: number}[]>([])
const pageSize = 30
let request = 0, session = 0, timer: ReturnType<typeof setTimeout> | undefined
const label = computed(() => {
  const names = props.modelValue.map(s => s.remark || s.name)
  return names.slice(0,3).join('、') + (names.length > 3 ? '等 ' + names.length + ' 名学生' : '')
})
function eligible(s: DirectoryStudent) { return !props.years || props.years.includes(String(s.year)) }
const yearsList = computed(() => [...new Set(groups.value.flatMap(g => g.year == null ? [] : [g.year]))].sort((a,b)=>b-a))
const classes = computed(() => [...new Set(groups.value.filter(g=>year.value==null || g.year===year.value).map(g=>g.className || ''))])
const selectedRows = computed(() => [...draft.value.values()].filter(s => {
  const q = term.value.toLocaleLowerCase()
  return (year.value==null || s.year===year.value) && (className.value===undefined || className.value===(s.className||'')) &&
    (!q || [s.name,s.username,s.remark,s.studentNo,s.className].some(v=>v?.toLocaleLowerCase().includes(q)))
}))
const count = computed(() => selectedOnly.value ? selectedRows.value.length : total.value)
const pageCount = computed(() => Math.max(1,Math.ceil(count.value/pageSize)))
const visible = computed(() => selectedOnly.value ? selectedRows.value.slice((page.value-1)*pageSize,page.value*pageSize) : items.value)
const available = computed(() => visible.value.filter(eligible))
const pageSelected = computed(() => available.value.length>0 && available.value.every(s=>draft.value.has(s.userId)))
const visibleGroups = computed(() => {
  const result = new Map<string, DirectoryStudent[]>()
  for (const student of visible.value) {
    const key = JSON.stringify([student.year, student.className])
    const members = result.get(key)
    if (members) members.push(student)
    else result.set(key, [student])
  }
  return [...result.entries()]
})
function start() {
  session++; draft.value = new Map(props.modelValue.map(s=>[s.userId,s]))
  search.value=''; term.value=''; year.value=undefined; className.value=undefined; page.value=1; selectedOnly.value=false
  groups.value=[]; open.value=true
}
watch(search, q=>{clearTimeout(timer); timer=setTimeout(()=>{term.value=q.trim()},180)})
watch(year,()=>{className.value=undefined})
watch([term,year,className,selectedOnly],()=>{page.value=1})
watch(pageCount,n=>{if(selectedOnly.value && page.value>n)page.value=n})
watch(open,v=>{if(!v){session++; request++; bulk.value=false; clearTimeout(timer)}})
watch(()=>props.schoolId,()=>{open.value=false})
onBeforeUnmount(()=>{request++; session++; clearTimeout(timer)})
async function load() {
  const ticket=++request
  if(!open.value || selectedOnly.value) {loading.value=false; return}
  loading.value=true; failed.value=false; items.value=[]
  try {
    const result=await directory(props.schoolId,{page:page.value,size:pageSize,q:term.value,year:year.value,className:className.value,restriction:props.restriction})
    if(ticket!==request)return
    groups.value=result.groups; items.value=result.items; total.value=result.total
    if(page.value>pageCount.value)page.value=pageCount.value
  } catch(e){if(ticket===request){failed.value=true;notifyError(e)}}
  finally{if(ticket===request)loading.value=false}
}
watch([open,term,year,className,page,selectedOnly],load)
function choose(s: DirectoryStudent) {
  if(!eligible(s))return
  if(!props.multiple){emit('update:modelValue',[s]); open.value=false;return}
  if(draft.value.has(s.userId))draft.value.delete(s.userId)
  else draft.value.set(s.userId,s)
}
function togglePage() {
  const remove=pageSelected.value
  for(const s of available.value){if(remove)draft.value.delete(s.userId);else draft.value.set(s.userId,s)}
}
async function selectClass(student: DirectoryStudent) {
  const ticket = session, targetYear = student.year, targetClass = student.className
  if (targetYear == null || !targetClass || !eligible(student) || bulk.value) return
  bulk.value=true
  try {
    const records:DirectoryStudent[]=[]
    for(let n=1;;n++){
      const result=await directory(props.schoolId,{year:targetYear,className:targetClass,page:n,size:100,restriction:props.restriction})
      if(ticket!==session)return
      records.push(...result.items)
      if(result.items.length===0 || n*100>=result.total)break
    }
    for(const s of records)if(eligible(s))draft.value.set(s.userId,s)
  }catch(e){if(ticket===session)notifyError(e)}
  finally{if(ticket===session)bulk.value=false}
}
function accept(){emit('update:modelValue',[...draft.value.values()].filter(eligible)); open.value=false}
</script>
<template>
  <button class="picker-trigger" type="button" :disabled="disabled" aria-haspopup="dialog" @click="start"><i class="i-lucide-users" /> {{ label || '选择学生' }} <i class="i-lucide-chevron-down" /></button>
  <ResponsiveDialog v-model="open" title="选择本校学生" :width="740" fullscreen-on-mobile>
    <div class="filters">
      <el-input v-model="search" placeholder="备注名、昵称、用户名或学号" aria-label="搜索学生" clearable />
      <select v-model="year" aria-label="筛选年级"><option :value="undefined">全部年级</option><option v-for="y in yearsList" :key="y" :value="y">{{y}} 级</option></select>
      <select v-model="className" aria-label="筛选班级"><option :value="undefined">全部班级</option><option v-for="c in classes" :key="c" :value="c">{{c || '未填写班级'}}</option></select>
    </div>
    <div v-if="multiple" class="tools">
      <el-button text :disabled="!available.length || loading || bulk" @click="togglePage">{{pageSelected?'取消本页选择':'选中本页'}}</el-button>
      <el-button text :type="selectedOnly?'primary':undefined" @click="selectedOnly=!selectedOnly">{{selectedOnly?'查看全部':'查看已选'}}（{{draft.size}}）</el-button>
    </div>
    <div class="roster" v-loading="loading || bulk" :aria-busy="loading || bulk">
      <p v-if="failed && !selectedOnly">名单读取失败 <el-button text @click="load">重试</el-button></p>
      <section v-for="[key, members] in visibleGroups" :key="key">
        <div class="class-heading">
          <strong>{{ members[0].year ? members[0].year + ' 级' : '未填写年级' }} · {{ members[0].className || '未填写班级' }}</strong>
          <el-button v-if="multiple && members[0].className && members[0].year && eligible(members[0])" text size="small" :disabled="bulk" @click="selectClass(members[0])">选中整班</el-button>
        </div>
        <label v-for="s in members" :key="s.userId" :class="{muted:!eligible(s)}">
          <input :type="multiple?'checkbox':'radio'" :checked="draft.has(s.userId)" :disabled="!eligible(s) || bulk" @click="choose(s)" />
          <span><b>{{s.remark || s.name}}</b><small v-if="s.remark">{{s.name}}</small></span>
          <span>{{s.studentNo || '未填写学号'}}<small v-if="!eligible(s)">未启用该年级</small></span>
        </label>
      </section>
      <p v-if="!visible.length && !loading && (!failed || selectedOnly)">没有符合条件的学生</p>
    </div>
    <div class="pagination"><span>共 {{count}} 人</span><el-button size="small" :disabled="page<=1 || bulk" @click="page--">上一页</el-button><span>{{page}} / {{pageCount}}</span><el-button size="small" :disabled="page>=pageCount || bulk" @click="page++">下一页</el-button></div>
    <template #footer><div class="actions"><span v-if="multiple">已选 {{draft.size}} 人</span><el-button @click="open=false">取消</el-button><el-button v-if="multiple" type="primary" :disabled="bulk" @click="accept">完成</el-button></div></template>
  </ResponsiveDialog>
</template>
<style scoped>
.picker-trigger,.filters,.actions,.tools,.pagination{display:flex;align-items:center;gap:8px}
.picker-trigger,select{background:var(--el-bg-color);color:var(--el-text-color-primary);border:1px solid var(--el-border-color);border-radius:7px;padding:8px 10px}
.picker-trigger{width:100%;cursor:pointer;text-align:left}.picker-trigger i:last-child{margin-left:auto}
.filters .el-input{flex:1;min-width:140px}.filters select{max-width:34%}.tools{flex-wrap:wrap}.tools .el-button,.actions .el-button,.pagination .el-button{margin:0}
.roster{margin:8px 0;min-height:90px;max-height:min(48vh,440px);overflow:auto}.roster label{display:grid;grid-template-columns:20px minmax(80px,1.3fr) 1fr;align-items:center;gap:8px;padding:8px 4px;border-bottom:1px solid var(--el-border-color-lighter);cursor:pointer}
.class-heading{display:flex;align-items:center;justify-content:space-between;min-height:32px;padding:4px 9px;background:var(--el-fill-color-light);border-radius:5px}.class-heading strong{font-size:13px;font-weight:600}.roster label:hover{background:var(--el-fill-color-lighter)}
.roster input{accent-color:var(--el-color-primary)}.roster small{display:block;color:var(--el-text-color-secondary)}.roster span{overflow-wrap:anywhere}.muted{color:var(--el-text-color-secondary)}
.pagination,.actions{justify-content:flex-end}.pagination>span:first-child,.actions>span{margin-right:auto}.filters{flex-wrap:wrap}
@media(max-width:600px){.filters .el-input{flex-basis:100%}.filters select{max-width:48%}.roster label{font-size:13px;gap:5px}}
</style>
