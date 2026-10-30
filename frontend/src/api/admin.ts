import { get } from '@/api/client'

/**
 * 管理后台：当前管理员的权限和数据概览（接口约定见 docs/2026-09-30-管理端扩充.md）。
 * 用户管理、管理员与权限、操作日志的类型在 adminUsers.ts。
 */

// 权限类型和当前管理员的权限（带缓存）只在 adminUsers.ts 里维护一份
import type { AdminPermission } from '@/api/adminUsers'
export { loadAdminMe, type AdminMe, type AdminPermission, type PermissionInfo } from '@/api/adminUsers'

export const ALL_PERMISSIONS: AdminPermission[] = ['DASHBOARD', 'USERS', 'SCHOOLS', 'CONTENT', 'SYNC', 'PROBLEMS', 'AUDIT', 'ADMINS']

export interface DashboardTrendPoint {
  day: string
  visits: number
  visitors: number
  activeUsers: number
  newUsers: number
}

export interface Dashboard {
  generatedAt: string
  users: {
    total: number
    students: number
    admins: number
    disabled: number
    newToday: number
    new7d: number
    emailVerified: number
    withSchool: number
    withVerifiedAccount: number
  }
  activity: {
    activeToday: number
    active7d: number
    active30d: number
    visitsToday: number
    visitorsToday: number
    guestVisitorsToday: number
  }
  pending: { members: number; appeals: number; failedSync: number }
  trend: DashboardTrendPoint[]
  topPages: { route: string; views: number }[]
  platforms: { platform: string; accounts: number; verified: number }[]
  schools: { id: number; name: string; members: number; pending: number }[]
}

export function loadDashboard(days: number) {
  return get<Dashboard>('/admin/dashboard', { days })
}
