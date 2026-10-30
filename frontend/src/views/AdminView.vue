<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ElMessage, ElMessageBox } from 'element-plus'
import { del, get, notifyError, post } from '@/api/client'
import { ALL_PERMISSIONS, loadAdminMe, type AdminMe, type AdminPermission } from '@/api/admin'
import type { NavResource, PlatformAccount, SyncTask } from '@/api/types'
import AdminsPanel from '@/components/admin/AdminsPanel.vue'
import AnnouncementsPanel from '@/components/admin/AnnouncementsPanel.vue'
import AuditPanel from '@/components/admin/AuditPanel.vue'
import DashboardPanel from '@/components/admin/DashboardPanel.vue'
import EnrollmentYearsPanel from '@/components/admin/EnrollmentYearsPanel.vue'
import NavPanel from '@/components/admin/NavPanel.vue'
import RemindersPanel from '@/components/admin/RemindersPanel.vue'
import SchoolInvitePanel from '@/components/admin/SchoolInvitePanel.vue'
import StudentsPanel from '@/components/admin/StudentsPanel.vue'
import SyncPanel from '@/components/admin/SyncPanel.vue'
import UsersPanel from '@/components/admin/UsersPanel.vue'
import OwnershipAppealManager from '@/components/OwnershipAppealManager.vue'
import ProblemGroupManager from '@/components/ProblemGroupManager.vue'
import TagMappingManager from '@/components/TagMappingManager.vue'
import EmptyState from '@/components/ui/EmptyState.vue'
import PageHeader from '@/components/ui/PageHeader.vue'
import PageTabs from '@/components/ui/PageTabs.vue'
import Skeleton from '@/components/ui/Skeleton.vue'
import { useBreakpoint } from '@/composables/useBreakpoint'
import { useTopBarOverride } from '@/composables/useTopBarOverride'

/**
 * 管理后台，按五组分区（概览、用户、内容、数据、系统）：
 * - 笔记本、桌面：左边一列分组导航，右边这一分区（和设置页同一种导航）；
 * - 平板：上面一排横向标签（表格要宽度，放不下左边一列）；
 * - 手机：没带 tab 时是分组列表，点一行进子页，顶栏写分区名、返回到列表。
 * 只显示当前管理员有权限的分区（GET /api/admin/me），默认打开第一个，通常是数据概览；用 ?tab= 记住，旧链接的键照样能用。
 * 同步状态上面四个数可以点：点「失败」筛出失败的任务和账号。学生与认证、归属申诉带待办数，同步失败标红。
 */
interface Overview {
  pending: number
  running: number
  failed: number
  tasks: SyncTask[]
  accounts: (PlatformAccount & { username?: string })[]
  /** 冷却中的平台：刚被源站限流，冷却结束前同平台任务暂不执行 */
  throttles?: { platform: string; blockedUntil: string; reason: string | null }[]
}

const SECTIONS = [
  { key: 'dashboard', label: '数据概览', group: '概览', icon: 'i-lucide-layout-dashboard', permission: 'DASHBOARD' },
  { key: 'users', label: '用户管理', group: '用户', icon: 'i-lucide-users', permission: 'USERS' },
  { key: 'members', label: '学生与认证', group: '用户', icon: 'i-lucide-user-check', permission: 'SCHOOLS' },
  { key: 'schools', label: '学校与邀请码', group: '用户', icon: 'i-lucide-school', permission: 'SCHOOLS' },
  { key: 'years', label: '年级配置', group: '用户', icon: 'i-lucide-calendar-range', permission: 'SCHOOLS' },
  { key: 'announcements', label: '公告与资源', group: '内容', icon: 'i-lucide-megaphone', permission: 'CONTENT' },
  { key: 'reminders', label: '学习提醒', group: '内容', icon: 'i-lucide-bell-ring', permission: 'CONTENT' },
  { key: 'nav', label: '导航维护', group: '内容', icon: 'i-lucide-compass', permission: 'CONTENT' },
  { key: 'sync', label: '同步状态', group: '数据', icon: 'i-lucide-refresh-cw', permission: 'SYNC' },
  { key: 'appeals', label: '归属申诉', group: '数据', icon: 'i-lucide-shield-alert', permission: 'SYNC' },
  { key: 'problem-groups', label: '题目映射', group: '数据', icon: 'i-lucide-git-merge', permission: 'PROBLEMS' },
  { key: 'tags', label: '标签分类', group: '数据', icon: 'i-lucide-tags', permission: 'PROBLEMS' },
  { key: 'admins', label: '管理员与权限', group: '系统', icon: 'i-lucide-shield-check', permission: 'ADMINS' },
  { key: 'audit', label: '操作日志', group: '系统', icon: 'i-lucide-scroll-text', permission: 'AUDIT' }
] as const satisfies readonly { key: string; label: string; group: string; icon: string; permission: AdminPermission }[]
type SectionKey = (typeof SECTIONS)[number]['key']
const GROUPS = ['概览', '用户', '内容', '数据', '系统'] as const

const route = useRoute()
const router = useRouter()
const { isMobile, lg } = useBreakpoint()

const me = ref<AdminMe | null>(null)
const pendingMembers = ref(0)
const navItems = ref<NavResource[]>([])
const overview = ref<Overview | null>(null)
const pendingAppeals = ref(0)
const loading = ref(true)
const taskStatus = ref('')
const accountStatus = ref('')
/** 页头「刷新」时加一，数据概览据此重读 */
const refreshKey = ref(0)

const permissions = computed<AdminPermission[]>(() => (me.value ? (me.value.full ? [...ALL_PERMISSIONS] : me.value.permissions) : []))
const has = (permission: AdminPermission) => permissions.value.includes(permission)
const visible = computed(() => SECTIONS.filter((s) => has(s.permission)))
const visibleGroups = computed(() =>
  GROUPS.map((group) => ({ label: group, items: visible.value.filter((s) => s.group === group) })).filter((g) => g.items.length)
)

/** 当前分区：?tab= 指定且有权限就用它；手机上没指定时是分组列表（null），其他尺寸默认第一个有权限的 */
const tab = computed<SectionKey | null>(() => {
  const asked = visible.value.find((s) => s.key === route.query.tab)
  if (asked) return asked.key
  return isMobile.value ? null : (visible.value[0]?.key ?? null)
})
const current = computed(() => SECTIONS.find((s) => s.key === tab.value) ?? null)
useTopBarOverride(() => (isMobile.value && current.value ? { title: current.value.label, parent: '/admin', parentTitle: '管理后台' } : null))

/** 电脑、平板上换分区替换当前历史；手机从列表进子页、从概览跳过去都是新的一页，返回键能回来 */
const tabLink = (key: SectionKey) => ({ path: '/admin', query: { ...route.query, tab: key } })
function go(key: string) {
  const target = { path: '/admin', query: { tab: key } }
  void (isMobile.value ? router.push(target) : router.replace(target))
}

function countOf(key: SectionKey) {
  if (key === 'members') return pendingMembers.value
  if (key === 'appeals') return pendingAppeals.value
  if (key === 'sync') return overview.value?.failed ?? 0
  return 0
}
const tabLinks = computed(() => visible.value.map((s) => ({ key: s.key, label: s.label, to: tabLink(s.key), count: countOf(s.key) })))

/** 分区的待办数和各面板要的数据：只读有权限的，没权限的接口不去碰（会 403） */
async function loadCounts() {
  const jobs: Promise<unknown>[] = []
  if (has('SCHOOLS')) {
    jobs.push(get<{ summary: { pending: number } }>('/admin/students', { size: 1 }).then((m) => (pendingMembers.value = m.summary.pending)))
  }
  if (has('CONTENT')) jobs.push(get<NavResource[]>('/admin/nav').then((n) => (navItems.value = n)))
  if (has('SYNC')) jobs.push(get<Overview>('/admin/sync/overview', { limit: 200 }).then((o) => (overview.value = o)))
  await Promise.all(jobs)
  if (has('SYNC')) {
    try {
      pendingAppeals.value = (await get<unknown[]>('/admin/ownership-appeals', { status: 'PENDING' })).length
    } catch {
      pendingAppeals.value = 0
    }
  }
}

async function load() {
  loading.value = true
  try {
    me.value = await loadAdminMe()
    await loadCounts()
  } catch (e) {
    notifyError(e)
  } finally {
    loading.value = false
  }
}
onMounted(load)
function refreshAll() {
  refreshKey.value += 1
  void load()
}

async function retry(accountId: number) {
  try {
    await post(`/admin/sync/accounts/${accountId}/retry`)
    ElMessage.success('已重新入队')
    await load()
  } catch (e) {
    notifyError(e)
  }
}
async function rebuild(accountId: number) {
  try {
    await post(`/admin/sync/accounts/${accountId}/rebuild`)
    ElMessage.success('已重算该账号的日统计')
  } catch (e) {
    notifyError(e)
  }
}
async function refreshContests() {
  try {
    await post('/admin/sync/contests/refresh')
    ElMessage.success('已提交比赛刷新任务')
    await load()
  } catch (e) {
    notifyError(e)
  }
}
async function unbind(accountId: number) {
  const account = overview.value?.accounts.find(a => a.id === accountId)
  if (!account || !has('SYNC')) return
  try {
    await ElMessageBox.confirm(`代解绑 ${account.username ?? '该用户'} 的 ${account.handle}？该账号的记录与凭据会一并删除。`, '确认代解绑', { type: 'warning', confirmButtonText: '解绑', cancelButtonText: '取消' })
  } catch { return }
  try {
    await del(`/admin/sync/accounts/${accountId}`)
    ElMessage.success('已代解绑平台账号')
    await load()
  } catch (e) { notifyError(e) }
}

/** 同步状态上面四个数：点了筛好 */
const stats = computed(() => [
  { key: 'PENDING', label: '待执行', value: overview.value?.pending, hint: '已入队、等同步器取出', tone: '' },
  { key: 'RUNNING', label: '执行中', value: overview.value?.running, hint: '正在抓取', tone: '' },
  { key: 'FAILED', label: '失败', value: overview.value?.failed, hint: '要排查或重试', tone: (overview.value?.failed ?? 0) > 0 ? 'danger' : '' },
  { key: '', label: '已绑定账号', value: overview.value?.accounts.length, hint: '纳入同步范围', tone: '' }
])
function openStat(key: string) {
  taskStatus.value = key
  accountStatus.value = key === 'FAILED' ? 'FAILED' : ''
  if (tab.value !== 'sync') go('sync')
}
/** 数据概览里的待办小数：跳到对应分区；同步失败直接筛好 */
function openFromDashboard(target: { tab: string; failedOnly?: boolean }) {
  if (target.failedOnly) openStat('FAILED')
  else go(target.tab)
}
</script>

<template>
  <div class="page" data-test="page">
    <PageHeader title="管理后台">
      <template v-if="!isMobile" #description>数据概览、用户与权限、学校与邀请码、公告和同步状态都在这里；只显示你有权限的分区。</template>
      <template #actions>
        <el-button :loading="loading" @click="refreshAll"><span v-if="!loading" class="i-lucide-refresh-cw mr-1.5" aria-hidden="true" />刷新</el-button>
        <el-button v-if="tab === 'sync'" type="primary" @click="refreshContests">刷新比赛列表</el-button>
        <el-button v-if="has('PROBLEMS')" tag="a" href="/admin/problem-lists">题单管理</el-button>
      </template>
      <template #mobile-actions>
        <button type="button" class="topbar-icon" aria-label="刷新" :disabled="loading" @click="refreshAll"><span class="i-lucide-refresh-cw text-xl" aria-hidden="true" /></button>
      </template>
    </PageHeader>
    <RouterLink v-if="has('SCHOOLS') || has('CONTENT')" to="/school-admin" class="mb-3 inline-flex items-center gap-2 text-brand-600">学校管理 <span class="i-lucide-chevron-right" aria-hidden="true" /></RouterLink>
    <RouterLink v-if="isMobile && has('PROBLEMS')" to="/admin/problem-lists" class="mb-3 inline-flex items-center gap-2 text-brand-600">题单管理 <span class="i-lucide-chevron-right" aria-hidden="true" /></RouterLink>

    <!-- 还没读到权限 -->
    <div v-if="!me" class="grid grid-cols-1 gap-4 lg:grid-cols-[188px_minmax(0,1fr)]" aria-busy="true">
      <Skeleton v-if="lg" class="h-[420px]" />
      <div class="flex flex-col gap-4">
        <Skeleton class="h-[104px]" />
        <Skeleton class="h-[300px]" />
      </div>
    </div>

    <EmptyState
      v-else-if="!visible.length"
      icon="i-lucide-shield-off"
      title="还没有分配管理权限"
      description="请联系拥有「管理员与权限」权限的管理员，给你开通要管理的分区。"
    />

    <!-- 手机：分组列表 -->
    <div v-else-if="isMobile && !tab" class="flex flex-col gap-[18px]" data-test="admin-list">
      <section v-for="group in visibleGroups" :key="group.label" class="flex flex-col gap-1.5">
        <h2 class="m-0 px-1 text-[12.5px] text-muted font-normal">{{ group.label }}</h2>
        <ul class="m-0 list-none overflow-hidden border border-line rounded-card border-solid bg-surface p-0">
          <li v-for="item in group.items" :key="item.key" class="list-row-wrap">
            <RouterLink :to="{ path: '/admin', query: { tab: item.key } }" class="list-row" :data-test="`admin-row-${item.key}`">
              <span :class="item.icon" class="shrink-0 text-[18px] text-muted" aria-hidden="true" />
              <span class="flex-1 text-[15px] text-ink">{{ item.label }}</span>
              <span v-if="countOf(item.key)" class="count num" :class="{ danger: item.key === 'sync' }">{{ countOf(item.key) }}</span>
              <span class="i-lucide-chevron-right shrink-0 text-[17px] text-faint" aria-hidden="true" />
            </RouterLink>
          </li>
        </ul>
      </section>
    </div>

    <!-- 电脑：左边分组导航；平板：横排标签；手机：子页 -->
    <div v-else class="grid grid-cols-1 items-start gap-4 lg:(grid-cols-[188px_minmax(0,1fr)] gap-6)">
      <nav v-if="lg" aria-label="管理分区" class="admin-nav" data-test="admin-nav">
        <div v-for="group in visibleGroups" :key="group.label" class="flex flex-col gap-0.5">
          <span class="px-3 pb-1 text-xs text-muted">{{ group.label }}</span>
          <RouterLink
            v-for="item in group.items"
            :key="item.key"
            :to="tabLink(item.key)"
            replace
            class="nav-item"
            :class="{ 'is-active': tab === item.key }"
            :aria-current="tab === item.key ? 'page' : undefined"
            :data-tab="item.key"
          >
            <span :class="item.icon" class="shrink-0 text-[17px]" aria-hidden="true" />
            <span class="min-w-0 flex-1 truncate">{{ item.label }}</span>
            <span v-if="countOf(item.key)" class="count num" :class="{ danger: item.key === 'sync' }" :aria-label="`${countOf(item.key)} 条待处理`">{{ countOf(item.key) }}</span>
          </RouterLink>
        </div>
      </nav>
      <PageTabs v-else-if="!isMobile && tab" :tabs="tabLinks" :active="tab" label="管理分区" />

      <div class="min-w-0 flex flex-col gap-4" :data-test="`admin-section-${tab}`">
        <h2 v-if="lg && current" class="m-0 text-lg text-ink font-semibold">{{ current.label }}</h2>

        <DashboardPanel v-if="tab === 'dashboard'" :permissions="permissions" :refresh-key="refreshKey" @open="openFromDashboard" />
        <UsersPanel v-else-if="tab === 'users'" :permissions="permissions" />
        <template v-else-if="tab === 'sync'">
          <div class="grid grid-cols-2 gap-3 lg:grid-cols-4" data-test="admin-stats">
            <button
              v-for="s in stats"
              :key="s.label"
              type="button"
              class="stat"
              :class="[s.tone, { 'is-active': taskStatus === s.key && !!s.key }]"
              :aria-label="`${s.label} ${s.value ?? '—'}，点开看${s.key ? '这些任务' : '全部账号'}`"
              @click="openStat(s.key)"
            >
              <span class="text-[12.5px] text-muted">{{ s.label }}</span>
              <Skeleton v-if="s.value === undefined" class="h-8 w-14" />
              <span v-else class="num text-[28px] font-semibold leading-tight">{{ s.value }}</span>
              <span class="text-xs text-muted">{{ s.hint }}</span>
            </button>
          </div>
          <SyncPanel
            v-model:task-status="taskStatus"
            v-model:account-status="accountStatus"
            :accounts="overview?.accounts ?? []"
            :tasks="overview?.tasks ?? []"
            :throttles="overview?.throttles ?? []"
            :totals="{ PENDING: overview?.pending ?? 0, RUNNING: overview?.running ?? 0, FAILED: overview?.failed ?? 0 }"
            :loading="loading && !overview"
            @retry="retry"
            @rebuild="rebuild"
            @unbind="unbind"
          />
          <el-button v-if="isMobile" class="self-start" @click="refreshContests">刷新比赛列表</el-button>
        </template>
        <StudentsPanel v-else-if="tab === 'members'" @updated="load" />
        <SchoolInvitePanel v-else-if="tab === 'schools'" />
        <EnrollmentYearsPanel v-else-if="tab === 'years'" />
        <AnnouncementsPanel v-else-if="tab === 'announcements'" />
        <RemindersPanel v-else-if="tab === 'reminders'" />
        <AdminsPanel v-else-if="tab === 'admins'" :permissions="permissions" />
        <AuditPanel v-else-if="tab === 'audit'" :permissions="permissions" />
        <div v-else class="legacy-panel">
          <OwnershipAppealManager v-if="tab === 'appeals'" />
          <ProblemGroupManager v-else-if="tab === 'problem-groups'" />
          <TagMappingManager v-else-if="tab === 'tags'" />
          <NavPanel v-else-if="tab === 'nav'" :items="navItems" :loading="loading && !navItems.length" @changed="load" />
        </div>
      </div>
    </div>
  </div>
</template>

<style scoped>
.admin-nav {
  position: sticky;
  top: 24px;
  display: flex;
  flex-direction: column;
  gap: 16px;
}

.nav-item {
  display: flex;
  align-items: center;
  gap: 10px;
  height: 38px;
  padding: 0 10px 0 12px;
  border-radius: 10px;
  color: var(--text);
  font-size: 14px;
  text-decoration: none;
}

.nav-item:hover {
  background: var(--bg-subtle);
}

.nav-item.is-active {
  background: var(--brand-soft);
  color: var(--brand-600);
  font-weight: 600;
}

.count {
  display: inline-flex;
  flex-shrink: 0;
  align-items: center;
  justify-content: center;
  min-width: 20px;
  height: 20px;
  padding: 0 6px;
  box-sizing: border-box;
  border-radius: 999px;
  background: var(--warning-tint);
  color: var(--warning-ink);
  font-size: 12px;
  font-weight: 600;
}

.count.danger {
  background: var(--danger-tint);
  color: var(--danger-ink);
}

.list-row-wrap + .list-row-wrap {
  border-top: 1px solid var(--border-soft);
}

.list-row {
  display: flex;
  align-items: center;
  gap: 12px;
  min-height: 52px;
  padding: 0 14px 0 16px;
  color: inherit;
  text-decoration: none;
}

.nav-item:focus-visible,
.list-row:focus-visible {
  outline: 2px solid var(--brand-500);
  outline-offset: -2px;
}

:deep(.page-tabs) {
  overflow-x: auto;
}

.stat {
  display: flex;
  flex-direction: column;
  align-items: flex-start;
  gap: 4px;
  padding: 14px 16px;
  border: 1px solid var(--border);
  border-radius: var(--radius);
  background: var(--bg-elevated);
  color: var(--text-strong);
  font: inherit;
  text-align: left;
  cursor: pointer;
}

.stat:hover {
  border-color: var(--border-strong);
}

.stat.danger .num {
  color: var(--danger-ink);
}

.stat.is-active {
  border-color: var(--brand-500);
  box-shadow: inset 0 0 0 1px var(--brand-500);
}

.stat:focus-visible {
  outline: 2px solid var(--brand-500);
  outline-offset: 2px;
}

/* 标签分类、题目映射、归属申诉三个管理组件沿用全局 .card，这里统一成和其他卡片一样的内边距、标题和表头 */
.legacy-panel :deep(.card) {
  margin-bottom: 16px;
  padding: 20px 22px;
}

.legacy-panel :deep(.card-title) {
  font-size: 16px;
  font-weight: 600;
}

.legacy-panel :deep(.card .muted) {
  font-size: 13px;
  line-height: 1.7;
}

.legacy-panel :deep(.el-table th.el-table__cell) {
  background: var(--bg-subtle);
  color: var(--text-muted);
  font-weight: 600;
}

@media (max-width: 767px) {
  .legacy-panel :deep(.card) {
    padding: 16px;
  }
}
</style>
