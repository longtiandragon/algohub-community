<script setup lang="ts">
import { adminLoginPath } from '@/utils/adminHost'
import { computed, onBeforeUnmount, onMounted, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import { get, post, notifyError } from '@/api/client'
import OnboardingLayout from '@/components/onboarding/OnboardingLayout.vue'
import { useAuthStore } from '@/stores/auth'
import { maskEmail } from '@/utils/email'

/**
 * 找回密码（/forgot-password）与设置密码（/forgot-password?mode=set，从「设置与安全」进来）。
 * 两步：① 已验证的登录邮箱 + 验证码 ② 新密码（可显示）+ 再输一次。重置后所有设备都要重新登录。
 * 只对设置过密码、或者想加一个密码的账号有用；同学们用邮箱验证码登录，不需要密码。
 */
const route = useRoute()
const router = useRouter()
const auth = useAuthStore()

const setMode = computed(() => route.query.mode === 'set')
const title = computed(() => (setMode.value ? '设置管理员密码' : '找回管理员密码'))
const back = computed(() =>
  setMode.value && auth.isLoggedIn
    ? { to: { path: '/settings', query: { section: 'login-email' } }, label: '返回设置' }
    : { to: adminLoginPath, label: '返回管理登录' }
)

// 从设置页来的，先填上自己的邮箱
const email = ref(auth.me?.email ?? '')
const code = ref('')
const password = ref('')
const confirm = ref('')
const showPassword = ref(false)
const enabled = ref(false)
const loading = ref(true)
const loadError = ref('')
const sending = ref(false)
const resetting = ref(false)
const seconds = ref(0)
const sentTo = ref('')
let timer: ReturnType<typeof setInterval> | undefined

const trimmed = computed(() => email.value.trim())
const validEmail = computed(() => trimmed.value.length <= 254 && /^[^\s@]+@[^\s@]+\.[^\s@]+$/.test(trimmed.value))
const available = computed(() => !loading.value && !loadError.value && enabled.value)
const codeReady = computed(() => /^\d{6}$/.test(code.value))
const passwordOk = computed(() => password.value.length >= 8 && password.value.length <= 64)
const mismatch = computed(() => confirm.value.length > 0 && confirm.value !== password.value)
const canSend = computed(() => available.value && validEmail.value && !sending.value && seconds.value === 0)
const canReset = computed(
  () => available.value && validEmail.value && codeReady.value && passwordOk.value && confirm.value === password.value && !resetting.value
)

async function load() {
  loading.value = true
  loadError.value = ''
  try {
    enabled.value = (await get<{ enabled: boolean }>('/auth/email/status')).enabled
  } catch (e) {
    loadError.value = e instanceof Error ? e.message : '读不到发件服务的状态'
  } finally {
    loading.value = false
  }
}
onMounted(load)
onBeforeUnmount(() => clearInterval(timer))
watch(trimmed, () => {
  code.value = ''
  sentTo.value = ''
})

async function send() {
  if (!canSend.value) return
  sending.value = true
  try {
    await post<string>('/auth/password-reset/code', { email: trimmed.value })
    // 后端对「这个邮箱有没有账号」一律同样回答，这里也只说发到了哪里
    sentTo.value = maskEmail(trimmed.value)
    code.value = ''
    clearInterval(timer)
    const until = Date.now() + 60_000
    seconds.value = 60
    timer = setInterval(() => {
      seconds.value = Math.max(0, Math.ceil((until - Date.now()) / 1000))
      if (!seconds.value) clearInterval(timer)
    }, 500)
    document.getElementById('reset-code')?.focus()
  } catch (e) {
    notifyError(e)
  } finally {
    sending.value = false
  }
}

async function reset() {
  if (!canReset.value) return
  resetting.value = true
  try {
    await post('/auth/password-reset', { email: trimmed.value, code: code.value, password: password.value })
    code.value = ''
    password.value = ''
    confirm.value = ''
    // 重置后后端让所有设备的登录都失效，本机也一起退出
    auth.clear()
    ElMessage.success(setMode.value ? '密码已设置，请重新登录' : '密码已重置，请用新密码登录')
    await router.push(adminLoginPath)
  } catch (e) {
    notifyError(e)
  } finally {
    resetting.value = false
  }
}

function onCodeInput(event: Event) {
  code.value = (event.target as HTMLInputElement).value.replace(/\D/g, '').slice(0, 6)
}
</script>

<template>
  <OnboardingLayout
    :title="title"
    icon="i-lucide-key-round"
    :description="setMode ? '仅管理员可以设置密码，普通用户请使用邮箱验证码或 GitHub 登录' : '通过管理员已验证的登录邮箱重新设置密码'"
    :back="back"
  >
    <div class="flex gap-2.5 rounded-xl bg-info-soft px-3.5 py-3" role="note">
      <span class="i-lucide-info mt-0.5 shrink-0 text-lg text-info-ink" aria-hidden="true" />
      <span class="text-[13.5px] text-info-ink leading-[1.65]">
        <template v-if="setMode">设置前先验证你已绑定的登录邮箱。设置好以后，所有设备都要重新登录一次。</template>
        <template v-else>只适用于设置过密码的账号。同学们用邮箱验证码登录，不需要密码。</template>
      </span>
    </div>

    <p v-if="loading" class="m-0 text-[13px] text-muted" role="status">正在检查发件服务…</p>
    <div v-else-if="loadError" class="flex items-center gap-3 rounded-xl bg-danger-soft px-3.5 py-2.5 text-[13px] text-danger-ink" role="alert">
      <span class="flex-1">读不到发件服务的状态：{{ loadError }}</span>
      <button type="button" class="auth-link" @click="load">重试</button>
    </div>
    <p v-else-if="!enabled" class="m-0 rounded-xl bg-warning-soft px-3.5 py-2.5 text-[13px] text-warning-ink leading-[1.6]" role="status">
      发件服务还没配置，暂时收不到验证码，请联系管理员。
    </p>

    <form class="flex flex-col gap-[18px]" novalidate @submit.prevent="reset">
      <!-- ① 验证邮箱 -->
      <fieldset class="m-0 flex flex-col gap-3 border-0 p-0">
        <legend class="mb-3 flex items-center gap-2 p-0">
          <span class="step-number" :class="codeReady ? 'is-done' : 'is-current'" aria-hidden="true">
            <span v-if="codeReady" class="i-lucide-check text-[13px]" />
            <template v-else>1</template>
          </span>
          <span class="text-[15px] text-ink font-semibold">验证邮箱</span>
        </legend>
        <div class="flex flex-col gap-1.5">
          <label for="reset-email" class="text-[13px] text-body">已验证的登录邮箱</label>
          <input id="reset-email" v-model="email" type="email" class="auth-input" autocomplete="email" placeholder="name@example.com" @keydown.enter.prevent="send" />
        </div>
        <div class="flex flex-col gap-1.5">
          <div class="flex gap-2">
            <label for="reset-code" class="sr-only">重置验证码</label>
            <input
              id="reset-code"
              :value="code"
              class="auth-input min-w-0 flex-1"
              :class="{ 'num tracking-[0.2em]': code }"
              inputmode="numeric"
              autocomplete="one-time-code"
              maxlength="6"
              placeholder="6 位验证码"
              :aria-describedby="sentTo ? 'reset-code-hint' : undefined"
              @input="onCodeInput"
            />
            <button type="button" class="resend-button" :class="{ 'is-waiting': seconds > 0 }" :disabled="!canSend" @click="send">
              <template v-if="sending">发送中…</template>
              <template v-else-if="seconds"><span class="num">{{ seconds }}</span> 秒后重发</template>
              <template v-else>获取验证码</template>
            </button>
          </div>
          <span v-if="sentTo" id="reset-code-hint" class="text-[13px] text-muted leading-[1.6]" role="status">
            如果 {{ sentTo }} 是已验证的登录邮箱，验证码已经发过去了，10 分钟内有效。
          </span>
        </div>
      </fieldset>

      <!-- ② 设置新密码 -->
      <fieldset class="m-0 flex flex-col gap-3 border-0 p-0">
        <legend class="mb-3 flex items-center gap-2 p-0">
          <span class="step-number" :class="codeReady ? 'is-current' : 'is-todo'" aria-hidden="true">2</span>
          <span class="text-[15px] text-ink font-semibold">设置新密码</span>
        </legend>
        <div class="flex flex-col gap-1.5">
          <label for="reset-password" class="text-[13px] text-body">新密码（8–64 位）</label>
          <div class="relative">
            <input
              id="reset-password"
              v-model="password"
              :type="showPassword ? 'text' : 'password'"
              class="auth-input pr-12"
              maxlength="64"
              autocomplete="new-password"
            />
            <button
              type="button"
              class="reveal-button"
              :aria-label="showPassword ? '隐藏密码' : '显示密码'"
              :aria-pressed="showPassword"
              @click="showPassword = !showPassword"
            >
              <span :class="showPassword ? 'i-lucide-eye-off' : 'i-lucide-eye'" class="text-[19px]" aria-hidden="true" />
            </button>
          </div>
          <span v-if="password && !passwordOk" class="text-[12.5px] text-danger-ink">密码要 8–64 位</span>
        </div>
        <div class="flex flex-col gap-1.5">
          <label for="reset-confirm" class="text-[13px] text-body">再输一次</label>
          <input
            id="reset-confirm"
            v-model="confirm"
            :type="showPassword ? 'text' : 'password'"
            class="auth-input"
            maxlength="64"
            autocomplete="new-password"
            :aria-invalid="mismatch ? 'true' : undefined"
            @keydown.enter.prevent="reset"
          />
          <span v-if="mismatch" class="text-[12.5px] text-danger-ink" role="alert">两次输入的密码不一样</span>
        </div>
        <span class="text-[13px] text-muted">重置后，所有设备都需要重新登录。</span>
      </fieldset>
    </form>

    <template #footer>
      <button type="button" class="auth-button is-primary" :disabled="!canReset" @click="reset">
        {{ resetting ? '正在提交…' : setMode ? '设置密码' : '重置密码' }}
      </button>
    </template>
  </OnboardingLayout>
</template>

<style scoped>
.step-number {
  display: flex;
  flex-shrink: 0;
  align-items: center;
  justify-content: center;
  width: 22px;
  height: 22px;
  border-radius: 50%;
  font-family: var(--font-num);
  font-size: 12px;
  font-weight: 600;
}

.step-number.is-current {
  background: var(--brand-btn);
  color: #fff;
}

.step-number.is-done {
  background: var(--success);
  color: #fff;
}

.step-number.is-todo {
  background: var(--border);
  color: var(--text);
}

.resend-button {
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

.resend-button:disabled {
  cursor: not-allowed;
  opacity: 0.6;
}

.resend-button.is-waiting {
  border-color: var(--border);
  background: var(--bg-subtle);
  color: var(--text-muted);
  font-size: 14px;
  font-weight: 400;
  opacity: 1;
}

.resend-button:focus-visible,
.reveal-button:focus-visible {
  outline: 2px solid var(--brand-500);
  outline-offset: 2px;
}

.reveal-button {
  position: absolute;
  top: 4px;
  right: 4px;
  display: flex;
  align-items: center;
  justify-content: center;
  width: 40px;
  height: 40px;
  padding: 0;
  border: 0;
  border-radius: 10px;
  background: transparent;
  color: var(--text-muted);
  cursor: pointer;
}

@media (min-width: 1024px) {
  .resend-button {
    width: 124px;
    height: 46px;
    border-radius: 10px;
    font-size: 14px;
  }

  .reveal-button {
    top: 3px;
  }
}
</style>
