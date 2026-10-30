import { post } from '@/api/client'
import { isAdminHost } from '@/utils/adminHost'

/**
 * 访问统计：每次切换页面上报一次路由名（后端按「每个访客每天一行」累加，管理员不计入，
 * 见 docs/2026-09-30-管理端扩充.md）。只报路由名，不报完整路径，同学档案的编号之类不会被记下来。
 * 同一路由 2 秒内只报一次（重定向、刷新令牌重试都可能连着触发）；管理员域名上不报；上报失败不打扰页面。
 */
const ROUTE = /^[a-z0-9-]{1,64}$/
let last = { route: '', at: 0 }

export function recordVisit(route: unknown) {
  if (isAdminHost || typeof route !== 'string' || !ROUTE.test(route)) return
  const now = Date.now()
  if (route === last.route && now - last.at < 2000) return
  last = { route, at: now }
  post('/visits', { route }).catch(() => undefined)
}
