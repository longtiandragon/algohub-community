<script setup lang="ts">
import { computed, onBeforeUnmount, onMounted, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { get, post, notifyError } from '@/api/client'
import { useAuthStore } from '@/stores/auth'
import type { TokenView } from '@/api/types'
import { maskEmail } from '@/utils/email'

/**
 * 邮箱验证码登录：邮箱 → 获取验证码（同一行，发出后倒计时）→ 登录。
 * 第一次验证会自动创建账号；已有账号的同学先登录再到设置里绑定邮箱，避免多出一个账号（这句写在页面底部说明里）。
 */
const email = ref('')
const code = ref('')
const enabled = ref(false)
const loading = ref(true)
const loadError = ref(false)
const sending = ref(false)
const loggingIn = ref(false)
const seconds = ref(0)
/** 验证码发到了哪个邮箱（打码后显示） */
const sentTo = ref('')
let timer: ReturnType<typeof setInterval> | undefined

const auth = useAuthStore()
const router = useRouter()
const route = useRoute()

const trimmed = computed(() => email.value.trim())
const validEmail = computed(() => trimmed.value.length <= 254 && /^[^\s@]+@[^\s@]+\.[^\s@]+$/.test(trimmed.value))
const validCode = computed(() => /^\d{6}$/.test(code.value))
const available = computed(() => !loading.value && !loadError.value && enabled.value)
const canSend = computed(() => available.value && validEmail.value && !sending.value && seconds.value === 0)
const canLogin = computed(() => available.value && validEmail.value && validCode.value && !loggingIn.value)

async function load() {
  loading.value = true
  loadError.value = false
  try {
    enabled.value = (await get<{ enabled: boolean }>('/auth/email/status')).enabled
  } catch {
    loadError.value = true
    enabled.value = false
  } finally {
    loading.value = false
  }
}
onMounted(load)
onBeforeUnmount(() => clearInterval(timer))

// 换了邮箱，之前的验证码和提示都作废
watch(trimmed, () => {
  code.value = ''
  sentTo.value = ''
})

async function send() {
  if (!canSend.value) return
  sending.value = true
  try {
    await post('/auth/email/code', { email: trimmed.value })
    code.value = ''
    sentTo.value = maskEmail(trimmed.value)
    clearInterval(timer)
    const until = Date.now() + 60_000
    seconds.value = 60
    timer = setInterval(() => {
      seconds.value = Math.max(0, Math.ceil((until - Date.now()) / 1000))
      if (!seconds.value) clearInterval(timer)
    }, 500)
    document.getElementById('login-code')?.focus()
  } catch (e) {
    notifyError(e)
  } finally {
    sending.value = false
  }
}

async function login() {
  if (!canLogin.value) return
  loggingIn.value = true
  try {
    const data = await post<TokenView>('/auth/email/login', { email: trimmed.value, code: code.value })
    if (!await auth.apply(data)) return
    const target = route.query.redirect
    await router.push(typeof target === 'string' && target.startsWith('/') && !target.startsWith('//') && !target.includes('\\') ? target : '/')
  } catch (e) {
    notifyError(e)
  } finally {
    loggingIn.value = false
  }
}

/** 在邮箱框里按回车：还没有验证码就去发，有了就登录 */
function onEmailEnter() {
  if (code.value) void login()
  else void send()
}

function onCodeInput(event: Event) {
  // 只留数字，粘贴「123 456」这种也能用
  code.value = (event.target as HTMLInputElement).value.replace(/\D/g, '').slice(0, 6)
}
</script>

<template>
  <form class="flex flex-col gap-4" novalidate @submit.prevent="login">
    <p v-if="loading" class="m-0 text-[13px] text-muted" role="status">正在检查邮箱登录服务…</p>
    <div v-else-if="loadError" class="flex items-center gap-3 rounded-xl bg-danger-soft px-3.5 py-2.5 text-[13px] text-danger-ink" role="alert">
      <span class="flex-1">读不到邮箱登录服务的状态</span>
      <button type="button" class="auth-link" @click="load">重试</button>
    </div>
    <p v-else-if="!enabled" class="m-0 rounded-xl bg-surface-2 px-3.5 py-2.5 text-[13px] text-body leading-[1.6]" role="status">
      邮箱验证码登录暂未开放，请先用 GitHub 登录，或联系管理员配置发件服务。
    </p>

    <div class="flex flex-col gap-1.5">
      <label for="login-email" class="text-[13.5px] text-ink font-semibold">邮箱</label>
      <input
        id="login-email"
        v-model="email"
        type="email"
        class="auth-input"
        autocomplete="email"
        placeholder="name@example.com"
        :disabled="loggingIn"
        @keydown.enter.prevent="onEmailEnter"
      />
    </div>

    <div class="flex flex-col gap-1.5">
      <label for="login-code" class="text-[13.5px] text-ink font-semibold">验证码</label>
      <div class="flex gap-2">
        <input
          id="login-code"
          :value="code"
          class="auth-input min-w-0 flex-1"
          :class="{ 'tracking-[0.2em]': code }"
          inputmode="numeric"
          autocomplete="one-time-code"
          maxlength="6"
          placeholder="6 位数字"
          :disabled="loggingIn"
          :aria-describedby="sentTo ? 'login-code-hint' : undefined"
          @input="onCodeInput"
        />
        <button type="button" class="send-button" :class="{ 'is-waiting': seconds > 0 }" :disabled="!canSend" @click="send">
          <template v-if="sending">发送中…</template>
          <template v-else-if="seconds"><span class="num">{{ seconds }}</span> 秒后重发</template>
          <template v-else>获取验证码</template>
        </button>
      </div>
      <span v-if="sentTo" id="login-code-hint" class="text-[12.5px] text-muted leading-[1.6]" role="status">
        验证码已发到 {{ sentTo }}，10 分钟内有效
      </span>
    </div>

    <button type="submit" class="auth-button is-primary mt-1" :disabled="!canLogin">
      {{ loggingIn ? '正在登录…' : '登录' }}
    </button>
  </form>
</template>

<style scoped>
.send-button {
  flex-shrink: 0;
  width: 112px;
  height: 48px;
  border: 1px solid color-mix(in srgb, var(--brand-400) 45%, var(--border));
  border-radius: 12px;
  background: var(--brand-soft);
  color: var(--brand-600);
  font: inherit;
  font-size: 15px;
  font-weight: 600;
  white-space: nowrap;
  cursor: pointer;
}

.send-button:hover:not(:disabled) {
  border-color: var(--brand-400);
}

.send-button:disabled {
  cursor: not-allowed;
  opacity: 0.6;
}

/* 倒计时：灰底灰字，不算「不可用」，不再调低透明度 */
.send-button.is-waiting {
  border-color: var(--border);
  background: var(--bg-subtle);
  color: var(--text-muted);
  font-size: 14px;
  font-weight: 400;
  opacity: 1;
}

.send-button:focus-visible {
  outline: 2px solid var(--brand-500);
  outline-offset: 2px;
}

@media (min-width: 1024px) {
  .send-button {
    width: 124px;
    height: 46px;
    border-radius: 10px;
    font-size: 14px;
  }
}
</style>
