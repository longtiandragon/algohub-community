<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { get, post, notifyError } from '@/api/client'
import ResponsiveList from '@/components/ui/ResponsiveList.vue'
import SectionCard from '@/components/ui/SectionCard.vue'
import type { ListColumn } from '@/components/ui/types'

interface Student {
  id: number; username: string; displayName: string; membershipId: number | null; schoolName: string | null
  membershipStatus: string; enrollmentYear: number | null; className: string | null; studentNo: string | null
  emailVerified: boolean; verifiedCount: number; accountStatus: string
  email: string | null; appliedAt: string | null; reviewedAt: string | null
}
interface Result { rows: Student[]; total: number; page: number; summary: Record<string, number> }
const emit = defineEmits<{ updated: [] }>()
const schools = ref<{ id: number; name: string }[]>([])
const filters = ref({ schoolId: 0, unaffiliated: false, q: '', year: null as number | null, className: '', membership: '', verified: '' })
const page = ref(1), size = ref(20), loading = ref(false)
const result = ref<Result | null>(null)
const editing = ref<Student | null>(null)
const saving = ref(false)
let request = 0
/** 系统给邮箱注册的同学自动生成 mail_xxx 登录名，管理员看不懂：有邮箱就显示邮箱 */
const identity = (row: Student) => row.email || row.username
/** 北京时间，精确到分钟 */
const at = (value: string | null) => value ? new Date(value).toLocaleString('zh-CN', { timeZone: 'Asia/Shanghai', year: 'numeric', month: 'numeric', day: 'numeric', hour: '2-digit', minute: '2-digit', hour12: false }) : ''
/** 申请时间；审核过的再写审核时间 */
const applyText = (row: Student) => !row.appliedAt ? '' : row.reviewedAt && row.membershipStatus !== 'PENDING'
  ? `申请 ${at(row.appliedAt)} · ${row.membershipStatus === 'APPROVED' ? '通过' : '审核'} ${at(row.reviewedAt)}`
  : `申请于 ${at(row.appliedAt)}`
const memberships: Record<string,string> = { APPROVED:'入校已认证', PENDING:'待审核', REJECTED:'已拒绝', NONE:'未申请学校' }
const summaryItems = [['total','学生人数'], ['approved','入校已认证'], ['unaffiliated','未绑定学校'], ['pending','待审核'], ['rejected','已拒绝'], ['none','从未申请学校'], ['emailVerified','邮箱已验证'], ['ojVerified','OJ 已验证']]
const scopeName = computed(() => filters.value.schoolId ? schools.value.find(s=>s.id===filters.value.schoolId)?.name : filters.value.unaffiliated ? '未加入学校的学生' : '全站学生')
async function load(reset = false) {
  if(reset) page.value=1
  const current=++request
  loading.value=true
  try {
    const data=await get<Result>('/admin/students',{...filters.value, year:filters.value.year || 0,page:page.value,size:size.value})
    if(current===request) { result.value=data; page.value=data.page }
  } catch(e) { if(current===request) notifyError(e) } finally { if(current===request) loading.value=false }
}
function reset() { filters.value={schoolId:0,unaffiliated:false,q:'',year:null,className:'',membership:'',verified:''}; void load(true) }
async function review(row: Student, approve: boolean) {
  try { await ElMessageBox.confirm(`${approve ? '通过' : '拒绝'} ${row.displayName} 的入校申请？`, '审核入校申请') } catch { return }
  try { await post(`/admin/members/${row.membershipId}/review`,{approve,reason:approve?null:'管理员审核未通过'}); ElMessage.success('审核完成'); await load(); emit('updated') } catch(e) { notifyError(e) }
}
async function save() {
  if(!editing.value || saving.value) return
  saving.value=true
  try {
    await post(`/admin/members/${editing.value.membershipId}/profile`, {enrollmentYear:editing.value.enrollmentYear, className:editing.value.className?.trim()||null, studentNo:editing.value.studentNo?.trim()||null})
    editing.value=null; ElMessage.success('资料已更新'); await load(); emit('updated')
  } catch(e) { notifyError(e) } finally { saving.value=false }
}
const columns: ListColumn[] = [
  {key:'student',label:'学生',minWidth:150},{key:'school',label:'学校 / 班级',minWidth:180},
  {key:'verification',label:'认证状态',minWidth:150},
  {key:'actions',label:'管理',minWidth:170}
]
onMounted(async()=>{ try { schools.value=await get('/admin/schools') } catch(e) { notifyError(e) } await load() })
</script>
<template>
  <SectionCard title="学生与学校认证" :description="`${scopeName}：查看学校归属、认证状态和成员资料`">
    <div class="filters mb-4">
      <label>学校<el-select v-model="filters.schoolId" filterable @change="filters.unaffiliated=false; load(true)"><el-option :value="0" label="全部学校 / 未入校" /><el-option v-for="s in schools" :key="s.id" :value="s.id" :label="s.name" /></el-select></label>
      <label>入校状态<el-select v-model="filters.membership" placeholder="全部状态" @change="load(true)"><el-option value="" label="全部状态" /><el-option v-for="(label,key) in memberships" :key="key" :value="key" :label="label" /></el-select></label>
      <label>OJ 认证<el-select v-model="filters.verified" placeholder="全部" @change="load(true)"><el-option value="" label="全部" /><el-option value="YES" label="已验证 OJ 账号" /><el-option value="NO" label="尚无已验证账号" /></el-select></label>
      <label>入学年级<el-input-number v-model="filters.year" :min="2000" :max="2100" :controls="false" placeholder="全部年级" @change="load(true)" /></label>
      <label>班级<el-input v-model="filters.className" clearable placeholder="完整班级名称" @keyup.enter="load(true)" @clear="load(true)" /></label>
      <label class="search">搜索学生<el-input v-model="filters.q" clearable placeholder="昵称、邮箱、学号" @keyup.enter="load(true)" @clear="load(true)" /></label>
      <div class="flex items-end gap-2"><el-button type="primary" :loading="loading" @click="load(true)">查询</el-button><el-button @click="reset">重置</el-button></div>
    </div>
    <el-checkbox v-model="filters.unaffiliated" :disabled="!!filters.schoolId" @change="load(true)">只看尚未通过入校认证的学生（含待审核）</el-checkbox>
    <div class="summary my-4" :aria-busy="loading">
      <div v-for="[key,label] in summaryItems" :key="key" class="summary-item"><span>{{ label }}</span><strong>{{ result?.summary[key] ?? '—' }}</strong></div>
    </div>
    <p class="text-xs text-muted leading-5">“入校已认证”指入校申请已通过；“未绑定学校”包括待审核、已拒绝和从未申请学校。人数随学校、年级、班级、入校状态和搜索条件变化；OJ 认证筛选只作用于下方列表。管理员不计入学生人数。</p>
    <ResponsiveList :items="result?.rows ?? []" :columns="columns" row-key="id" :loading="loading" empty-text="没有符合条件的学生">
      <template #cell-student="{row}"><strong>{{row.displayName}}</strong><div class="text-xs text-muted break-all">{{identity(row)}} · {{row.accountStatus==='ACTIVE'?'正常':'已停用'}}</div></template>
      <template #cell-school="{row}"><div>{{row.schoolName || '未申请学校'}}</div><div class="text-xs text-muted">{{row.enrollmentYear || '未填年级'}} / {{row.className || '未填班级'}} / {{row.studentNo || '未填学号'}}</div></template>
      <template #cell-verification="{row}"><div>{{memberships[row.membershipStatus]}}</div><div v-if="row.appliedAt" class="text-xs" :class="row.membershipStatus==='PENDING' ? 'text-warning-ink' : 'text-muted'">{{applyText(row)}}</div><div class="text-xs text-muted">邮箱{{row.emailVerified?'已验证':'未验证'}} · 已验证 OJ {{row.verifiedCount}} 个</div></template>
      <template #cell-actions="{row}"><template v-if="row.membershipId"><el-button v-if="row.membershipStatus!=='APPROVED'" text type="primary" @click="review(row,true)">通过</el-button><el-button v-if="row.membershipStatus!=='REJECTED'" text type="danger" @click="review(row,false)">拒绝</el-button><el-button text @click="editing={...row}">改资料</el-button></template><span v-else class="text-xs text-muted">尚未申请入校</span></template>
      <template #card="{row}"><strong>{{row.displayName}}</strong> <span class="text-xs break-all">{{identity(row)}}</span><p>{{row.schoolName||'从未申请学校'}} · {{memberships[row.membershipStatus]}}</p><p v-if="row.appliedAt" class="text-xs text-muted">{{applyText(row)}}</p><p>{{row.enrollmentYear||'未填年级'}} / {{row.className||'未填班级'}} / {{row.studentNo||'未填学号'}}</p><p>邮箱{{row.emailVerified?'已验证':'未验证'}} · 已验证 OJ {{row.verifiedCount}} 个</p><template v-if="row.membershipId"><el-button v-if="row.membershipStatus!=='APPROVED'" @click="review(row,true)">通过</el-button><el-button v-if="row.membershipStatus!=='REJECTED'" @click="review(row,false)">拒绝</el-button><el-button @click="editing={...row}">改资料</el-button></template></template>
    </ResponsiveList>
    <div class="mt-4 flex flex-wrap items-center justify-between gap-3"><label class="text-sm">每页 <el-select v-model="size" class="!w-24" @change="load(true)"><el-option v-for="n in [10,20,50,100]" :key="n" :value="n" :label="`${n} 条`" /></el-select></label><el-pagination v-model:current-page="page" :page-size="size" :total="result?.total ?? 0" :pager-count="5" layout="total, prev, pager, next" @current-change="load()" /></div>
  </SectionCard>
  <el-dialog :model-value="!!editing" title="编辑学校成员资料" width="min(480px,94vw)" @close="editing=null">
    <el-form v-if="editing" label-position="top"><el-form-item label="年级"><el-input-number v-model="editing.enrollmentYear" :min="2000" :max="2100" /></el-form-item><el-form-item label="班级"><el-input v-model="editing.className" maxlength="64" /></el-form-item><el-form-item label="学号"><el-input v-model="editing.studentNo" maxlength="32" /></el-form-item></el-form>
    <template #footer><el-button @click="editing=null">取消</el-button><el-button type="primary" :loading="saving" @click="save">保存</el-button></template>
  </el-dialog>
</template>
<style scoped>
.filters { display:grid; grid-template-columns:repeat(4,minmax(0,1fr)); gap:12px; }
.filters label { display:flex; flex-direction:column; gap:6px; font-size:13px; }
.summary { display:grid; grid-template-columns:repeat(4,minmax(0,1fr)); gap:10px; }
.summary-item { display:flex; flex-direction:column; gap:8px; padding:12px; border:1px solid var(--border); border-radius:var(--radius); background:var(--bg-subtle); font-size:12px; }
.summary-item strong { font-size:24px; font-variant-numeric:tabular-nums; color:var(--text-strong); }
@media(max-width:900px) { .filters {grid-template-columns:repeat(2,minmax(0,1fr));} .summary {grid-template-columns:repeat(3,minmax(0,1fr));} }
@media(max-width:480px) { .summary {grid-template-columns:repeat(2,minmax(0,1fr));} }
</style>
