import axios, { AxiosError, type InternalAxiosRequestConfig } from 'axios'
import { ElMessage } from 'element-plus'
import { shallowRef } from 'vue'

export interface ApiResult<T> {
  success: boolean
  data: T
  errorKind: string | null
  message: string | null
}

export class ApiError extends Error {
  constructor(
    public kind: string,
    message: string
  ) {
    super(message)
  }
}

/**
 * 访问令牌只保存在内存里：不写 localStorage，降低 XSS 拿到长期凭证的风险。
 * 页面刷新后靠 HttpOnly 的刷新令牌 Cookie 重新换取访问令牌。
 */
let accessToken: string | null = null
let sessionUserId: number | null = null
let onUnauthenticated: (() => void) | null = null
let sessionGeneration = 0

interface PendingLogout {
  id: string
  mode: 'current' | 'all'
  userId: number | null
  requiresIdentity?: boolean
}
const LOGOUT_KEY = 'algohub.pending-logout'
const LOGOUT_MESSAGE = '服务端退出尚未完成，已暂停此浏览器的登录恢复；恢复网络后会自动重试'

function parsePendingLogout(raw: string | null): PendingLogout | null {
  try {
    const value = raw ? JSON.parse(raw) : null
    return value && typeof value.id === 'string' && ['current', 'all'].includes(value.mode)
      && (value.userId === null || Number.isSafeInteger(value.userId)) ? value : null
  } catch { return null }
}

function readPendingLogout(): PendingLogout | null {
  let current: PendingLogout | null = null
  for (const storage of ['localStorage', 'sessionStorage'] as const) {
    try {
      const value = parsePendingLogout(window[storage].getItem(LOGOUT_KEY))
      if (value?.mode === 'all') return value
      current ??= value
    } catch { /* Try the tab-local fallback. */ }
  }
  return current
}

/** Only an operation marker and user ID are stored. Tokens remain in memory / HttpOnly cookies. */
export const pendingLogout = shallowRef<PendingLogout | null>(readPendingLogout())
export const logoutMarkerPersistent = shallowRef((() => {
  try { return !pendingLogout.value || parsePendingLogout(localStorage.getItem(LOGOUT_KEY))?.id === pendingLogout.value.id }
  catch { return false }
})())

function writePendingLogout(value: PendingLogout | null) {
  logoutMarkerPersistent.value = false
  for (const storage of ['localStorage', 'sessionStorage'] as const) {
    try {
      if (value) window[storage].setItem(LOGOUT_KEY, JSON.stringify(value))
      else window[storage].removeItem(LOGOUT_KEY)
      if (storage === 'localStorage') {
        logoutMarkerPersistent.value = true
        if (value) { try { sessionStorage.removeItem(LOGOUT_KEY) } catch { /* Tab storage disabled. */ } }
      }
      if (value) break
    } catch { /* The warning asks the user to keep this page open if storage is unavailable. */ }
  }
  pendingLogout.value = value
}

export function getSessionGeneration() { return sessionGeneration }

let localSessionQueue: Promise<unknown> = Promise.resolve()
let localLogoutQueue: Promise<unknown> = Promise.resolve()
function withSessionLock<T>(run: () => Promise<T>, markerOnly = false): Promise<T> {
  const locks = typeof navigator !== 'undefined' ? navigator.locks : undefined
  if (locks) return locks.request(markerOnly ? 'algohub-logout-marker' : 'algohub-refresh', run)
  const next = (markerOnly ? localLogoutQueue : localSessionQueue).then(run, run)
  if (markerOnly) localLogoutQueue = next.catch(() => {})
  else localSessionQueue = next.catch(() => {})
  return next
}

let retryingLogout: Promise<void> | null = null

export function retryPendingLogout(proof?: { accessToken: string; userId: number }): Promise<void> {
  if (retryingLogout) return retryingLogout
  const operation = pendingLogout.value
  if (!operation) return Promise.resolve()
  retryingLogout = withSessionLock(async () => {
    const isCurrent = () => {
      const stored = readPendingLogout()
      return pendingLogout.value?.id === operation.id && (stored?.id === operation.id || (!stored && !logoutMarkerPersistent.value))
    }
    try {
      if (!isCurrent()) throw new ApiError('LOGOUT_PENDING', LOGOUT_MESSAGE)
      let token = proof?.accessToken
      if (proof && proof.userId !== operation.userId) {
        throw new ApiError('LOGOUT_PENDING', '请使用发起退出所有设备的原账号验证身份；其他账号不会被退出')
      }
      const recoverIdentity = async () => {
        try {
          const response = await axios.post<ApiResult<{ accessToken: string; user: { id: number } }>>('/api/auth/refresh', {}, { withCredentials: true, timeout: 10000 })
          if (!response.data.success || response.data.data?.user.id !== operation.userId) {
            throw new ApiError('LOGOUT_IDENTITY', '请重新验证原账号，才能确认退出所有设备')
          }
          token = response.data.data.accessToken
        } catch (error) {
          if ((axios.isAxiosError(error) && error.response?.status === 401) || (error instanceof ApiError && error.kind === 'LOGOUT_IDENTITY')) {
            await withSessionLock(async () => { if (isCurrent()) writePendingLogout({ ...operation, requiresIdentity: true }) }, true)
            throw new ApiError('LOGOUT_PENDING', '尚未确认退出所有设备。请重新登录原账号，仅用于完成退出；不会恢复已登录页面')
          }
          throw error
        }
      }
      if (operation.mode === 'all' && !token) await recoverIdentity()
      if (!isCurrent()) throw new ApiError('LOGOUT_PENDING', LOGOUT_MESSAGE)
      const sendLogout = () => {
        if (!isCurrent()) throw new ApiError('LOGOUT_PENDING', LOGOUT_MESSAGE)
        return axios.post<ApiResult<unknown>>(operation.mode === 'all' ? '/api/auth/logout-all' : '/api/auth/logout', {}, {
          withCredentials: true, timeout: 10000, headers: token ? { Authorization: `Bearer ${token}` } : undefined
        })
      }
      let response
      try { response = await sendLogout() } catch (error) {
        if (!axios.isAxiosError(error) || error.response?.status !== 401 || !token) throw error
        // An expired access token must not prevent revocation of the refresh-cookie session.
        token = undefined
        if (operation.mode === 'all') await recoverIdentity()
        response = await sendLogout()
      }
      if (!response.data.success) throw new ApiError('LOGOUT_PENDING', LOGOUT_MESSAGE)
      // Another tab may have requested a newer or broader logout while this request ran.
      await withSessionLock(async () => { if (isCurrent()) writePendingLogout(null) }, true)
    } catch (error) {
      if (error instanceof ApiError) throw error
      throw new ApiError('LOGOUT_PENDING', LOGOUT_MESSAGE)
    }
  }).finally(() => { retryingLogout = null })
  return retryingLogout
}

export async function logoutSession(all: boolean, userId: number | null): Promise<void> {
  const token = accessToken
  const stored = readPendingLogout()
  const previous = stored?.mode === 'all' ? stored : pendingLogout.value?.mode === 'all' ? pendingLogout.value : stored ?? pendingLogout.value
  const targetUserId = userId ?? previous?.userId ?? null
  if (all && targetUserId === null) throw new ApiError('LOGOUT_PENDING', '当前没有可验证的账号，无法发起退出所有设备')
  const intent = previous?.mode === 'all' ? previous : { id: crypto.randomUUID(), mode: all ? 'all' as const : 'current' as const, userId: targetUserId }
  // Stage in this tab immediately, so a reload while waiting for the marker lock stays blocked.
  try { sessionStorage.setItem(LOGOUT_KEY, JSON.stringify(intent)) } catch { /* The persistent write below reports storage failure. */ }
  pendingLogout.value = intent
  setAccessToken(null)
  await withSessionLock(async () => {
    const latest = readPendingLogout()
    writePendingLogout(latest?.mode === 'all' ? latest : intent)
  }, true)
  await retryPendingLogout(token && userId !== null ? { accessToken: token, userId } : undefined)
  if (pendingLogout.value) throw new ApiError('LOGOUT_PENDING', LOGOUT_MESSAGE)
}

if (typeof window !== 'undefined') {
  window.addEventListener('online', () => { if (pendingLogout.value) void retryPendingLogout().catch(() => {}) })
  window.addEventListener('storage', event => {
    if (event.key !== LOGOUT_KEY) return
    // Events can arrive after newer writes. Clear identity on every event, but read the current marker.
    const next = readPendingLogout()
    setAccessToken(null)
    pendingLogout.value = next
    if (!next) onUnauthenticated?.()
  })
}

export function setAccessToken(token: string | null, userId: number | null = null) {
  sessionGeneration++
  if (token && pendingLogout.value) return
  accessToken = token
  sessionUserId = token ? userId : null
}

export function getAccessToken() {
  return accessToken
}

export function setUnauthenticatedHandler(handler: () => void) {
  onUnauthenticated = handler
}

const http = axios.create({
  baseURL: '/api',
  withCredentials: true,
  timeout: 30000
})

const verificationRequest = (url: string) => /^\/auth\/(login$|email\/(status|code|login)$|github\/(status|authorize-url|callback|link-context|link-code|link-login)$|enrollment-years$)/.test(url)
type SessionConfig = InternalAxiosRequestConfig & { _retried?: boolean; _sessionGeneration?: number; _sessionLocked?: boolean }

http.interceptors.request.use((config: SessionConfig) => {
  if (pendingLogout.value && !(pendingLogout.value.requiresIdentity && verificationRequest(config.url ?? ''))) {
    throw new ApiError('LOGOUT_PENDING', LOGOUT_MESSAGE)
  }
  config._sessionGeneration = sessionGeneration
  if (accessToken) {
    config.headers.Authorization = `Bearer ${accessToken}`
  }
  return config
})

/**
 * 用刷新令牌 Cookie 换新的访问令牌。刷新令牌是一次性轮换的：几个标签页同时拿同一个 Cookie 去换，
 * 后到的会被当成重放、把大家都踢下线。所以跨标签页排队（Web Locks），排到时 Cookie 已经是前一个换好的新值。
 * 同一个标签页里并发的请求共用同一个 Promise。
 */
export function refreshSession<T = { accessToken: string }>(alreadyLocked = false): Promise<ApiResult<T> | null> {
  const generation = sessionGeneration
  const run = async () => {
    if (pendingLogout.value || generation !== sessionGeneration) return null
    const result = await axios
      .post<ApiResult<T>>('/api/auth/refresh', {}, { withCredentials: true, timeout: 10000 })
      .then((res) => res.data)
      .catch((error: AxiosError<ApiResult<T>>) => {
        // 只有服务器明确判定会话失效才能退出；断网、超时和 5xx 不代表 Cookie 已过期。
        if (error.response?.status === 401) return null
        throw new ApiError('NETWORK', '暂时无法恢复登录，请检查网络后重试；无需重新登录')
      })
    return generation === sessionGeneration && !pendingLogout.value ? result : null
  }
  return alreadyLocked ? run() : withSessionLock(run)
}

let refreshing: Promise<string | null> | null = null

async function refreshAccessToken(alreadyLocked = false): Promise<string | null> {
  if (pendingLogout.value) return null
  const renew = () => {
    const generation = sessionGeneration
    const userId = sessionUserId
    return refreshSession<{ accessToken: string; user: { id: number } }>(alreadyLocked)
      .then((result) => {
        const token = result?.success ? (result.data?.accessToken ?? null) : null
        if (generation !== sessionGeneration || pendingLogout.value) return null
        // Cookies are shared across tabs. A different account's cookie must never replay this account's request.
        if (token && (userId === null || result?.data?.user?.id !== userId)) {
          onUnauthenticated?.()
          throw new ApiError('SESSION_CHANGED', '其他标签页已切换账号，请重新登录后再操作')
        }
        accessToken = token
        return token
      })
  }
  // A cookie-issuing request already owns the lock; renew in that same critical section.
  if (alreadyLocked) return renew()
  if (!refreshing) {
    refreshing = renew().finally(() => {
        refreshing = null
      })
  }
  return refreshing
}

/**
 * 不走「401 → 刷新 → 重试」的接口：登录、注册、刷新本身、找回密码这些本来就不带登录态的。
 * 退出、退出所有设备、登录设备、GitHub 绑定等要登录的 /auth 接口照常刷新重试：
 * 闲置半小时访问令牌过期后点退出，也能先换新令牌再真正退出，不会留下还能用的刷新 Cookie。
 */
const NO_REFRESH = /^\/auth\/(refresh|login|register|email\/(login|code|status)|github\/(callback|status|link-login|link-code|link-context)|password-reset)/

http.interceptors.response.use(
  (response) => {
    const config = response.config as SessionConfig
    if (config._sessionGeneration !== sessionGeneration) throw new ApiError('SESSION_CHANGED', '登录状态已变化，请重试')
    return response
  },
  async (error: AxiosError<ApiResult<unknown>>) => {
    if (error instanceof ApiError) return Promise.reject(error)
    const config = error.config as SessionConfig | undefined
    const status = error.response?.status
    const result = error.response?.data
    const skipRefresh = NO_REFRESH.test(config?.url ?? '')

    if (status === 401 && config && config._sessionGeneration === sessionGeneration && !pendingLogout.value && !config._retried && !skipRefresh) {
      config._retried = true
      const token = await refreshAccessToken(config._sessionLocked)
      // A login or logout while refresh was pending supersedes this failed request.
      if (config._sessionGeneration !== sessionGeneration || pendingLogout.value) {
        throw new ApiError('SESSION_CHANGED', '登录状态已变化，请重试')
      }
      if (token) {
        config.headers.Authorization = `Bearer ${token}`
        return http.request(config)
      }
      onUnauthenticated?.()
    }

    // 下载文件（responseType: 'blob'）出错时，后端的 JSON 说明被包在 Blob 里
    let body = result as ApiResult<unknown> | Blob | undefined
    if (typeof Blob !== 'undefined' && body instanceof Blob) {
      try {
        body = JSON.parse(await body.text()) as ApiResult<unknown>
      } catch {
        body = undefined
      }
    }
    const parsed = body as ApiResult<unknown> | undefined
    const kind = parsed?.errorKind ?? (status ? 'UPSTREAM_ERROR' : 'NETWORK')
    const message =
      parsed?.message ?? (status ? '服务暂时不可用，请稍后再试' : '连不上服务器，请检查网络后再试')
    return Promise.reject(new ApiError(kind, message))
  }
)

async function unwrap<T>(promise: Promise<{ data: ApiResult<T> }>): Promise<T> {
  const response = await promise
  if (!response.data.success) {
    throw new ApiError(response.data.errorKind ?? 'INTERNAL', response.data.message ?? '请求失败')
  }
  return response.data.data
}

export function get<T>(url: string, params?: Record<string, unknown>): Promise<T> {
  return unwrap<T>(http.get(url, { params }))
}

export function post<T>(url: string, body?: unknown): Promise<T> {
  // A late login/identity-change response can set a cookie even when its token is discarded.
  // Queue cookie-issuing requests with logout so revocation always follows that response.
  if (/^\/(auth\/(login|register|email\/login|github\/(callback|link-login|unbind))|me\/login-email\/verify|demo\/login)$/.test(url)) {
    return withSessionLock(() => unwrap<T>(http.post(url, body ?? {}, { _sessionLocked: true } as SessionConfig)))
  }
  return unwrap<T>(http.post(url, body ?? {}))
}

export function del<T>(url: string): Promise<T> {
  return unwrap<T>(http.delete(url))
}

/**
 * 「还没验证登录邮箱」是一道一次性的门槛，不是故障。
 *
 * 后端对 /api/** 全局要求已验证的登录邮箱，只放行 /auth/**、/me 与 /me/login-email。
 * 一个页面通常并发好几个请求，如果照常弹红色报错，同学会看到一连串「操作失败」，
 * 却没人告诉他该去哪。所以这里把它单独识别出来，交给页面顶部的引导条，
 * 并且不重复弹窗。
 */
const EMAIL_GATE_HINT = '绑定并验证登录邮箱'
let emailGateHandler: (() => void) | null = null

export function setEmailGateHandler(handler: () => void) {
  emailGateHandler = handler
}

export function isEmailGateError(error: unknown): boolean {
  return (
    error instanceof ApiError && error.kind === 'FORBIDDEN' && error.message.includes(EMAIL_GATE_HINT)
  )
}

/** 统一的错误提示：保留后端的错误分类，便于区分登录失效、限流与源站异常。 */
export function notifyError(error: unknown) {
  if (error instanceof ApiError && error.kind === 'SESSION_CHANGED') return
  if (isEmailGateError(error)) {
    emailGateHandler?.()
    return
  }
  if (error instanceof ApiError) {
    // 错误分类（UPSTREAM_ERROR 这类）只在开发时附上，线上只给同学看得懂的那句话
    ElMessage.error(import.meta.env.DEV ? `${error.message}（${error.kind}）` : error.message)
  } else if (error instanceof Error && error.message) {
    ElMessage.error(error.message)
  } else {
    ElMessage.error('操作失败，请稍后再试')
  }
}

export default http
