<script setup lang="ts">
import { ElMessage } from 'element-plus'
import { post, notifyError } from '@/api/client'
import { useEnrollmentYears } from '@/composables/useEnrollmentYears'
import { computed, ref, watch } from 'vue'
import type { SchoolMember } from '@/api/types'
import ResponsiveDialog from '@/components/ui/ResponsiveDialog.vue'
import ResponsiveList from '@/components/ui/ResponsiveList.vue'
import SectionCard from '@/components/ui/SectionCard.vue'
import StatusBadge from '@/components/ui/StatusBadge.vue'
import type { ListColumn } from '@/components/ui/types'

/**
 * 成员审批：按状态筛（有待审核时默认只看待审核），按昵称、用户名、学号搜，一页 20 条；
 * 待审核的可以通过或拒绝，已通过的可以拒绝，已拒绝的可以通过（和原来一样）。
 */
const props = defineProps<{ members: SchoolMember[]; loading?: boolean }>()
const emit = defineEmits<{ review: [id: number, approve: boolean]; updated: [] }>()

const editing = ref<SchoolMember | null>(null)
const saving = ref(false)
const { years } = useEnrollmentYears()
function editMember(id: number) {
  const member = props.members.find(item => item.id === id)
  if (member) editing.value = { ...member }
}
async function saveMember() {
  if (!editing.value || saving.value) return
  saving.value = true
  try {
    const { id, enrollmentYear, className, studentNo } = editing.value
    await post(`/admin/members/${id}/profile`, { enrollmentYear, className: className?.trim() || null, studentNo: studentNo?.trim() || null })
    editing.value = null
    emit('updated')
    ElMessage.success('成员资料已更新')
  } catch (e) { notifyError(e) } finally { saving.value = false }
}
const STATUS: Record<string, { label: string; tone: 'success' | 'warning' | 'danger' }> = {
  APPROVED: { label: '已通过', tone: 'success' },
  PENDING: { label: '待审核', tone: 'warning' },
  REJECTED: { label: '已拒绝', tone: 'danger' }
}
const count = (status: string) => (status ? props.members.filter((m) => m.status === status).length : props.members.length)
const status = ref(count('PENDING') ? 'PENDING' : '')
watch(
  () => props.members.length,
  () => {
    if (status.value === 'PENDING' && !count('PENDING')) status.value = ''
  }
)
const search = ref('')
const page = ref(1)
const PAGE_SIZE = 20
const filtered = computed(() => {
  const q = search.value.trim().toLowerCase()
  return props.members.filter((m) => (!status.value || m.status === status.value) && (!q || `${m.displayName ?? ''} ${m.username ?? ''} ${m.studentNo ?? ''}`.toLowerCase().includes(q)))
})
const paged = computed(() => filtered.value.slice((page.value - 1) * PAGE_SIZE, page.value * PAGE_SIZE))
watch([status, search], () => {
  page.value = 1
})
const FILTERS = [
  { value: 'PENDING', label: '待审核' },
  { value: '', label: '全部' },
  { value: 'APPROVED', label: '已通过' },
  { value: 'REJECTED', label: '已拒绝' }
]
const columns: ListColumn[] = [
  { key: 'name', label: '同学', minWidth: 150 },
  { key: 'cohort', label: '年级 / 班级', minWidth: 150 },
  { key: 'studentNo', label: '学号', width: 120 },
  { key: 'status', label: '状态', width: 100 },
  { key: 'appliedAt', label: '申请时间', width: 170 },
  { key: 'actions', label: '操作', width: 200 }
]
const formatTime = (value: string | null | undefined) => (value ? new Date(value).toLocaleString('zh-CN', { timeZone: 'Asia/Shanghai', hour12: false }) : '—')
const cohort = (m: SchoolMember) => [m.enrollmentYear ? `${m.enrollmentYear} 级` : '', m.className].filter(Boolean).join(' ') || '—'
</script>

<template>
  <SectionCard title="入校申请" :description="`共 ${members.length} 条，待审核 ${count('PENDING')} 条`" data-test="admin-members">
    <div class="mb-3 flex flex-wrap items-center gap-2">
      <div role="radiogroup" aria-label="审核状态" class="flex flex-wrap gap-2">
        <button
          v-for="f in FILTERS"
          :key="f.value"
          type="button"
          role="radio"
          class="filter-chip"
          :class="{ 'is-active': status === f.value }"
          :aria-checked="status === f.value"
          @click="status = f.value"
        >
          {{ f.label }}<span class="num">&nbsp;{{ count(f.value) }}</span>
        </button>
      </div>
      <el-input v-model="search" clearable placeholder="搜昵称、用户名、学号" class="!w-56" aria-label="搜同学">
        <template #prefix><span class="i-lucide-search" aria-hidden="true" /></template>
      </el-input>
    </div>
    <ResponsiveList :items="paged" :columns="columns" row-key="id" :loading="loading" :empty-text="status === 'PENDING' ? '没有待审核的申请' : '没有符合的申请'">
      <template #cell-name="{ row }">
        <span class="flex flex-col"><span class="text-ink">{{ row.displayName || '—' }}</span><span class="mono text-xs text-muted">{{ row.username }}</span></span>
      </template>
      <template #cell-cohort="{ row }">{{ cohort(row) }}</template>
      <template #cell-studentNo="{ row }"><span class="mono text-[13px]">{{ row.studentNo || '—' }}</span></template>
      <template #cell-status="{ row }"><StatusBadge variant="label" :tone="STATUS[row.status]?.tone ?? 'neutral'">{{ STATUS[row.status]?.label ?? row.status }}</StatusBadge></template>
      <template #cell-appliedAt="{ row }">{{ formatTime(row.appliedAt) }}</template>
      <template #cell-actions="{ row }">
        <span class="inline-flex flex-nowrap items-center">
          <el-button v-if="row.status !== 'APPROVED'" size="small" text type="primary" @click="emit('review', row.id, true)">通过</el-button>
          <el-button v-if="row.status !== 'REJECTED'" size="small" text type="danger" @click="emit('review', row.id, false)">拒绝</el-button>
          <el-button size="small" text @click="editMember(row.id)">改资料</el-button>
        </span>
      </template>
      <template #card="{ row }">
        <div class="flex flex-col gap-1.5">
          <div class="flex items-center gap-2">
            <span class="flex-1 text-sm text-ink">{{ row.displayName || '—' }} <span class="mono text-xs text-muted">{{ row.username }}</span></span>
            <StatusBadge variant="label" :tone="STATUS[row.status]?.tone ?? 'neutral'">{{ STATUS[row.status]?.label ?? row.status }}</StatusBadge>
          </div>
          <span class="text-xs text-muted">{{ cohort(row) }}<template v-if="row.studentNo"> · 学号 {{ row.studentNo }}</template> · {{ formatTime(row.appliedAt) }}</span>
          <div class="flex items-center">
            <el-button v-if="row.status !== 'APPROVED'" size="small" type="primary" @click="emit('review', row.id, true)">通过</el-button>
            <el-button v-if="row.status !== 'REJECTED'" size="small" type="danger" plain @click="emit('review', row.id, false)">拒绝</el-button>
            <el-button size="small" class="!ml-auto" @click="editMember(row.id)">改资料</el-button>
          </div>
        </div>
      </template>
    </ResponsiveList>
    <el-pagination v-if="filtered.length > PAGE_SIZE" v-model:current-page="page" class="mt-3 justify-end" layout="total, prev, pager, next" :pager-count="5" :page-size="PAGE_SIZE" :total="filtered.length" />
  </SectionCard>
  <ResponsiveDialog :model-value="!!editing" title="修改成员资料" :width="460" @update:model-value="(open) => { if (!open) editing = null }">
    <form v-if="editing" class="flex flex-col gap-4" @submit.prevent="saveMember">
      <p class="m-0 text-[13px] text-muted leading-[1.6]">
        {{ editing.displayName || editing.username }} 的年级、班级和学号。同学自己也能在「设置与安全」里改；按学校名单核对后再改，会留下修改记录。
      </p>
      <div class="grid grid-cols-1 gap-3 md:grid-cols-2">
        <label class="flex flex-col gap-1.5 text-[13px] text-body">
          入学年级
          <el-select v-model="editing.enrollmentYear" clearable placeholder="未填写">
            <el-option v-for="year in years" :key="year" :value="year" :label="`${year} 级`" />
          </el-select>
        </label>
        <label class="flex flex-col gap-1.5 text-[13px] text-body">
          学号
          <el-input v-model="editing.studentNo" maxlength="32" placeholder="未填写" />
        </label>
        <label class="flex flex-col gap-1.5 text-[13px] text-body md:col-span-2">
          班级
          <el-input v-model="editing.className" maxlength="64" placeholder="如 计算机应用 1 班" />
        </label>
      </div>
    </form>
    <template #footer>
      <el-button @click="editing = null">取消</el-button>
      <el-button type="primary" :loading="saving" @click="saveMember">保存</el-button>
    </template>
  </ResponsiveDialog>
</template>
