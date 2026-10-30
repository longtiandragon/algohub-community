<script setup lang="ts">
import { computed, onBeforeUnmount, onMounted, ref } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { notifyError } from '@/api/client'
import {
  fetchAdmins,
  fetchUsers,
  formatTime,
  isKeyAdmin,
  loadAdminMe,
  permissionCatalog,
  permissionName,
  saveAdmin,
  type AdminPermission,
  type AdminRow,
  type AdminUserRow,
  accountLabel
} from '@/api/adminUsers'
import AdminPermissionDialog from '@/components/admin/AdminPermissionDialog.vue'
import ResponsiveDialog from '@/components/ui/ResponsiveDialog.vue'
import ResponsiveList from '@/components/ui/ResponsiveList.vue'
import SchoolAdminGrants from '@/components/admin/SchoolAdminGrants.vue'
import SectionCard from '@/components/ui/SectionCard.vue'
import StatusBadge from '@/components/ui/StatusBadge.vue'
import UserAvatar from '@/components/ui/UserAvatar.vue'
import type { ListColumn } from '@/components/ui/types'
import { useAuthStore } from '@/stores/auth'
import { avatarTone } from '@/utils/avatar'
import { relativeTime } from '@/utils/statistics'

/**
 * 管理员与权限：列出全部管理员（全部权限 / 部分权限、有没有设密码、最近登录），可以添加、调整权限、取消管理员。
 * 自己那一行不能改；系统至少要留一个「正常、全部权限或有『管理员与权限』」的管理员，最后一个的调整和取消置灰（后端也会拦）。
 * 添加管理员：先按登录名、昵称或邮箱找到这位同学，再选全部权限或勾选几项（AdminPermissionDialog）。
 */
const auth = useAuthStore()
const admins = ref<AdminRow[]>([])
const loading = ref(false)

async function load() {
  loading.value = true
  try {
    admins.value = await fetchAdmins()
  } catch (e) {
    notifyError(e)
  } finally {
    loading.value = false
  }
}
onMounted(() => {
  void loadAdminMe().catch(() => undefined)
  void load()
})

const keyAdmins = computed(() => admins.value.filter(isKeyAdmin))
/** 这一行为什么不能改；能改时为 null */
function lockReason(row: AdminRow): string | null {
  if (row.id === auth.me?.id) return '不能修改自己的权限，需要时请另一位管理员操作'
  if (isKeyAdmin(row) && keyAdmins.value.length <= 1) return '至少要保留一位拥有全部权限（或「管理员与权限」）的管理员'
  return null
}

/* ------------------------------ 调整、取消 ------------------------------ */
const editing = ref<AdminRow | null>(null)
const editOpen = computed({
  get: () => !!editing.value,
  set: (open: boolean) => {
    if (!open) editing.value = null
  }
})

async function revoke(row: AdminRow) {
  try {
    await ElMessageBox.confirm(
      `${row.displayName} 会变回普通用户，所有设备立即退出；他的数据都保留，以后可以再设为管理员。`,
      '取消管理员',
      { confirmButtonText: '取消管理员', cancelButtonText: '先不', type: 'warning' }
    )
  } catch {
    return
  }
  try {
    await saveAdmin(row.id, 'STUDENT', null)
    ElMessage.success(`已取消 ${row.displayName} 的管理员身份`)
    await load()
  } catch (e) {
    notifyError(e)
  }
}

/* ------------------------------ 添加管理员 ------------------------------ */
const adding = ref(false)
const query = ref('')
const searching = ref(false)
const candidates = ref<AdminUserRow[]>([])
const searched = ref(false)
const promoting = ref<AdminUserRow | null>(null)
const promoteOpen = computed({
  get: () => !!promoting.value,
  set: (open: boolean) => {
    if (!open) promoting.value = null
  }
})
let typing: ReturnType<typeof setTimeout> | undefined
let request = 0

function openAdd() {
  query.value = ''
  candidates.value = []
  searched.value = false
  adding.value = true
}
function onQuery() {
  clearTimeout(typing)
  typing = setTimeout(search, 300)
}
onBeforeUnmount(() => clearTimeout(typing))
async function search() {
  const q = query.value.trim()
  if (!q) {
    candidates.value = []
    searched.value = false
    return
  }
  const current = ++request
  searching.value = true
  try {
    const page = await fetchUsers({ q, role: 'STUDENT', status: 'ACTIVE', page: 1, size: 8 })
    if (current === request) {
      candidates.value = page.rows
      searched.value = true
    }
  } catch (e) {
    if (current === request) notifyError(e)
  } finally {
    if (current === request) searching.value = false
  }
}
function pick(user: AdminUserRow) {
  adding.value = false
  promoting.value = user
}

/* ------------------------------ 显示 ------------------------------ */
const columns: ListColumn[] = [
  { key: 'admin', label: '管理员', minWidth: 220 },
  { key: 'permissions', label: '权限', minWidth: 260 },
  { key: 'password', label: '管理员密码', width: 120 },
  { key: 'lastLogin', label: '最近登录', width: 120 },
  { key: 'actions', label: '', width: 180, align: 'right' }
]
const ago = (value: string | null) => (value ? relativeTime(value) : '从未')
const permissionList = (row: AdminRow) => (row.permissions ?? []).map((code: AdminPermission) => permissionName(code))
const catalog = computed(() => permissionCatalog())
const showCatalog = ref(false)
</script>

<template>
  <SectionCard
    title="平台管理员与权限"
    description="管理员从单独的管理员入口用密码登录。「全部权限」能做所有管理操作；「部分权限」只能进入勾选的几项。系统至少保留一位全权管理员。"
    data-test="admin-admins"
  >
    <template #actions>
      <el-button type="primary" data-test="admin-add" @click="openAdd"><span class="i-lucide-user-plus mr-1.5" aria-hidden="true" />添加管理员</el-button>
    </template>

    <ResponsiveList :items="admins" :columns="columns" row-key="id" :loading="loading && !admins.length" empty-text="还没有管理员">
      <template #cell-admin="{ row }">
        <span class="flex min-w-0 items-center gap-2.5">
          <UserAvatar :name="row.displayName" class="avatar" :class="avatarTone(row.id)" />
          <span class="min-w-0 flex flex-col">
            <span class="flex items-center gap-1.5">
              <span class="truncate text-[13.5px] text-ink font-semibold">{{ row.displayName }}</span>
              <StatusBadge v-if="row.id === auth.me?.id" variant="label" tone="info">你</StatusBadge>
              <StatusBadge v-if="row.status !== 'ACTIVE'" variant="label" tone="danger">已禁用</StatusBadge>
            </span>
            <span class="truncate text-xs text-muted">{{ accountLabel(row) }}</span>
          </span>
        </span>
      </template>
      <template #cell-permissions="{ row }">
        <StatusBadge v-if="row.full" variant="label" tone="brand">全部权限</StatusBadge>
        <span v-else class="flex flex-wrap gap-1">
          <span v-for="name in permissionList(row)" :key="name" class="perm-chip">{{ name }}</span>
          <span v-if="!permissionList(row).length" class="text-[13px] text-muted">没有任何权限</span>
        </span>
      </template>
      <template #cell-password="{ row }">
        <StatusBadge :tone="row.hasPassword ? 'success' : 'warning'">{{ row.hasPassword ? '已设置' : '还没设置' }}</StatusBadge>
      </template>
      <template #cell-lastLogin="{ row }"><span class="text-[13px]" :title="formatTime(row.lastLoginAt)">{{ ago(row.lastLoginAt) }}</span></template>
      <template #cell-actions="{ row }">
        <el-tooltip :disabled="!lockReason(row)" :content="lockReason(row) ?? ''" placement="top">
          <span class="inline-flex flex-nowrap items-center">
            <el-button size="small" text type="primary" :disabled="!!lockReason(row)" :data-test="`admin-edit-${row.username}`" @click="editing = row">调整权限</el-button>
            <el-button size="small" text type="danger" :disabled="!!lockReason(row)" :data-test="`admin-revoke-${row.username}`" @click="revoke(row)">取消管理员</el-button>
          </span>
        </el-tooltip>
      </template>
      <template #card="{ row }">
        <div class="flex flex-col gap-2">
          <div class="flex items-center gap-2.5">
            <UserAvatar :name="row.displayName" class="avatar" :class="avatarTone(row.id)" />
            <span class="min-w-0 flex flex-1 flex-col">
              <span class="flex items-center gap-1.5">
                <span class="truncate text-sm text-ink font-semibold">{{ row.displayName }}</span>
                <StatusBadge v-if="row.id === auth.me?.id" variant="label" tone="info">你</StatusBadge>
                <StatusBadge v-if="row.status !== 'ACTIVE'" variant="label" tone="danger">已禁用</StatusBadge>
              </span>
              <span class="truncate text-xs text-muted">{{ accountLabel(row) }} · 最近登录 {{ ago(row.lastLoginAt) }}</span>
            </span>
          </div>
          <span class="flex flex-wrap items-center gap-1">
            <StatusBadge v-if="row.full" variant="label" tone="brand">全部权限</StatusBadge>
            <template v-else>
              <span v-for="name in permissionList(row)" :key="name" class="perm-chip">{{ name }}</span>
            </template>
            <StatusBadge :tone="row.hasPassword ? 'success' : 'warning'" class="ml-auto">{{ row.hasPassword ? '已设密码' : '还没设密码' }}</StatusBadge>
          </span>
          <p v-if="lockReason(row)" class="m-0 text-xs text-muted">{{ lockReason(row) }}</p>
          <div v-else class="flex items-center gap-2">
            <el-button size="small" @click="editing = row">调整权限</el-button>
            <el-button size="small" type="danger" plain @click="revoke(row)">取消管理员</el-button>
          </div>
        </div>
      </template>
    </ResponsiveList>

    <div class="mt-4">
      <button type="button" class="catalog-toggle" :aria-expanded="showCatalog" @click="showCatalog = !showCatalog">
        <span class="i-lucide-info text-[15px]" aria-hidden="true" />各项权限能做什么
        <span :class="showCatalog ? 'i-lucide-chevron-up' : 'i-lucide-chevron-down'" class="text-[15px]" aria-hidden="true" />
      </button>
      <dl v-if="showCatalog" class="catalog">
        <div v-for="item in catalog" :key="item.code" class="catalog-item">
          <dt>{{ item.name }}</dt>
          <dd>{{ item.description }}</dd>
        </div>
      </dl>
    </div>
  </SectionCard>
  <SchoolAdminGrants class="mt-4" />

  <!-- 添加管理员：先找到人 -->
  <ResponsiveDialog v-model="adding" title="添加管理员" :width="520">
    <div class="flex flex-col gap-3" data-test="admin-add-dialog">
      <p class="m-0 text-[13px] text-muted leading-[1.6]">按登录名、昵称或邮箱找到这位同学，下一步选择给他哪些权限。</p>
      <el-input v-model="query" clearable placeholder="登录名、昵称或邮箱" aria-label="找用户" data-test="admin-add-search" @input="onQuery" @keyup.enter="search">
        <template #prefix><span class="i-lucide-search" aria-hidden="true" /></template>
      </el-input>
      <div :aria-busy="searching">
        <ul v-if="candidates.length" class="candidates">
          <li v-for="c in candidates" :key="c.id">
            <button type="button" class="candidate" :data-test="`admin-pick-${c.username}`" @click="pick(c)">
              <UserAvatar :src="c.avatarUrl" :name="c.displayName" class="avatar" :class="avatarTone(c.id)" />
              <span class="min-w-0 flex flex-1 flex-col">
                <span class="truncate text-[13.5px] text-ink font-semibold">{{ c.displayName }}</span>
                <span class="truncate text-xs text-muted">{{ accountLabel(c) }}<template v-if="c.schoolName"> · {{ c.schoolName }}</template></span>
              </span>
              <span class="shrink-0 text-[13px] text-brand-600">选择</span>
            </button>
          </li>
        </ul>
        <p v-else-if="searched && !searching" class="m-0 py-4 text-center text-[13px] text-muted">没有找到这位同学（已禁用的账号和已经是管理员的不在这里）</p>
      </div>
    </div>
  </ResponsiveDialog>

  <!-- 添加管理员：选权限 -->
  <AdminPermissionDialog
    v-model="promoteOpen"
    :user="promoting ? { id: promoting.id, displayName: promoting.displayName, username: accountLabel(promoting) } : null"
    @saved="load"
  />
  <!-- 调整权限 -->
  <AdminPermissionDialog
    v-model="editOpen"
    :user="editing ? { id: editing.id, displayName: editing.displayName, username: accountLabel(editing), hasPassword: editing.hasPassword } : null"
    :permissions="editing ? (editing.full ? null : editing.permissions ?? []) : null"
    @saved="load"
  />
</template>

<style scoped>
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

.perm-chip {
  display: inline-flex;
  align-items: center;
  height: 22px;
  padding: 0 8px;
  border: 1px solid var(--border);
  border-radius: var(--radius-xs);
  background: var(--bg-subtle);
  color: var(--text);
  font-size: 12px;
  white-space: nowrap;
}

.catalog-toggle {
  display: inline-flex;
  align-items: center;
  gap: 6px;
  padding: 0;
  border: 0;
  background: none;
  color: var(--text-muted);
  font: inherit;
  font-size: 13px;
  cursor: pointer;
}

.catalog-toggle:hover {
  color: var(--brand-600);
}

.catalog {
  display: grid;
  grid-template-columns: repeat(2, minmax(0, 1fr));
  gap: 8px;
  margin: 10px 0 0;
}

.catalog-item {
  padding: 10px 12px;
  border-radius: 12px;
  background: var(--bg-subtle);
}

.catalog-item dt {
  color: var(--text-strong);
  font-size: 13.5px;
  font-weight: 600;
}

.catalog-item dd {
  margin: 2px 0 0;
  color: var(--text-muted);
  font-size: 12.5px;
  line-height: 1.6;
}

.candidates {
  display: flex;
  flex-direction: column;
  margin: 0;
  padding: 0;
  border: 1px solid var(--border);
  border-radius: 12px;
  list-style: none;
}

.candidates li + li {
  border-top: 1px solid var(--border-soft);
}

.candidate {
  display: flex;
  align-items: center;
  gap: 10px;
  width: 100%;
  padding: 10px 12px;
  border: 0;
  background: none;
  color: inherit;
  font: inherit;
  text-align: left;
  cursor: pointer;
}

.candidate:hover {
  background: var(--bg-subtle);
}

.candidate:focus-visible {
  outline: 2px solid var(--brand-500);
  outline-offset: -2px;
}

@media (max-width: 767px) {
  .catalog {
    grid-template-columns: minmax(0, 1fr);
  }
}
</style>
