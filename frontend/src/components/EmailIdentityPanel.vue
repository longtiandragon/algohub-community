<script setup lang="ts">
import { confirmIdentity } from '@/api/securityAction'
import { computed, nextTick, onBeforeUnmount, onMounted, ref, watch } from 'vue'
import { ElMessage } from 'element-plus'
import { get, post, notifyError } from '@/api/client'
import SectionCard from '@/components/ui/SectionCard.vue'
import StatusBadge from '@/components/ui/StatusBadge.vue'
import { useAuthStore } from '@/stores/auth'
import type { TokenView } from '@/api/types'
import { maskEmail } from '@/utils/email'

/**
 * 登录邮箱：绑定或更换，都要收验证码。
 * - full（设置页）：一行写当前邮箱和「更换」，点了才展开更换：填新邮箱 → 发验证码（原邮箱、新邮箱各一封）→ 两个验证码都填上 → 验证并更换。
 *   还没有登录邮箱的直接展开绑定表单。
 * - first（验证邮箱引导页）：只有邮箱和验证码两项，主按钮「验证并继续」在页面底部，由页面调用 verify()。
 * 验证成功后发出 verified。
 */
const props = withDefaults(defineProps<{ mode?: 'full' | 'first' }>(), { mode: 'full' })
const emit = defineEmits<{ verified: [] }>()

const auth = useAuthStore()
const status = ref<{ enabled: boolean; email: string | null }>({ enabled: false, email: null })
// 首次验证时先填上资料里的邮箱（邮箱登录、GitHub 登录的同学通常已经有）
const email = ref(props.mode === 'first' ? auth.me?.email ?? '' : '')
const code = ref('')
const previousCode = ref('')
const sending = ref(false)
const verifying = ref(false)
const loading = ref(true)
const loadError = ref('')
const remaining = ref(0)
const sentTo = ref('')
/** 设置页：点了「更换」才展开更换表单；还没有登录邮箱时一直展开 */
const editing = ref(false)
const showForm = computed(() => editing.value || !status.value.email)
let timer: ReturnType<typeof setInterval> | undefined

const busy = computed(() => sending.value || verifying.value)
const trimmed = computed(() => email.value.trim())
const validEmail = computed(() => trimmed.value.length <= 254 && /^[^\s@]+@[^\s@]+\.[^\s@]+$/.test(trimmed.value))
const ready = computed(() => !loading.value && !loadError.value && status.value.enabled)
const canSend = computed(() => ready.value && !busy.value && validEmail.value && remaining.value === 0)
const canVerify = computed(
  () =>
    ready.value &&
    !busy.value &&
    validEmail.value &&
    /^\d{6}$/.test(code.value) &&
    (!status.value.email || /^\d{6}$/.test(previousCode.value))
)

function cooldown() {
  clearInterval(timer)
  const until = Date.now() + 60_000
  remaining.value = 60
  timer = setInterval(() => {
    remaining.value = Math.max(0, Math.ceil((until - Date.now()) / 1000))
    if (!remaining.value) clearInterval(timer)
  }, 500)
}

// 换了邮箱，之前的验证码都作废
watch(trimmed, () => {
  code.value = ''
  previousCode.value = ''
  sentTo.value = ''
})
onBeforeUnmount(() => clearInterval(timer))

async function load() {
  loading.value = true
  loadError.value = ''
  try {
    status.value = await get('/me/login-email')
  } catch (e) {
    loadError.value = (e as Error).message
  } finally {
    loading.value = false
  }
}
onMounted(load)

async function send() {
  if (!canSend.value) return
  const target = trimmed.value
  sending.value = true
  try {
    await post('/me/login-email/code', { email: target })
    sentTo.value = target
    code.value = ''
    previousCode.value = ''
    cooldown()
    if (props.mode === 'first') document.getElementById('verify-code')?.focus()
    else {
      ElMessage.success(status.value.email ? '验证码已分别发送到原邮箱和新邮箱，10 分钟内有效' : '验证码已发送，10 分钟内有效')
      document.getElementById(status.value.email ? 'login-email-old-code' : 'login-email-code')?.focus()
    }
  } catch (e) {
    if (e !== 'cancel' && e !== 'close') notifyError(e)
  } finally {
    sending.value = false
  }
}

async function verify() {
  if (!canVerify.value) return
  verifying.value = true
  try {
    const actionToken = status.value.email ? undefined : await confirmIdentity('FIRST_EMAIL')
    const token = await post<TokenView | null>('/me/login-email/verify', {
      actionToken,
      email: trimmed.value,
      code: code.value,
      previousCode: status.value.email ? previousCode.value : undefined
    })
    code.value = ''
    sentTo.value = ''
    email.value = ''
    editing.value = false
    const replaced = !!status.value.email
    // 换邮箱：其他设备已退出，这台设备换了新的登录状态；第一次绑定不影响登录
    if (token) {
      if (!await auth.apply(token)) return
    } else await auth.refreshMe()
    ElMessage.success(replaced ? '登录邮箱已更换，其他设备已退出登录' : '登录邮箱已验证')
    await load()
    emit('verified')
  } catch (e) {
    if (e !== 'cancel' && e !== 'close') notifyError(e)
  } finally {
    verifying.value = false
  }
}

/** 首次验证：在邮箱框按回车，还没有验证码就去发，有了就验证 */
function onEmailEnter() {
  if (code.value) void verify()
  else void send()
}

function onCodeInput(event: Event) {
  code.value = (event.target as HTMLInputElement).value.replace(/\D/g, '').slice(0, 6)
}

/** 设置页的两个验证码框只留数字 */
const digits = (value: string) => value.replace(/\D/g, '').slice(0, 6)

async function startEdit() {
  editing.value = true
  await nextTick()
  document.getElementById('login-email-new')?.focus()
}

function cancelEdit() {
  editing.value = false
  email.value = ''
  code.value = ''
  previousCode.value = ''
  sentTo.value = ''
}

defineExpose({ verify, canVerify, verifying })
</script>

<template>
  <!-- 首次验证：邮箱 + 验证码，主按钮在页面上（表单里没有提交按钮，回车由两个输入框自己处理） -->
  <form v-if="mode === 'first'" class="flex flex-col gap-[18px]" novalidate @submit.prevent="verify">
    <p v-if="loading" class="m-0 text-[13px] text-muted" role="status">正在读取…</p>
    <div v-else-if="loadError" class="flex items-center gap-3 rounded-xl bg-danger-soft px-3.5 py-2.5 text-[13px] text-danger-ink" role="alert">
      <span class="flex-1">读取邮箱状态失败：{{ loadError }}</span>
      <button type="button" class="auth-link" @click="load">重试</button>
    </div>
    <p v-else-if="!status.enabled" class="m-0 rounded-xl bg-warning-soft px-3.5 py-2.5 text-[13px] text-warning-ink leading-[1.6]" role="status">
      发件服务还没配置，暂时收不到验证码，请联系管理员。
    </p>

    <div class="flex flex-col gap-1.5">
      <label for="verify-email" class="text-[13.5px] text-ink font-semibold">邮箱</label>
      <input
        id="verify-email"
        v-model="email"
        type="email"
        class="auth-input"
        autocomplete="email"
        placeholder="name@example.com"
        :disabled="verifying"
        @keydown.enter.prevent="onEmailEnter"
      />
    </div>
    <div class="flex flex-col gap-1.5">
      <label for="verify-code" class="text-[13.5px] text-ink font-semibold">验证码</label>
      <div class="flex gap-2">
        <input
          id="verify-code"
          :value="code"
          class="auth-input min-w-0 flex-1"
          :class="{ 'num text-lg tracking-[0.3em]': code }"
          inputmode="numeric"
          autocomplete="one-time-code"
          maxlength="6"
          placeholder="6 位数字"
          :disabled="verifying"
          :aria-describedby="sentTo ? 'verify-code-hint' : undefined"
          @input="onCodeInput"
          @keydown.enter.prevent="verify"
        />
        <button type="button" class="resend-button" :class="{ 'is-waiting': remaining > 0 }" :disabled="!canSend" @click="send">
          <template v-if="sending">发送中…</template>
          <template v-else-if="remaining"><span class="num">{{ remaining }}</span> 秒后重发</template>
          <template v-else>获取验证码</template>
        </button>
      </div>
      <span v-if="sentTo" id="verify-code-hint" class="text-[13px] text-muted leading-[1.6]" role="status">
        已发到 {{ maskEmail(sentTo) }}，10 分钟内有效。收不到就看看垃圾邮件。
      </span>
    </div>
  </form>

  <!-- 设置页：当前登录邮箱一行 + 「更换」，展开后是更换表单 -->
  <SectionCard v-else title="登录邮箱" description="用来接收登录验证码、找回账号；和个人资料里的联系邮箱是两回事。" data-test="login-email-panel">
    <p v-if="loading" class="m-0 text-[13px] text-muted" role="status">正在读取…</p>
    <div v-else-if="loadError" class="flex items-center gap-3 rounded-xl bg-danger-soft px-3.5 py-2.5 text-[13px] text-danger-ink" role="alert">
      <span class="flex-1">读取登录邮箱失败：{{ loadError }}</span>
      <el-button size="small" @click="load">重试</el-button>
    </div>
    <template v-else>
      <div class="flex items-center gap-3">
        <span class="size-10 flex shrink-0 items-center justify-center rounded-full bg-brand-soft text-brand-600" aria-hidden="true">
          <span class="i-lucide-mail text-lg" />
        </span>
        <div class="min-w-0 flex flex-1 flex-col gap-0.5">
          <span class="break-all text-sm text-ink font-semibold leading-5">{{ status.email ?? '还没有登录邮箱' }}</span>
          <StatusBadge v-if="status.email" tone="success">已验证</StatusBadge>
          <StatusBadge v-else tone="warning">还没绑定，在下面填邮箱</StatusBadge>
        </div>
        <el-button v-if="status.email && !editing" class="shrink-0" data-test="login-email-change" @click="startEdit">更换</el-button>
      </div>

      <form v-if="showForm" class="mt-4 flex flex-col gap-4 border-0 border-t border-line-soft border-solid pt-4" novalidate @submit.prevent="verify">
        <p v-if="!status.enabled" class="m-0 rounded-xl bg-warning-soft px-3.5 py-2.5 text-[13px] text-warning-ink leading-[1.6]" role="status">
          发件服务还没配置，暂时收不到验证码，请联系管理员。
        </p>

        <div class="flex flex-col gap-1.5">
          <label for="login-email-new" class="text-[13px] text-ink font-medium">{{ status.email ? '新邮箱' : '邮箱' }}</label>
          <div class="flex gap-2">
            <el-input
              id="login-email-new"
              v-model="email"
              type="email"
              autocomplete="email"
              placeholder="name@example.com"
              class="min-w-0 flex-1"
              :disabled="verifying"
              @keydown.enter.prevent="send"
            />
            <el-button class="send-button" :disabled="!canSend" :loading="sending" @click="send">
              <template v-if="remaining"><span class="num">{{ remaining }}</span>&nbsp;秒后重发</template>
              <template v-else>{{ sentTo ? '重新发送' : '发送验证码' }}</template>
            </el-button>
          </div>
          <span class="text-[12.5px] text-muted leading-5" role="status">
            <template v-if="sentTo">已发出，10 分钟内有效{{ status.email ? '：原邮箱和新邮箱各收到一封' : '' }}。收不到就看看垃圾邮件。</template>
            <template v-else-if="status.email">会给原邮箱和新邮箱各发一封验证码，两个都要填。</template>
            <template v-else>会发一封验证码到这个邮箱。</template>
          </span>
        </div>

        <div class="grid grid-cols-1 gap-4 md:grid-cols-2">
          <div v-if="status.email" class="flex flex-col gap-1.5">
            <label for="login-email-old-code" class="text-[13px] text-ink font-medium">原邮箱验证码</label>
            <el-input
              id="login-email-old-code"
              :model-value="previousCode"
              inputmode="numeric"
              autocomplete="one-time-code"
              maxlength="6"
              placeholder="6 位数字"
              :disabled="verifying"
              @update:model-value="previousCode = digits($event)"
            />
            <span class="text-xs text-muted">发到 {{ maskEmail(status.email) }}</span>
          </div>
          <div class="flex flex-col gap-1.5">
            <label for="login-email-code" class="text-[13px] text-ink font-medium">{{ status.email ? '新邮箱验证码' : '验证码' }}</label>
            <el-input
              id="login-email-code"
              :model-value="code"
              inputmode="numeric"
              autocomplete="one-time-code"
              maxlength="6"
              placeholder="6 位数字"
              :disabled="verifying"
              @update:model-value="code = digits($event)"
            />
            <span class="text-xs text-muted">{{ sentTo ? `发到 ${maskEmail(sentTo)}` : status.email ? '发到上面填的新邮箱' : '发到上面填的邮箱' }}</span>
          </div>
        </div>

        <div class="flex flex-wrap items-center gap-2">
          <el-button type="primary" native-type="submit" :disabled="!canVerify" :loading="verifying">{{ status.email ? '验证并更换' : '验证并绑定' }}</el-button>
          <el-button v-if="status.email" :disabled="verifying" @click="cancelEdit">取消</el-button>
        </div>
        <p v-if="status.email" class="m-0 text-[12.5px] text-muted leading-5">
          换好后其他设备会退出登录。原邮箱已经收不到信了？请联系管理员核验身份后更换。
        </p>
      </form>
    </template>
  </SectionCard>
</template>

<style scoped>
.resend-button {
  flex-shrink: 0;
  width: 118px;
  height: 48px;
  border: 1px solid color-mix(in srgb, var(--brand-400) 45%, var(--border));
  border-radius: 12px;
  background: var(--brand-soft);
  color: var(--brand-600);
  font: inherit;
  font-size: 14.5px;
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
  font-weight: 400;
  opacity: 1;
}

.resend-button:focus-visible {
  outline: 2px solid var(--brand-500);
  outline-offset: 2px;
}

/* 设置页「发送验证码」：宽度固定，倒计时时不跳 */
.send-button {
  flex-shrink: 0;
  min-width: 104px;
}

@media (min-width: 1024px) {
  .resend-button {
    height: 46px;
    border-radius: 10px;
    font-size: 14px;
  }
}
</style>
