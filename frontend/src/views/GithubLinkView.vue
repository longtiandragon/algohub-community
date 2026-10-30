<script setup lang="ts">
import { computed, onBeforeUnmount, onMounted, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ApiError, get, post, notifyError } from '@/api/client'
import { startGithubAuthorize } from '@/api/githubAuthorize'
import { useAuthStore } from '@/stores/auth'
import type { TokenView } from '@/api/types'

const route = useRoute()
const auth = useAuthStore()
const router = useRouter()
const context = ref<{ maskedEmail: string; githubLogin: string; hasPassword: boolean } | null>(null)
const loading = ref(true)
const contextError = ref('')
onMounted(async () => {
  try { context.value = await get('/auth/github/link-context') }
  catch (e) { contextError.value = e instanceof Error ? e.message : '关联已失效，请重新发起' }
  finally { loading.value = false }
})
async function restart() {
  if (auth.isLoggedIn) { await router.push('/settings?section=oauth'); return }
  try { await startGithubAuthorize() } catch (e) { notifyError(e) }
}
const passwordMode = computed(() => route.query.mode === 'password' && context.value?.hasPassword)
const code = ref('')
const password = ref('')
const busy = ref(false)
const completed = ref(false)
const sending = ref(false)
const seconds = ref(0)
const sent = ref(false)
let timer: ReturnType<typeof setInterval> | undefined
onBeforeUnmount(() => clearInterval(timer))
const canSubmit = computed(() => !!context.value && !busy.value && (passwordMode.value
  ? !!password.value : /^\d{6}$/.test(code.value)))

function handleFailure(error: unknown) {
  if (error instanceof ApiError && ['FORBIDDEN', 'CONFLICT'].includes(error.kind)) {
    context.value = null
    contextError.value = error.message
    code.value = ''
    password.value = ''
    clearInterval(timer)
    return
  }
  notifyError(error)
}

async function sendCode() {
  if (sending.value || seconds.value || !context.value) return
  sending.value = true
  try {
    await post('/auth/github/link-code')
    sent.value = true
    const until = Date.now() + 60000
    seconds.value = 60
    clearInterval(timer)
    timer = setInterval(() => {
      seconds.value = Math.max(0, Math.ceil((until - Date.now()) / 1000))
      if (!seconds.value) clearInterval(timer)
    }, 500)
  } catch (e) { handleFailure(e) }
  finally { sending.value = false }
}

async function submit() {
  if (!canSubmit.value) return
  busy.value = true
  try {
    const result = await post<{ token: TokenView }>('/auth/github/link-login', passwordMode.value
      ? { method: 'password', password: password.value }
      : { method: 'email', code: code.value })
    if (!await auth.apply(result.token)) return
    completed.value = true
    password.value = ''
    code.value = ''
    // 关联成功以后只处理跳转；不能把页面分包失败当成验证码或绑定失败。
    await router.replace('/settings?section=oauth')
  } catch (e) {
    if (!completed.value) handleFailure(e)
    busy.value = false
  }
}
</script>

<template>
  <main class="auth-grid-bg min-h-screen flex items-center justify-center p-4 md:p-6">
    <section class="link-card" :class="{ 'is-centered': !loading && !context }" aria-live="polite">
      <template v-if="completed">
        <h1 class="card-title">GitHub 已关联成功</h1>
        <p class="m-0 text-sm text-muted">关联已保存，以后可以使用 GitHub 登录。</p>
        <RouterLink to="/settings?section=oauth" class="card-button is-primary">查看第三方账号</RouterLink>
      </template>
      <template v-else>
      <!-- 加载中和正常状态：卡片头和 GitHub 授权回调页「处理中」一样，两个图标连一条虚线 -->
      <div v-if="loading || context" class="flex flex-col items-center gap-3 text-center">
        <div class="flex items-center gap-2.5" aria-hidden="true">
          <img src="/algohub-mark.svg" alt="" class="logo-box" />
          <span class="connector" />
          <span class="logo-box github"><span class="i-lucide-github text-2xl" /></span>
        </div>
        <h1 class="card-title">关联 GitHub 到已有账号</h1>
        <p v-if="context" class="m-0 text-sm text-muted leading-[1.7]">将 GitHub <strong class="text-ink">{{ context.githubLogin }}</strong> 关联到下面这个邮箱的原账号，学习记录会保留。</p>
        <p v-else role="status" class="m-0 text-sm text-muted">正在确认关联信息…</p>
      </div>
      <template v-if="context">
      <div class="rounded-xl bg-surface-2 p-3.5">
        <span class="block text-xs text-muted">已确认的原账号邮箱 · 不可更改</span>
        <strong class="mt-1 block break-all text-base text-ink" data-test="locked-email">{{ context.maskedEmail }}</strong>
      </div>
      <form class="flex flex-col gap-4" @submit.prevent="submit">
        <template v-if="passwordMode">
          <label class="flex flex-col gap-1.5 text-[13px] text-body">密码
            <input v-model="password" class="auth-input" type="password" autocomplete="current-password" maxlength="64" required :disabled="busy" placeholder="原账号密码" />
          </label>
        </template>
        <template v-else>
          <div class="flex flex-col gap-1.5">
            <label for="github-link-code" class="text-[13px] text-body">邮箱验证码</label>
            <div class="flex gap-2">
              <input id="github-link-code" v-model="code" class="auth-input min-w-0 flex-1" inputmode="numeric" autocomplete="one-time-code" pattern="[0-9]{6}" maxlength="6" required :disabled="busy" placeholder="6 位验证码" />
              <button class="code-button" type="button" :disabled="busy || sending || seconds > 0 || !context" @click="sendCode">{{ sending ? '发送中…' : seconds ? `${seconds} 秒后重发` : '获取验证码' }}</button>
            </div>
            <p v-if="sent" class="m-0 text-xs text-muted" role="status">验证码已发送，5 分钟内有效。</p>
          </div>
        </template>
        <button type="submit" class="auth-button is-primary" :disabled="!canSubmit">{{ busy ? '正在关联…' : '验证并关联 GitHub' }}</button>
      </form>
      <div class="flex flex-wrap justify-between gap-3 text-sm">
        <RouterLink v-if="context.hasPassword" :to="passwordMode ? '/oauth/github/link' : '/oauth/github/link?mode=password'">{{ passwordMode ? '验证邮箱并关联' : '使用原账号登录' }}</RouterLink>
        <RouterLink to="/login">暂不关联，返回登录</RouterLink>
      </div>
      <p class="m-0 text-xs text-muted leading-[1.7]">验证码仅用于本次 GitHub 关联。关联后，该 GitHub 将可以登录此平台账号。请勿向他人提供验证码。</p>
      </template>
      <!-- 出错：和授权回调页的「授权没有完成」一样，图标、标题、原因、按钮都居中 -->
      <template v-else-if="!loading">
        <span class="state-icon bg-danger-tint text-danger-ink" aria-hidden="true"><span class="i-lucide-link-2-off text-[26px]" /></span>
        <h1 class="card-title">关联没有完成</h1>
        <p class="m-0 text-sm text-muted leading-[1.7]" role="alert">{{ contextError || '关联已失效，请重新发起' }}</p>
        <div class="flex flex-wrap justify-center gap-2.5">
          <button type="button" class="card-button is-primary" @click="restart">{{ auth.isLoggedIn ? '返回账号安全' : '重新使用 GitHub 登录' }}</button>
          <RouterLink :to="auth.isLoggedIn ? '/' : '/login'" class="card-button is-outline">{{ auth.isLoggedIn ? '回到首页' : '返回登录' }}</RouterLink>
        </div>
      </template>
      </template>
    </section>
  </main>
</template>

<style scoped>
.link-card { display: flex; flex-direction: column; gap: 20px; width: 100%; max-width: 440px; padding: 30px 26px; border: 1px solid var(--border); border-radius: 18px; background: var(--bg-elevated); box-shadow: 0 12px 32px rgba(18,22,41,.06); }
/* 出错时整张卡居中，和 OauthCallbackView 的失败状态同一个样子（图标、标题、按钮的尺寸也照它） */
.link-card.is-centered { align-items: center; justify-content: center; gap: 16px; max-width: 380px; min-height: 300px; padding: 32px 28px; text-align: center; }
.card-title { margin: 0; color: var(--text-strong); font-size: 19px; font-weight: 650; }
.logo-box { display: flex; align-items: center; justify-content: center; width: 48px; height: 48px; border-radius: 13px; }
/* GitHub 的黑底白字；深色主题反过来，免得黑块落在深色卡片上看不见 */
.logo-box.github { background: var(--text-strong); color: var(--bg-elevated); }
.connector { width: 44px; border-top: 2px dashed color-mix(in srgb, var(--brand-400) 50%, transparent); }
.state-icon { display: flex; align-items: center; justify-content: center; width: 56px; height: 56px; border-radius: 50%; }
.card-button { display: inline-flex; align-items: center; height: 40px; padding: 0 16px; border: 1px solid transparent; border-radius: 10px; font: inherit; font-size: 14px; cursor: pointer; }
.card-button.is-primary { background: var(--brand-btn); color: #fff; font-weight: 600; }
.card-button.is-primary:hover { background: var(--brand-btn-hover); }
.card-button.is-outline { border-color: var(--border-strong); background: var(--bg-elevated); color: var(--text-strong); }
.card-button.is-outline:hover { background: var(--bg-subtle); color: var(--text-strong); text-decoration: none; }
.card-button:focus-visible { outline: 2px solid var(--brand-500); outline-offset: 2px; }
.code-button { flex-shrink: 0; padding: 0 12px; border: 1px solid var(--border-strong); border-radius: 10px; background: var(--brand-soft); color: var(--brand-600); font: inherit; font-size: 13px; cursor: pointer; }
.code-button:disabled { opacity: .6; cursor: not-allowed; }
.code-button:focus-visible { outline: 2px solid var(--brand-500); outline-offset: 2px; }
@media(max-width: 400px) { .link-card { padding: 24px 18px; } }
</style>
