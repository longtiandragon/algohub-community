<script setup lang="ts">
import { computed, onBeforeUnmount, onMounted, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { post, notifyError } from '@/api/client'
import { consumeGithubState, startGithubAuthorize, takeGithubIntent } from '@/api/githubAuthorize'
import { useAuthStore } from '@/stores/auth'
import type { TokenView } from '@/api/types'

/**
 * GitHub 授权回来的一页：处理中 → 成功（正在进入平台；新账号提示接下来做什么）或失败（原因 + 重新授权 + 返回）。
 * 未登录时是 GitHub 登录；已登录时是在「设置与安全」里绑定 GitHub，成功后回到那里，失败时第二个按钮是「返回设置」。
 */
const route = useRoute()
const router = useRouter()
const auth = useAuthStore()

const status = ref<'working' | 'done' | 'failed'>('working')
const title = ref('')
const lines = ref<string[]>([])
const retrying = ref(false)
const linkRequired = ref(false)
const intent = ref<ReturnType<typeof takeGithubIntent>>(null)
const retryBinding = computed(() => auth.isLoggedIn && intent.value?.mode !== 'REAUTH')
let active = true
let redirectTimer: ReturnType<typeof setTimeout> | undefined
onBeforeUnmount(() => {
  active = false
  clearTimeout(redirectTimer)
})

/** 失败时第二个按钮：登录着（绑定 GitHub）回设置，否则回登录 */
const backLink = computed(() =>
  auth.isLoggedIn ? { to: { path: '/settings', query: { section: 'oauth' } }, label: '返回设置' } : { to: '/login', label: '返回登录' }
)

function fail(reason: string) {
  status.value = 'failed'
  lines.value = [reason]
}

function succeed(heading: string, message: string[], to: Parameters<typeof router.replace>[0]) {
  status.value = 'done'
  title.value = heading
  lines.value = message
  redirectTimer = setTimeout(() => { void router.replace(to).catch(() => { /* 路由统一处理加载错误 */ }) }, 0)
}

onMounted(async () => {
  const { error, code, state } = route.query
  intent.value = takeGithubIntent(state)
  // 授权码只能用一次：读出来就从地址栏去掉，不留在历史记录和 Referer 里
  void router.replace({ query: {} })
  if (typeof error === 'string' && error) {
    // error_description 是链接里带的文字，谁都能改，不照着显示
    fail(error === 'access_denied' ? '你在 GitHub 取消了授权' : 'GitHub 没有完成授权，请重新授权')
    return
  }
  if (typeof code !== 'string' || typeof state !== 'string' || !code || !state) {
    fail('回来的链接缺少授权信息，请重新授权')
    return
  }
  if (!consumeGithubState(state)) {
    fail('这个授权链接不是从本页面发起的，为了安全没有继续。请点「重新授权」')
    return
  }
  try {
    const result = await post<{ mode: string; token?: TokenView; created?: boolean; actionToken?: string; purpose?: string }>('/auth/github/callback', { code, state })
    if (!active) return
    if (result.mode === 'LINK_REQUIRED') {
      linkRequired.value = true
      fail('这个邮箱已有平台账号。请先验证原账号，再关联 GitHub；原有学习记录会保留。')
    } else if (result.mode === 'REAUTH' && result.actionToken && result.purpose) {
      sessionStorage.setItem(`security-proof:${result.purpose}`, JSON.stringify({ token: result.actionToken, expires: Date.now() + 240000 }))
      const saved = sessionStorage.getItem('security-return') || '/settings'
      sessionStorage.removeItem('security-return')
      const destination = saved.startsWith('/') && !saved.startsWith('//') ? saved : '/settings'
      succeed('身份已确认', ['请再次点击刚才的操作完成提交。'], destination)
    } else if (result.mode === 'LOGIN' && result.token) {
      if (!await auth.apply(result.token)) return
      if (result.created) {
        // 刚用 GitHub 建好账号：路由会依次带去验证邮箱（GitHub 没给已验证邮箱时）和选择学校
        const next = result.token.user.emailVerified ? '第一次使用，接下来选择你的学校' : '第一次使用，接下来先验证登录邮箱，再选择学校'
        succeed('授权成功', ['正在进入平台…', next], '/')
      } else {
        succeed('授权成功', ['正在进入平台…'], '/')
      }
    } else if (result.mode === 'BIND') {
      if (!result.token) throw new Error('绑定已完成，请重新登录')
      if (!await auth.apply(result.token)) return
      // 从设置里绑定的回设置，从登录页「关联已有账号」来的进首页
      const fromSettings = sessionStorage.getItem('security-return')?.startsWith('/settings')
      sessionStorage.removeItem('security-return')
      succeed('GitHub 已关联', ['以后可以直接用 GitHub 登录，其他设备已退出登录。', fromSettings ? '正在回到「设置与安全」…' : '正在进入平台…'],
        fromSettings ? { path: '/settings', query: { section: 'oauth' } } : '/')
    } else {
      throw new Error('授权结果不完整，请重新授权')
    }
  } catch (e) {
    if (!active) return
    fail(e instanceof Error ? e.message : '授权失败，请重新授权')
  }
})

async function retry() {
  if (retrying.value) return
  retrying.value = true
  try {
    if (retryBinding.value) {
      await router.replace('/oauth/github/link')
    } else {
      await startGithubAuthorize(auth.isLoggedIn ? intent.value?.purpose : undefined)
    }
  } catch (e) {
    notifyError(e)
    retrying.value = false
  }
}
</script>

<template>
  <main class="auth-grid-bg min-h-screen flex items-center justify-center p-4 md:p-6">
    <section class="callback-card" aria-live="polite">
      <template v-if="status === 'working'">
        <div class="flex items-center gap-2.5" aria-hidden="true">
          <img src="/algohub-mark.svg" alt="" class="logo-box" />
          <span class="connector" />
          <span class="logo-box github"><span class="i-lucide-github text-2xl" /></span>
        </div>
        <h1 class="card-title">正在完成 GitHub 授权</h1>
        <p class="m-0 text-sm text-muted">马上就好，请不要关闭页面</p>
        <div class="progress" role="progressbar" aria-label="正在授权"><span class="progress-bar" /></div>
      </template>

      <template v-else-if="status === 'done'">
        <span class="state-icon bg-success-soft text-success-ink" aria-hidden="true"><span class="i-lucide-check text-[28px]" /></span>
        <h1 class="card-title">{{ title }}</h1>
        <p class="m-0 text-sm text-muted leading-[1.7]">
          <template v-for="(line, index) in lines" :key="index"><br v-if="index > 0" />{{ line }}</template>
        </p>
      </template>

      <template v-else>
        <span class="state-icon" :class="linkRequired ? 'bg-brand-soft text-brand-600' : 'bg-danger-tint text-danger-ink'" aria-hidden="true"><span :class="linkRequired ? 'i-lucide-link' : 'i-lucide-x'" class="text-[26px]" /></span>
        <h1 class="card-title">{{ linkRequired ? '关联已有账号' : '授权没有完成' }}</h1>
        <p class="m-0 text-sm text-muted leading-[1.7]">{{ lines[0] }}</p>
        <div v-if="linkRequired" class="flex flex-wrap justify-center gap-2.5">
          <RouterLink to="/oauth/github/link" class="card-button is-primary">验证邮箱并关联</RouterLink>
          <RouterLink to="/login" class="card-button is-outline">使用已绑定的方式登录</RouterLink>
        </div>
        <div v-else class="flex flex-wrap justify-center gap-2.5">
          <button type="button" class="card-button is-primary" :disabled="retrying" @click="retry">
            {{ retrying ? '正在跳转…' : retryBinding ? '重新验证并关联' : '重新授权' }}
          </button>
          <RouterLink :to="backLink.to" class="card-button is-outline">{{ backLink.label }}</RouterLink>
        </div>
      </template>
    </section>
  </main>
</template>

<style scoped>
.callback-card {
  display: flex;
  flex-direction: column;
  align-items: center;
  justify-content: center;
  gap: 16px;
  width: 100%;
  max-width: 380px;
  min-height: 300px;
  padding: 32px 28px;
  border: 1px solid var(--border);
  border-radius: 18px;
  background: var(--bg-elevated);
  box-shadow: 0 12px 32px rgba(18, 22, 41, 0.06);
  text-align: center;
}

.card-title {
  margin: 0;
  color: var(--text-strong);
  font-size: 19px;
  font-weight: 650;
}

.logo-box {
  display: flex;
  align-items: center;
  justify-content: center;
  width: 48px;
  height: 48px;
  border-radius: 13px;
}

/* GitHub 的黑底白字；深色主题反过来，免得黑块落在深色卡片上看不见 */
.logo-box.github {
  background: var(--text-strong);
  color: var(--bg-elevated);
}

.connector {
  width: 44px;
  border-top: 2px dashed color-mix(in srgb, var(--brand-400) 50%, transparent);
}

.state-icon {
  display: flex;
  align-items: center;
  justify-content: center;
  width: 56px;
  height: 56px;
  border-radius: 50%;
}

.progress {
  width: 200px;
  height: 4px;
  overflow: hidden;
  border-radius: 2px;
  background: var(--border-soft);
}

.progress-bar {
  display: block;
  width: 45%;
  height: 100%;
  border-radius: 2px;
  background: var(--brand-500);
  animation: sweep 1.2s ease-in-out infinite;
}

@keyframes sweep {
  from {
    transform: translateX(-100%);
  }

  to {
    transform: translateX(225%);
  }
}

/* 不想要动画的同学：停在中间 */
@media (prefers-reduced-motion: reduce) {
  .progress-bar {
    margin-left: 30%;
    animation: none;
  }
}

.card-button {
  display: inline-flex;
  align-items: center;
  height: 40px;
  padding: 0 16px;
  border: 1px solid transparent;
  border-radius: 10px;
  font: inherit;
  font-size: 14px;
  cursor: pointer;
}

.card-button.is-primary {
  background: var(--brand-btn);
  color: #fff;
  font-weight: 600;
}

.card-button.is-primary:hover:not(:disabled) {
  background: var(--brand-btn-hover);
}

.card-button.is-primary:disabled {
  cursor: progress;
  opacity: 0.7;
}

.card-button.is-outline {
  border-color: var(--border-strong);
  background: var(--bg-elevated);
  color: var(--text-strong);
}

.card-button.is-outline:hover {
  background: var(--bg-subtle);
  color: var(--text-strong);
  text-decoration: none;
}

.card-button:focus-visible {
  outline: 2px solid var(--brand-500);
  outline-offset: 2px;
}
</style>
