import { ref } from 'vue'
import { get, post } from '@/api/client'

/**
 * 管理后台：用户管理、管理员与权限、操作日志的接口和类型（约定见 docs/2026-09-30-管理端扩充.md 第一、三节）。
 * 当前管理员自己的权限（/admin/me）几个面板都要用，拿一次缓存起来；改了自己以外的人不会影响它。
 */

/** 权限代码；管理员的 permissions 为 null 表示全部权限 */
export type AdminPermission = 'DASHBOARD' | 'USERS' | 'SCHOOLS' | 'CONTENT' | 'SYNC' | 'PROBLEMS' | 'AUDIT' | 'ADMINS'

export interface PermissionInfo {
  code: AdminPermission
  name: string
  description: string
}

export interface AdminMe {
  userId: number
  full: boolean
  permissions: AdminPermission[]
  catalog: PermissionInfo[]
}

export interface AdminUserRow {
  id: number
  username: string
  displayName: string
  email: string | null
  emailVerified: boolean
  avatarUrl: string | null
  role: 'STUDENT' | 'ADMIN'
  schoolAdmin?: boolean
  status: string
  adminPermissions: AdminPermission[] | null
  createdAt: string
  lastLoginAt: string | null
  lastSeenAt: string | null
  schoolName: string | null
  membershipStatus: string | null
  enrollmentYear: number | null
  className: string | null
  accountCount: number
  verifiedAccountCount: number
}

export interface AdminUserSummary {
  total: number
  students: number
  schoolAdmins: number
  admins: number
  disabled: number
  activeToday: number
}

export interface AdminUserPage {
  rows: AdminUserRow[]
  total: number
  page: number
  size: number
  summary: AdminUserSummary
}

export interface AdminUserDetail {
  user: AdminUserRow & { bio: string | null; hasPassword: boolean; githubLogin: string | null }
  memberships: { schoolName: string; status: string; enrollmentYear: number | null; className: string | null; studentNo: string | null; createdAt: string }[]
  accounts: { id: number; platform: string; handle: string; ownershipStatus: string; syncStatus: string; lastSyncedAt: string | null }[]
  events: { event: string; ip: string | null; userAgent: string | null; createdAt: string }[]
  visits: { days30: number; views30: number; lastSeenAt: string | null }
}

export interface AdminRow {
  id: number
  username: string
  displayName: string
  email: string | null
  status: string
  full: boolean
  permissions: AdminPermission[] | null
  hasPassword: boolean
  lastLoginAt: string | null
  lastSeenAt: string | null
}

export interface AuditRow {
  id: number
  actorId: number | null
  actorName: string
  method: string
  pattern: string
  target: string | null
  status: number
  ip: string | null
  createdAt: string
}

export interface AuditPage {
  rows: AuditRow[]
  total: number
  page: number
  size: number
}

/** 后端没给权限说明时用这一份（和约定文档第一节一致） */
export const PERMISSION_CATALOG: PermissionInfo[] = [
  { code: 'DASHBOARD', name: '数据概览', description: '查看用户数量、访问量和趋势' },
  { code: 'USERS', name: '用户管理', description: '查看全部用户、禁用或启用账号、强制下线' },
  { code: 'SCHOOLS', name: '学校与成员', description: '学校与邀请码、入校审批、学生资料、年级配置' },
  { code: 'CONTENT', name: '公告与内容', description: '公告与资源、学习提醒、导航维护' },
  { code: 'SYNC', name: '同步与申诉', description: '同步状态、重试同步、归属申诉、比赛列表' },
  { code: 'PROBLEMS', name: '题库与标签', description: '题目映射、标签分类、题单与训练活动' },
  { code: 'AUDIT', name: '操作日志', description: '查看管理员的每一次修改' },
  { code: 'ADMINS', name: '管理员与权限', description: '添加管理员、调整权限、取消管理员' }
]

export const fetchUsers = (params: Record<string, unknown>) => get<AdminUserPage>('/admin/users', params)
export const fetchUser = (id: number) => get<AdminUserDetail>(`/admin/users/${id}`)
export const setUserStatus = (id: number, status: 'ACTIVE' | 'DISABLED', reason: string | null) =>
  post<AdminUserRow>(`/admin/users/${id}/status`, { status, reason })
export const logoutUser = (id: number) => post<{ revokedSessions: number }>(`/admin/users/${id}/logout`)
export const fetchAdmins = () => get<AdminRow[]>('/admin/admins')
export const saveAdmin = (userId: number, role: 'ADMIN' | 'STUDENT', permissions: AdminPermission[] | null) =>
  post<unknown>(`/admin/admins/${userId}`, { role, permissions })
export const fetchAudit = (params: Record<string, unknown>) => get<AuditPage>('/admin/audit', params)

/* ------------------------------ 当前管理员 ------------------------------ */

const adminMe = ref<AdminMe | null>(null)
let pending: Promise<AdminMe> | null = null

/** 当前管理员的权限，整个后台共用一份；force 时重新拿 */
export function loadAdminMe(force = false): Promise<AdminMe> {
  if (!force && adminMe.value) return Promise.resolve(adminMe.value)
  if (!force && pending) return pending
  pending = get<AdminMe>('/admin/me')
    .then((data) => (adminMe.value = data))
    .finally(() => {
      pending = null
    })
  return pending
}

export function useAdminMe() {
  return adminMe
}

/** 有没有某项权限；传进来的 permissions（AdminView 给的）优先，没有就看 /admin/me */
export function hasPermission(code: AdminPermission, given?: readonly string[] | null) {
  if (given) return given.includes(code)
  const me = adminMe.value
  return !!me && (me.full || me.permissions.includes(code))
}

export function permissionCatalog(): PermissionInfo[] {
  return adminMe.value?.catalog?.length ? adminMe.value.catalog : PERMISSION_CATALOG
}

export function permissionName(code: string) {
  return permissionCatalog().find((p) => p.code === code)?.name ?? code
}

/** 「全部权限或有『管理员与权限』」的正常管理员：系统至少要留一个 */
export const isKeyAdmin = (a: Pick<AdminRow, 'status' | 'full' | 'permissions'>) =>
  a.status === 'ACTIVE' && (a.full || !!a.permissions?.includes('ADMINS'))

/* ------------------------------ 显示用的文字 ------------------------------ */

/**
 * 列表里代表一个账号的那一行字：邮箱注册的同学登录名是系统生成的 mail_xxx，管理员看不懂，
 * 所以有邮箱就显示邮箱；没有邮箱时，自动生成的名字写「未留邮箱」，其他（admin、gh_xxx）照常显示登录名。
 */
export const accountLabel = (u: { username: string; email?: string | null }) =>
  u.email || (u.username.startsWith('mail_') ? '未留邮箱' : u.username)

export const formatTime = (value: string | null | undefined) =>
  value ? new Date(value).toLocaleString('zh-CN', { timeZone: 'Asia/Shanghai', hour12: false }) : '—'

export const USER_STATUS: Record<string, { label: string; tone: 'success' | 'danger' | 'neutral' }> = {
  ACTIVE: { label: '正常', tone: 'success' },
  DISABLED: { label: '已禁用', tone: 'danger' }
}

export const MEMBERSHIP_STATUS: Record<string, { label: string; tone: 'success' | 'warning' | 'danger' | 'neutral' }> = {
  APPROVED: { label: '已入校', tone: 'success' },
  PENDING: { label: '待审核', tone: 'warning' },
  REJECTED: { label: '已拒绝', tone: 'danger' }
}

export const OWNERSHIP_STATUS: Record<string, { label: string; tone: 'success' | 'warning' | 'danger' | 'neutral' }> = {
  VERIFIED: { label: '已验证归属', tone: 'success' },
  RESOLVED: { label: '未验证', tone: 'neutral' },
  UNVERIFIED: { label: '未验证', tone: 'neutral' },
  MISMATCH: { label: '归属不一致', tone: 'danger' }
}

/**
 * 操作日志里的接口模板翻成中文。先找完全一样的，再按「方法 + 前缀」认出是哪一类操作；都认不出就原样显示。
 */
const EXACT: Record<string, string> = {
  'POST /api/admin/users/{id}/status': '修改账号状态',
  'POST /api/admin/users/{id}/logout': '强制下线',
  'POST /api/admin/admins/{userId}': '设置管理员权限',
  'POST /api/admin/schools': '新建学校',
  'POST /api/admin/schools/{id}': '修改学校',
  'DELETE /api/admin/schools/{id}': '删除学校',
  'POST /api/admin/schools/{id}/invite-code': '生成或更换邀请码',
  'DELETE /api/admin/schools/{id}/invite-code': '停用邀请码',
  'POST /api/admin/members/{id}/review': '审核入校申请',
  'POST /api/admin/members/{id}/profile': '修改成员资料',
  'POST /api/admin/enrollment-years': '修改年级配置',
  'POST /api/admin/announcements': '新建公告或资源',
  'POST /api/admin/announcements/{id}': '修改公告或资源',
  'DELETE /api/admin/announcements/{id}': '删除公告或资源',
  'POST /api/admin/nav': '新建导航链接',
  'POST /api/admin/nav/{id}': '修改导航链接',
  'DELETE /api/admin/nav/{id}': '删除导航链接',
  'POST /api/admin/reminders/users/{id}/goals': '设置学习目标',
  'POST /api/admin/reminders/users/{id}/send': '发送学习提醒',
  'POST /api/admin/sync/accounts/{accountId}/retry': '重试同步',
  'POST /api/admin/sync/accounts/{accountId}/rebuild': '重算每日统计',
  'DELETE /api/admin/sync/accounts/{accountId}': '代解绑平台账号',
  'POST /api/admin/sync/contests/refresh': '刷新比赛列表',
  'POST /api/admin/sync/catalog': '同步题库目录',
  'POST /api/admin/contests/{id}/category': '修改比赛分类',
  'POST /api/admin/ownership-appeals/{id}/approve': '通过归属申诉',
  'POST /api/admin/ownership-appeals/{id}/reject': '驳回归属申诉'
}
const AREAS: [string, string][] = [
  ['/api/admin/users', '用户'],
  ['/api/admin/admins', '管理员'],
  ['/api/admin/schools', '学校'],
  ['/api/admin/members', '入校成员'],
  ['/api/admin/students', '学生'],
  ['/api/admin/enrollment-years', '年级配置'],
  ['/api/admin/announcements', '公告'],
  ['/api/admin/nav', '导航'],
  ['/api/admin/reminders', '学习提醒'],
  ['/api/admin/sync', '同步'],
  ['/api/admin/tasks', '同步任务'],
  ['/api/admin/contests', '比赛'],
  ['/api/admin/ownership-appeals', '归属申诉'],
  ['/api/admin/problem-groups', '题目映射'],
  ['/api/admin/problem-lists', '题单'],
  ['/api/admin/tag-mappings', '标签分类'],
  ['/api/admin/activities', '训练活动']
]
const VERBS: Record<string, string> = { POST: '修改', PUT: '修改', PATCH: '修改', DELETE: '删除' }

export function auditActionLabel(method: string, pattern: string): string | null {
  // pattern 里一般已经带着方法（POST /api/...）；兼容只存了路径的写法
  const full = /^[A-Z]+ /.test(pattern) ? pattern : `${method} ${pattern}`
  if (EXACT[full]) return EXACT[full]
  const path = full.replace(/^[A-Z]+ /, '')
  const area = AREAS.find(([prefix]) => path === prefix || path.startsWith(`${prefix}/`))
  if (!area) return null
  return `${VERBS[method] ?? '操作'}${area[1]}`
}
