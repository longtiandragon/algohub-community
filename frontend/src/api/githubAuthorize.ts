import { get, post } from '@/api/client'

/**
 * 跳去 GitHub 授权前，把这次的 state 记在本标签页（sessionStorage）；授权回来时 state 对不上就不提交，
 * 防止别人把他自己的授权回调链接塞给你打开（登录成他的账号、或把他的 GitHub 绑到你名下）。
 * 服务端另外用 Cookie 核对发起的浏览器，这里是前端这一层。
 */
const KEY = 'algohub.github.state'
const INTENT_KEY = 'algohub.github.intent'
type GithubIntent = { mode: 'LOGIN' | 'BIND' | 'REAUTH'; purpose?: string }
const AUTHORIZE_PREFIX = 'https://github.com/login/oauth/authorize?'

/** purpose：登录/绑定不传；敏感操作前重新授权 GitHub 确认本人时传操作用途 */
export async function startGithubAuthorize(purpose?: string) {
  const { authorizeUrl } = await get<{ authorizeUrl: string }>('/auth/github/authorize-url', purpose ? { purpose } : undefined)
  redirectToGithub(authorizeUrl, purpose ? { mode: 'REAUTH', purpose } : { mode: 'LOGIN' })
}

/** 已登录绑定 GitHub：先确认本人拿到凭证（actionToken），再换授权链接 */
export async function startGithubBind(actionToken: string) {
  const { authorizeUrl } = await post<{ authorizeUrl: string }>('/auth/github/bind-url', { actionToken })
  redirectToGithub(authorizeUrl)
}

export function redirectToGithub(authorizeUrl: string, intent: GithubIntent = { mode: 'BIND' }) {
  if (typeof authorizeUrl !== 'string' || !authorizeUrl.startsWith(AUTHORIZE_PREFIX)) {
    throw new Error('授权地址不对，请稍后再试')
  }
  const state = new URL(authorizeUrl).searchParams.get('state')
  try {
    if (state) {
      sessionStorage.setItem(KEY, state)
      sessionStorage.setItem(INTENT_KEY, JSON.stringify({ ...intent, state, expires: Date.now() + 600000 }))
    }
  } catch {
    // 无痕模式等存不了：回来时跳过前端这层核对，仍有服务端核对
  }
  window.location.assign(authorizeUrl)
}

/** UI continuation only; the server independently validates the actual authorization. */
export function takeGithubIntent(state: unknown): GithubIntent | null {
  try {
    const raw = sessionStorage.getItem(INTENT_KEY)
    sessionStorage.removeItem(INTENT_KEY)
    if (!raw) return null
    const intent = JSON.parse(raw)
    if (typeof state !== 'string' || intent.state !== state || !(intent.expires > Date.now())) return null
    if (intent.mode === 'LOGIN' || intent.mode === 'BIND') return { mode: intent.mode }
    if (intent.mode === 'REAUTH' && ['DELETE_ACCOUNT', 'BIND_GITHUB', 'UNBIND_GITHUB', 'FIRST_EMAIL'].includes(intent.purpose)) {
      return { mode: 'REAUTH', purpose: intent.purpose }
    }
  } catch { /* Missing browser storage must not prevent displaying a recovery option. */ }
  return null
}

/** 回调页用：取出并清掉记下的 state。true = 对上了或本来就没记（存不了）；false = 记了但对不上 */
export function consumeGithubState(state: string): boolean {
  let saved: string | null = null
  let readable = true
  try {
    saved = sessionStorage.getItem(KEY)
    sessionStorage.removeItem(KEY)
  } catch {
    readable = false
  }
  if (!readable) return true
  return saved === state
}
