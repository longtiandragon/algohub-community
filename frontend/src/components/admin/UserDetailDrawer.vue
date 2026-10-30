<script setup lang="ts">
import { computed, ref, watch } from 'vue'
import { ElDrawer, ElMessage, ElMessageBox } from 'element-plus'
import { notifyError } from '@/api/client'
import {
  MEMBERSHIP_STATUS,
  OWNERSHIP_STATUS,
  USER_STATUS,
  fetchUser,
  formatTime,
  hasPermission,
  logoutUser,
  permissionName,
  setUserStatus,
  type AdminUserDetail,
  accountLabel
} from '@/api/adminUsers'
import AdminPermissionDialog from '@/components/admin/AdminPermissionDialog.vue'
import PlatformLogo from '@/components/PlatformLogo.vue'
import BottomSheet from '@/components/ui/BottomSheet.vue'
import ResponsiveDialog from '@/components/ui/ResponsiveDialog.vue'
import Skeleton from '@/components/ui/Skeleton.vue'
import StatusBadge from '@/components/ui/StatusBadge.vue'
import UserAvatar from '@/components/ui/UserAvatar.vue'
import { useBreakpoint } from '@/composables/useBreakpoint'
import { useAuthStore } from '@/stores/auth'
import { avatarTone } from '@/utils/avatar'
import { platformName } from '@/utils/platformBrand'
import { relativeTime, syncStatusText } from '@/utils/statistics'
import { AUTH_EVENT_LABELS, deviceName } from '@/utils/userAgent'

/**
 * 用户详情：电脑是右侧抽屉，手机是占满整屏的底部弹层。资料、访问情况、入校记录、平台账号、最近登录，
 * 上面一排操作：禁用 / 启用（写原因、二次确认）、强制下线、设为平台管理员 / 调整权限（有「管理员与权限」时）。
 * 自己的账号不能禁用、不能改自己的权限；「最后一个全权管理员」由后端拦下并说明原因。
 */
const props = defineProps<{
  userId: number | null
  /** AdminView 给的当前管理员权限；不给就看 /admin/me */
  permissions?: readonly string[] | null
}>()
const emit = defineEmits<{ close: []; updated: [] }>()

const auth = useAuthStore()
const { isMobile } = useBreakpoint()
const detail = ref<AdminUserDetail | null>(null)
const loading = ref(false)
const busy = ref<'' | 'status' | 'logout'>('')
let request = 0

async function load() {
  const id = props.userId
  if (id == null) return
  const current = ++request
  loading.value = true
  try {
    const data = await fetchUser(id)
    if (current === request) detail.value = data
  } catch (e) {
    if (current === request) notifyError(e)
  } finally {
    if (current === request) loading.value = false
  }
}
/** 最近登录默认显示 6 条；换人时收起 */
const showAllEvents = ref(false)
watch(
  () => props.userId,
  (id) => {
    detail.value = null
    showAllEvents.value = false
    if (id != null) void load()
  },
  { immediate: true }
)

const open = computed(() => props.userId != null)
const user = computed(() => detail.value?.user ?? null)
const isSelf = computed(() => !!user.value && user.value.id === auth.me?.id)
const canManageAdmins = computed(() => hasPermission('ADMINS', props.permissions))
const disabled = computed(() => user.value?.status === 'DISABLED')

/* ------------------------------ 禁用 / 启用 ------------------------------ */
const disabling = ref(false)
const reason = ref('')
function askDisable() {
  reason.value = ''
  disabling.value = true
}
async function confirmDisable() {
  if (!user.value || busy.value) return
  busy.value = 'status'
  try {
    await setUserStatus(user.value.id, 'DISABLED', reason.value.trim() || null)
    disabling.value = false
    ElMessage.success(`已禁用 ${user.value.displayName}，他的所有设备都已退出`)
    await load()
    emit('updated')
  } catch (e) {
    notifyError(e)
  } finally {
    busy.value = ''
  }
}
async function enable() {
  if (!user.value || busy.value) return
  try {
    await ElMessageBox.confirm(`启用后 ${user.value.displayName} 可以重新登录，数据都还在。`, '启用账号', {
      confirmButtonText: '启用',
      cancelButtonText: '取消'
    })
  } catch {
    return
  }
  busy.value = 'status'
  try {
    await setUserStatus(user.value.id, 'ACTIVE', null)
    ElMessage.success('账号已启用')
    await load()
    emit('updated')
  } catch (e) {
    notifyError(e)
  } finally {
    busy.value = ''
  }
}

/* ------------------------------ 强制下线 ------------------------------ */
async function forceLogout() {
  if (!user.value || busy.value) return
  try {
    await ElMessageBox.confirm(`${user.value.displayName} 的所有设备都会退出登录，需要重新登录才能继续使用。`, '强制下线', {
      confirmButtonText: '全部退出',
      cancelButtonText: '取消',
      type: 'warning'
    })
  } catch {
    return
  }
  busy.value = 'logout'
  try {
    const result = await logoutUser(user.value.id)
    ElMessage.success(result.revokedSessions ? `已让 ${result.revokedSessions} 台设备退出登录` : '已退出，他目前没有登录中的设备')
    await load()
  } catch (e) {
    notifyError(e)
  } finally {
    busy.value = ''
  }
}

/* ------------------------------ 管理员 ------------------------------ */
const editingAdmin = ref(false)
async function adminSaved() {
  await load()
  emit('updated')
}

/* ------------------------------ 显示 ------------------------------ */
const ago = (value: string | null | undefined, never = '从未') => (value ? relativeTime(value) : never)
const cohort = (m: { enrollmentYear: number | null; className: string | null; studentNo: string | null }) =>
  [m.enrollmentYear ? `${m.enrollmentYear} 级` : '', m.className, m.studentNo ? `学号 ${m.studentNo}` : ''].filter(Boolean).join(' · ') || '没有填年级班级'
const events = computed(() => (showAllEvents.value ? detail.value?.events ?? [] : (detail.value?.events ?? []).slice(0, 6)))
const eventLabel = (event: string) => AUTH_EVENT_LABELS[event] ?? event
const dangerEvent = (event: string) => event === 'LOGIN_FAILED' || event === 'LOGIN_LOCKED'
const roleText = computed(() => {
  const u = user.value
  if (!u || u.role !== 'ADMIN') return u?.schoolAdmin ? '学校管理员' : '学生'
  return u.adminPermissions ? '平台管理员（部分权限）' : '平台管理员（全部权限）'
})

function close() {
  emit('close')
}
</script>

<template>
  <!-- 手机用底部弹层（它里面已经用了抽屉，样式随之加载），电脑用右侧抽屉；内容只写一份 -->
  <component
    :is="isMobile ? BottomSheet : ElDrawer"
    :model-value="open"
    v-bind="isMobile ? { full: true, title: '用户详情' } : { title: '用户详情', size: 560, direction: 'rtl', appendToBody: true, class: 'user-drawer' }"
    @update:model-value="(value: boolean) => { if (!value) close() }"
  >
    <div class="flex flex-col gap-5 pb-2" data-test="admin-user-detail">
      <!-- 加载中 -->
      <div v-if="loading && !user" class="flex flex-col gap-3" aria-busy="true">
        <div class="flex items-center gap-3"><Skeleton class="h-13 w-13 rounded-full" /><div class="flex flex-1 flex-col gap-2"><Skeleton class="h-4 w-1/3" /><Skeleton class="h-3 w-1/2" /></div></div>
        <Skeleton class="h-20 w-full" />
        <Skeleton class="h-32 w-full" />
      </div>

      <template v-else-if="user">
        <!-- 头部 -->
        <div class="flex items-start gap-3.5">
          <UserAvatar :src="user.avatarUrl" :name="user.displayName" class="avatar" :class="avatarTone(user.id)" />
          <div class="min-w-0 flex flex-1 flex-col gap-1">
            <div class="flex flex-wrap items-center gap-2">
              <span class="truncate text-lg text-ink font-semibold leading-6">{{ user.displayName }}</span>
              <StatusBadge variant="label" :tone="user.role === 'ADMIN' ? 'brand' : 'neutral'">{{ roleText }}</StatusBadge>
              <StatusBadge variant="label" :tone="USER_STATUS[user.status]?.tone ?? 'neutral'">{{ USER_STATUS[user.status]?.label ?? user.status }}</StatusBadge>
            </div>
            <span class="text-[13px] text-muted">{{ accountLabel(user) }}<template v-if="isSelf"> · 这是你自己</template></span>
            <span v-if="user.role === 'ADMIN' && user.adminPermissions" class="text-xs text-muted leading-5">
              权限：{{ user.adminPermissions.map(permissionName).join('、') }}
            </span>
          </div>
        </div>

        <!-- 操作 -->
        <div class="flex flex-wrap items-center gap-2" data-test="admin-user-actions">
          <el-button
            v-if="!disabled"
            type="danger"
            plain
            :disabled="isSelf"
            :loading="busy === 'status'"
            data-test="admin-user-disable"
            @click="askDisable"
          >
            <span class="i-lucide-ban mr-1.5" aria-hidden="true" />禁用账号
          </el-button>
          <el-button v-else type="primary" :loading="busy === 'status'" data-test="admin-user-enable" @click="enable">
            <span class="i-lucide-circle-check mr-1.5" aria-hidden="true" />启用账号
          </el-button>
          <el-button :disabled="isSelf || disabled" :loading="busy === 'logout'" data-test="admin-user-logout" @click="forceLogout">
            <span class="i-lucide-log-out mr-1.5" aria-hidden="true" />强制下线
          </el-button>
          <el-button v-if="canManageAdmins" :disabled="isSelf || disabled" data-test="admin-user-admin" @click="editingAdmin = true">
            <span class="i-lucide-shield mr-1.5" aria-hidden="true" />{{ user.role === 'ADMIN' ? '调整权限' : '设为平台管理员' }}
          </el-button>
        </div>
        <p v-if="isSelf" class="-mt-2 mb-0 text-xs text-muted">不能禁用自己，也不能改自己的权限；需要时请另一位管理员操作。</p>
        <p v-else-if="disabled" class="-mt-2 mb-0 text-xs text-danger-ink">这个账号已被禁用：不能登录，排行和动态里也不再出现；启用后恢复。</p>

        <!-- 访问情况 -->
        <div class="facts">
          <div class="fact"><span class="fact-label">近 30 天来过</span><span class="fact-value num">{{ detail?.visits.days30 ?? 0 }}<small> 天</small></span></div>
          <div class="fact"><span class="fact-label">打开页面</span><span class="fact-value num">{{ detail?.visits.views30 ?? 0 }}<small> 次</small></span></div>
          <div class="fact"><span class="fact-label">最近活跃</span><span class="fact-value" :title="formatTime(user.lastSeenAt)">{{ ago(user.lastSeenAt) }}</span></div>
          <div class="fact"><span class="fact-label">已验证账号</span><span class="fact-value num">{{ user.verifiedAccountCount }}<small> / {{ user.accountCount }}</small></span></div>
        </div>

        <!-- 资料 -->
        <section class="block">
          <h3 class="block-title">资料</h3>
          <dl class="info">
            <dt>登录邮箱</dt>
            <dd>
              <template v-if="user.email">{{ user.email }}<StatusBadge class="ml-2" :tone="user.emailVerified ? 'success' : 'warning'">{{ user.emailVerified ? '已验证' : '未验证' }}</StatusBadge></template>
              <span v-else class="text-muted">没有</span>
            </dd>
            <dt>GitHub</dt>
            <dd><span v-if="detail?.user.githubLogin" class="mono">{{ detail.user.githubLogin }}</span><span v-else class="text-muted">未绑定</span></dd>
            <template v-if="user.role === 'ADMIN'">
              <dt>管理员密码</dt>
              <dd><span :class="detail?.user.hasPassword ? '' : 'text-warning-ink'">{{ detail?.user.hasPassword ? '已设置' : '还没设置，暂时不能从管理员入口登录' }}</span></dd>
            </template>
            <dt>注册时间</dt>
            <dd>{{ formatTime(user.createdAt) }}</dd>
            <dt>最近登录</dt>
            <dd>{{ user.lastLoginAt ? formatTime(user.lastLoginAt) : '从未' }}</dd>
            <template v-if="detail?.user.bio">
              <dt>简介</dt>
              <dd class="whitespace-pre-wrap break-words">{{ detail.user.bio }}</dd>
            </template>
          </dl>
        </section>

        <!-- 入校记录 -->
        <section class="block">
          <h3 class="block-title">入校记录</h3>
          <ul v-if="detail?.memberships.length" class="rows">
            <li v-for="(m, i) in detail.memberships" :key="i" class="row">
              <span class="i-lucide-school shrink-0 text-base text-faint" aria-hidden="true" />
              <span class="min-w-0 flex flex-1 flex-col">
                <span class="truncate text-[13.5px] text-ink">{{ m.schoolName }}</span>
                <span class="text-xs text-muted">{{ cohort(m) }} · {{ formatTime(m.createdAt) }}</span>
              </span>
              <StatusBadge variant="label" :tone="MEMBERSHIP_STATUS[m.status]?.tone ?? 'neutral'">{{ MEMBERSHIP_STATUS[m.status]?.label ?? m.status }}</StatusBadge>
            </li>
          </ul>
          <p v-else class="empty">还没有申请加入学校</p>
        </section>

        <!-- 平台账号 -->
        <section class="block">
          <h3 class="block-title">平台账号</h3>
          <ul v-if="detail?.accounts.length" class="rows">
            <li v-for="a in detail.accounts" :key="a.id" class="row">
              <PlatformLogo :platform="a.platform" :size="22" :title="platformName(a.platform)" />
              <span class="min-w-0 flex flex-1 flex-col">
                <span class="truncate text-[13.5px] text-ink">{{ platformName(a.platform) }} <span class="mono text-xs text-muted">{{ a.handle }}</span></span>
                <span class="text-xs text-muted">{{ syncStatusText(a.syncStatus) }}<template v-if="a.lastSyncedAt"> · {{ ago(a.lastSyncedAt) }}</template></span>
              </span>
              <StatusBadge variant="label" :tone="OWNERSHIP_STATUS[a.ownershipStatus]?.tone ?? 'neutral'">{{ OWNERSHIP_STATUS[a.ownershipStatus]?.label ?? a.ownershipStatus }}</StatusBadge>
            </li>
          </ul>
          <p v-else class="empty">还没有绑定平台账号</p>
        </section>

        <!-- 最近登录 -->
        <section class="block">
          <h3 class="block-title">最近登录记录</h3>
          <ul v-if="detail?.events.length" class="rows">
            <li v-for="(e, i) in events" :key="i" class="row">
              <span class="min-w-0 flex flex-1 flex-col">
                <span class="text-[13.5px]" :class="dangerEvent(e.event) ? 'text-danger-ink' : 'text-ink'">{{ eventLabel(e.event) }}</span>
                <span class="text-xs text-muted" :title="e.userAgent ?? ''">{{ deviceName(e.userAgent) }}<template v-if="e.ip"> · <span class="mono">{{ e.ip }}</span></template></span>
              </span>
              <span class="shrink-0 text-xs text-muted">{{ formatTime(e.createdAt) }}</span>
            </li>
          </ul>
          <p v-else class="empty">没有登录记录</p>
          <el-button v-if="(detail?.events.length ?? 0) > 6" text size="small" class="mt-1" @click="showAllEvents = !showAllEvents">
            {{ showAllEvents ? '收起' : `显示全部 ${detail?.events.length} 条` }}
          </el-button>
        </section>
      </template>
    </div>
  </component>

  <ResponsiveDialog v-model="disabling" title="禁用账号" :width="460">
    <div v-if="user" class="flex flex-col gap-3">
      <p class="m-0 text-[13.5px] text-body leading-[1.7]">
        禁用后 <span class="font-semibold text-ink">{{ user.displayName }}</span> 的所有设备立即退出，不能再登录；
        他的数据都保留，随时可以启用。
      </p>
      <label class="flex flex-col gap-1.5 text-[13px] text-body">
        原因（选填，会记进操作日志）
        <el-input v-model="reason" type="textarea" :rows="3" maxlength="200" show-word-limit placeholder="比如：冒用他人学号，已联系本人" data-test="admin-disable-reason" />
      </label>
    </div>
    <template #footer>
      <el-button @click="disabling = false">取消</el-button>
      <el-button type="danger" :loading="busy === 'status'" data-test="admin-disable-confirm" @click="confirmDisable">禁用</el-button>
    </template>
  </ResponsiveDialog>

  <AdminPermissionDialog
    v-model="editingAdmin"
    :user="user ? { id: user.id, displayName: user.displayName, username: accountLabel(user), hasPassword: detail?.user.hasPassword } : null"
    :permissions="user?.role === 'ADMIN' ? user.adminPermissions : undefined"
    @saved="adminSaved"
  />
</template>

<style scoped>
.avatar {
  display: flex;
  flex-shrink: 0;
  align-items: center;
  justify-content: center;
  width: 52px;
  height: 52px;
  border-radius: 50%;
  font-size: 21px;
  font-weight: 600;
}

.facts {
  display: grid;
  grid-template-columns: repeat(4, minmax(0, 1fr));
  gap: 8px;
}

.fact {
  display: flex;
  flex-direction: column;
  gap: 4px;
  padding: 10px 12px;
  border-radius: 12px;
  background: var(--bg-subtle);
}

.fact-label {
  color: var(--text-muted);
  font-size: 12px;
}

.fact-value {
  color: var(--text-strong);
  font-size: 17px;
  font-weight: 650;
  line-height: 22px;
  white-space: nowrap;
}

.fact-value small {
  color: var(--text-muted);
  font-size: 12px;
  font-weight: 400;
}

.block {
  display: flex;
  flex-direction: column;
  gap: 8px;
}

.block-title {
  margin: 0;
  color: var(--text-strong);
  font-size: 14px;
  font-weight: 600;
}

.info {
  display: grid;
  grid-template-columns: 84px minmax(0, 1fr);
  gap: 8px 12px;
  margin: 0;
  font-size: 13.5px;
  line-height: 20px;
}

.info dt {
  color: var(--text-muted);
}

.info dd {
  margin: 0;
  min-width: 0;
  color: var(--text);
  overflow-wrap: anywhere;
}

.rows {
  display: flex;
  flex-direction: column;
  margin: 0;
  padding: 0;
  border: 1px solid var(--border);
  border-radius: 12px;
  list-style: none;
}

.row {
  display: flex;
  align-items: center;
  gap: 10px;
  min-height: 52px;
  padding: 8px 12px;
}

.row + .row {
  border-top: 1px solid var(--border-soft);
}

.empty {
  margin: 0;
  padding: 12px;
  border: 1px dashed var(--border-strong);
  border-radius: 12px;
  color: var(--text-muted);
  font-size: 13px;
  text-align: center;
}

@media (max-width: 767px) {
  .facts {
    grid-template-columns: repeat(2, minmax(0, 1fr));
  }
}
</style>

<style>
/* 电脑上的右侧抽屉：标题和内容的间距跟卡片一致 */
.user-drawer.el-drawer .el-drawer__header {
  margin-bottom: 0;
  padding: 20px 24px 12px;
  color: var(--text-strong);
  font-size: 17px;
  font-weight: 650;
}

.user-drawer.el-drawer .el-drawer__body {
  padding: 8px 24px 24px;
}
</style>
