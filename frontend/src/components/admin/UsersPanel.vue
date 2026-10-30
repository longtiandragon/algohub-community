<script setup lang="ts">
import { computed, onBeforeUnmount, onMounted, reactive, ref } from 'vue'
import { get, notifyError } from '@/api/client'
import {
  MEMBERSHIP_STATUS,
  USER_STATUS,
  fetchUsers,
  formatTime,
  loadAdminMe,
  type AdminUserPage,
  type AdminUserRow,
  accountLabel
} from '@/api/adminUsers'
import UserDetailDrawer from '@/components/admin/UserDetailDrawer.vue'
import ResponsiveList from '@/components/ui/ResponsiveList.vue'
import SectionCard from '@/components/ui/SectionCard.vue'
import StatusBadge from '@/components/ui/StatusBadge.vue'
import UserAvatar from '@/components/ui/UserAvatar.vue'
import type { ListColumn } from '@/components/ui/types'
import { avatarTone } from '@/utils/avatar'
import { relativeTime } from '@/utils/statistics'

/**
 * 用户管理：全站所有账号（学生、学校管理员和平台管理员）。上面六个数点了就筛；
 * 搜登录名、昵称、邮箱、学号，按角色、状态、学校筛，按最近注册或最近活跃排；电脑表格、手机卡片，一页 20 条。
 * 点一行打开详情（UserDetailDrawer），禁用、启用、强制下线、设为管理员都在详情里做。
 */
const props = defineProps<{
  /** AdminView 给的当前管理员权限；不给就看 /admin/me */
  permissions?: readonly string[] | null
}>()

const filters = reactive({ q: '', role: '', status: '', schoolId: 0, sort: 'created' })
const page = ref(1)
const size = ref(20)
const loading = ref(false)
const result = ref<AdminUserPage | null>(null)
const schools = ref<{ id: number; name: string }[]>([])
const openId = ref<number | null>(null)
let request = 0

async function load(reset = false) {
  if (reset) page.value = 1
  const current = ++request
  loading.value = true
  try {
    const data = await fetchUsers({ ...filters, q: filters.q.trim(), page: page.value, size: size.value })
    if (current === request) {
      result.value = data
      page.value = data.page
    }
  } catch (e) {
    if (current === request) notifyError(e)
  } finally {
    if (current === request) loading.value = false
  }
}

/** 搜索框边打边搜，停 300 毫秒再发 */
let typing: ReturnType<typeof setTimeout> | undefined
function onSearch() {
  clearTimeout(typing)
  typing = setTimeout(() => load(true), 300)
}
onBeforeUnmount(() => clearTimeout(typing))

onMounted(async () => {
  void loadAdminMe().catch(() => undefined)
  // 学校列表归「学校与成员」权限；没有这个权限就不显示学校筛选
  get<{ id: number; name: string }[]>('/admin/schools')
    .then((list) => (schools.value = list))
    .catch(() => (schools.value = []))
  await load()
})

const SUMMARY = [
  { key: 'total', label: '全部用户', apply: { role: '', status: '', sort: 'created' } },
  { key: 'students', label: '学生', apply: { role: 'STUDENT', status: '', sort: 'created' } },
  { key: 'schoolAdmins', label: '学校管理员', apply: { role: 'SCHOOL_ADMIN', status: '', sort: 'created' } },
  { key: 'admins', label: '平台管理员', apply: { role: 'ADMIN', status: '', sort: 'created' } },
  { key: 'disabled', label: '已禁用', apply: { role: '', status: 'DISABLED', sort: 'created' } },
  { key: 'activeToday', label: '今日活跃', apply: { role: '', status: '', sort: 'lastSeen' } }
] as const
type SummaryKey = (typeof SUMMARY)[number]['key']
const activeSummary = computed<SummaryKey | null>(() => {
  if (filters.q || filters.schoolId) return null
  if (filters.sort === 'lastSeen') return !filters.role && !filters.status ? 'activeToday' : null
  if (filters.status === 'DISABLED') return filters.role ? null : 'disabled'
  if (filters.status) return null
  return filters.role === 'STUDENT' ? 'students' : filters.role === 'ADMIN' ? 'admins' : filters.role === 'SCHOOL_ADMIN' ? 'schoolAdmins' : 'total'
})
function applySummary(item: (typeof SUMMARY)[number]) {
  Object.assign(filters, item.apply, { q: '', schoolId: 0 })
  void load(true)
}

const ROLES = [
  { value: '', label: '全部' },
  { value: 'STUDENT', label: '学生' },
  { value: 'SCHOOL_ADMIN', label: '学校管理员' },
  { value: 'ADMIN', label: '平台管理员' }
]
function setRole(value: string) {
  filters.role = value
  void load(true)
}
const filtered = computed(() => !!(filters.q || filters.role || filters.status || filters.schoolId))
function resetFilters() {
  Object.assign(filters, { q: '', role: '', status: '', schoolId: 0, sort: 'created' })
  void load(true)
}

const columns: ListColumn[] = [
  { key: 'user', label: '用户', minWidth: 220 },
  { key: 'role', label: '身份', width: 150 },
  { key: 'school', label: '学校', minWidth: 190 },
  { key: 'accounts', label: '平台账号', width: 100, align: 'center' },
  { key: 'lastSeen', label: '最近活跃', width: 120 },
  { key: 'createdAt', label: '注册', width: 110 },
  { key: 'actions', label: '', width: 76, align: 'right' }
]

const ago = (value: string | null) => (value ? relativeTime(value) : '从未')
const day = (value: string) => new Date(value).toLocaleDateString('zh-CN', { timeZone: 'Asia/Shanghai' })
const cohort = (row: AdminUserRow) => [row.enrollmentYear ? `${row.enrollmentYear} 级` : '', row.className].filter(Boolean).join(' ')
const roleLabel = (row: AdminUserRow) => (row.role === 'ADMIN' ? (row.adminPermissions ? '平台管理员 · 部分权限' : '平台管理员 · 全部权限') : row.schoolAdmin ? '学校管理员' : '学生')
</script>

<template>
  <SectionCard
    title="用户管理"
    description="区分学生、学校管理员和平台管理员。学校管理员只管理授权学校，平台管理员的权限高于学校管理员。"
    data-test="admin-users"
  >
    <div class="summary" :aria-busy="loading">
      <button
        v-for="item in SUMMARY"
        :key="item.key"
        type="button"
        class="summary-item"
        :class="{ 'is-active': activeSummary === item.key, 'is-danger': item.key === 'disabled' && (result?.summary.disabled ?? 0) > 0 }"
        :aria-pressed="activeSummary === item.key"
        :data-test="`admin-users-summary-${item.key}`"
        @click="applySummary(item)"
      >
        <span class="summary-label">{{ item.label }}</span>
        <span class="summary-value num">{{ result?.summary[item.key] ?? '—' }}</span>
      </button>
    </div>

    <div class="mt-4 flex flex-wrap items-center gap-2">
      <el-input
        v-model="filters.q"
        clearable
        placeholder="搜登录名、昵称、邮箱、学号"
        class="search"
        aria-label="搜索用户"
        data-test="admin-users-search"
        @input="onSearch"
        @keyup.enter="load(true)"
        @clear="load(true)"
      >
        <template #prefix><span class="i-lucide-search" aria-hidden="true" /></template>
      </el-input>
      <div role="radiogroup" aria-label="身份" class="flex flex-wrap gap-2">
        <button
          v-for="r in ROLES"
          :key="r.value"
          type="button"
          role="radio"
          class="filter-chip"
          :class="{ 'is-active': filters.role === r.value }"
          :aria-checked="filters.role === r.value"
          @click="setRole(r.value)"
        >
          {{ r.label }}
        </button>
      </div>
      <el-select v-model="filters.status" class="!w-30" aria-label="账号状态" placeholder="全部状态" @change="load(true)">
        <el-option value="" label="全部状态" />
        <el-option value="ACTIVE" label="正常" />
        <el-option value="DISABLED" label="已禁用" />
      </el-select>
      <el-select v-if="schools.length" v-model="filters.schoolId" filterable class="!w-44" aria-label="学校" @change="load(true)">
        <el-option :value="0" label="全部学校" />
        <el-option v-for="s in schools" :key="s.id" :value="s.id" :label="s.name" />
      </el-select>
      <el-select v-model="filters.sort" class="!w-32" aria-label="排序" @change="load(true)">
        <el-option value="created" label="最近注册" />
        <el-option value="lastSeen" label="最近活跃" />
      </el-select>
      <el-button v-if="filtered" text @click="resetFilters">清除筛选</el-button>
    </div>

    <div class="mt-4">
      <ResponsiveList :items="result?.rows ?? []" :columns="columns" row-key="id" :loading="loading && !result" :empty-text="filtered ? '没有符合条件的用户' : '还没有用户'">
        <template #cell-user="{ row }">
          <button type="button" class="user-cell" :data-test="`admin-user-${row.username}`" @click="openId = row.id">
            <UserAvatar :src="row.avatarUrl" :name="row.displayName" class="avatar" :class="avatarTone(row.id)" />
            <span class="min-w-0 flex flex-col">
              <span class="truncate text-[13.5px] text-ink font-semibold">{{ row.displayName }}</span>
              <span class="truncate text-xs text-muted">{{ accountLabel(row) }}</span>
            </span>
          </button>
        </template>
        <template #cell-role="{ row }">
          <span class="flex flex-col items-start gap-1">
            <StatusBadge variant="label" :tone="row.role === 'ADMIN' ? 'brand' : 'neutral'">{{ roleLabel(row) }}</StatusBadge>
            <StatusBadge v-if="row.status !== 'ACTIVE'" :tone="USER_STATUS[row.status]?.tone ?? 'neutral'">{{ USER_STATUS[row.status]?.label ?? row.status }}</StatusBadge>
          </span>
        </template>
        <template #cell-school="{ row }">
          <span v-if="row.schoolName" class="flex flex-col">
            <span class="flex min-w-0 items-center gap-1.5">
              <span class="truncate text-[13.5px] text-body">{{ row.schoolName }}</span>
              <StatusBadge v-if="row.membershipStatus && row.membershipStatus !== 'APPROVED'" variant="label" :tone="MEMBERSHIP_STATUS[row.membershipStatus]?.tone ?? 'neutral'">
                {{ MEMBERSHIP_STATUS[row.membershipStatus]?.label ?? row.membershipStatus }}
              </StatusBadge>
            </span>
            <span v-if="cohort(row)" class="text-xs text-muted">{{ cohort(row) }}</span>
          </span>
          <span v-else class="text-[13px] text-muted">{{ row.role === 'ADMIN' ? '—' : '未加入学校' }}</span>
        </template>
        <template #cell-accounts="{ row }">
          <span class="num text-[13.5px]" :title="`已验证归属 ${row.verifiedAccountCount} 个，共绑定 ${row.accountCount} 个`">
            <span class="text-ink">{{ row.verifiedAccountCount }}</span><span class="text-muted"> / {{ row.accountCount }}</span>
          </span>
        </template>
        <template #cell-lastSeen="{ row }"><span class="text-[13px]" :title="formatTime(row.lastSeenAt)">{{ ago(row.lastSeenAt) }}</span></template>
        <template #cell-createdAt="{ row }"><span class="num text-[13px] text-muted" :title="formatTime(row.createdAt)">{{ day(row.createdAt) }}</span></template>
        <template #cell-actions="{ row }">
          <el-button text size="small" @click="openId = row.id">详情</el-button>
        </template>
        <template #card="{ row }">
          <button type="button" class="card-button" :data-test="`admin-user-card-${row.username}`" @click="openId = row.id">
            <UserAvatar :src="row.avatarUrl" :name="row.displayName" class="avatar" :class="avatarTone(row.id)" />
            <span class="min-w-0 flex flex-1 flex-col gap-1">
              <span class="flex min-w-0 items-center gap-2">
                <span class="truncate text-sm text-ink font-semibold">{{ row.displayName }}</span>
                <StatusBadge variant="label" :tone="row.role === 'ADMIN' ? 'brand' : 'neutral'">{{ row.role === 'ADMIN' ? '平台管理员' : row.schoolAdmin ? '学校管理员' : '学生' }}</StatusBadge>
                <StatusBadge v-if="row.status !== 'ACTIVE'" variant="label" :tone="USER_STATUS[row.status]?.tone ?? 'neutral'">{{ USER_STATUS[row.status]?.label ?? row.status }}</StatusBadge>
              </span>
              <span class="truncate text-xs text-muted">{{ accountLabel(row) }}<template v-if="row.schoolName"> · {{ row.schoolName }}<template v-if="cohort(row)"> {{ cohort(row) }}</template></template></span>
              <span class="text-xs text-muted">最近活跃 {{ ago(row.lastSeenAt) }} · 已验证账号 {{ row.verifiedAccountCount }}/{{ row.accountCount }}</span>
            </span>
            <span class="i-lucide-chevron-right shrink-0 text-lg text-faint" aria-hidden="true" />
          </button>
        </template>
      </ResponsiveList>
    </div>

    <div v-if="(result?.total ?? 0) > 0" class="mt-4 flex flex-wrap items-center justify-between gap-3">
      <span class="text-[13px] text-muted">共 {{ result?.total }} 位{{ filtered ? '符合条件的' : '' }}用户</span>
      <div class="flex flex-wrap items-center gap-3">
        <el-select v-model="size" class="!w-28" aria-label="每页条数" @change="load(true)">
          <el-option v-for="n in [20, 50, 100]" :key="n" :value="n" :label="`每页 ${n} 条`" />
        </el-select>
        <el-pagination v-model:current-page="page" :page-size="size" :total="result?.total ?? 0" :pager-count="5" layout="prev, pager, next" @current-change="load()" />
      </div>
    </div>
  </SectionCard>

  <UserDetailDrawer :user-id="openId" :permissions="props.permissions" @close="openId = null" @updated="load()" />
</template>

<style scoped>
.summary {
  display: grid;
  grid-template-columns: repeat(6, minmax(0, 1fr));
  gap: 10px;
}

.summary-item {
  display: flex;
  flex-direction: column;
  align-items: flex-start;
  gap: 4px;
  padding: 12px 14px;
  border: 1px solid var(--border);
  border-radius: 12px;
  background: var(--bg-elevated);
  color: inherit;
  font: inherit;
  text-align: left;
  cursor: pointer;
}

.summary-item:hover {
  border-color: var(--border-strong);
}

.summary-item.is-active {
  border-color: color-mix(in srgb, var(--brand-500) 45%, var(--border));
  background: color-mix(in srgb, var(--brand-soft) 50%, var(--bg-elevated));
}

.summary-item:focus-visible {
  outline: 2px solid var(--brand-500);
  outline-offset: 2px;
}

.summary-label {
  color: var(--text-muted);
  font-size: 12.5px;
}

.summary-value {
  color: var(--text-strong);
  font-size: 24px;
  font-weight: 650;
  line-height: 30px;
}

.summary-item.is-danger .summary-value {
  color: var(--danger-ink);
}

.search {
  width: 260px;
}

.avatar {
  display: flex;
  flex-shrink: 0;
  align-items: center;
  justify-content: center;
  width: 34px;
  height: 34px;
  border-radius: 50%;
  font-size: 14px;
  font-weight: 600;
}

.user-cell,
.card-button {
  display: flex;
  align-items: center;
  gap: 10px;
  width: 100%;
  min-width: 0;
  padding: 0;
  border: 0;
  background: none;
  color: inherit;
  font: inherit;
  text-align: left;
  cursor: pointer;
}

.user-cell:hover .text-ink {
  color: var(--brand-600);
}

.user-cell:focus-visible,
.card-button:focus-visible {
  border-radius: 8px;
  outline: 2px solid var(--brand-500);
  outline-offset: 2px;
}

.card-button .avatar {
  width: 40px;
  height: 40px;
  font-size: 16px;
}

@media (max-width: 1023px) {
  .summary {
    grid-template-columns: repeat(3, minmax(0, 1fr));
  }
}

@media (max-width: 767px) {
  /* 六项统计在手机上两列排列 */
  .summary {
    grid-template-columns: repeat(2, minmax(0, 1fr));
  }

  .search {
    width: 100%;
  }
}
</style>
