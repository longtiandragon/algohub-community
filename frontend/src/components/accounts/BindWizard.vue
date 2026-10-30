<script setup lang="ts">
import { computed, nextTick, onBeforeUnmount, ref, watch } from 'vue'
import { ElMessage } from 'element-plus'
import { ApiError, del, get, isEmailGateError, notifyError, post } from '@/api/client'
import type { BindingRequest, IdentityLookup, PlatformCapability } from '@/api/types'
import PlatformLogo from '@/components/PlatformLogo.vue'
import ResponsiveDialog from '@/components/ui/ResponsiveDialog.vue'
import { useBreakpoint } from '@/composables/useBreakpoint'
import { bindingGuides } from '@/utils/bindingGuides'
import { platformName } from '@/utils/platformBrand'
import WizardMatiji from './wizard/WizardMatiji.vue'
import WizardPlatforms from './wizard/WizardPlatforms.vue'
import WizardProof from './wizard/WizardProof.vue'
import WizardStepper from './wizard/WizardStepper.vue'
import type { MatijiCandidate } from './wizard/types'

/**
 * 添加账号向导：选平台 → 填账号 → 确认是你 → 验证归属 → 完成。电脑上是 640 宽的对话框，手机上占满整屏。
 * 三类特殊平台各走自己的分支：码蹄集第二步按昵称查找；只能个人关注的平台（杭电 OJ）查到账号后直接添加，没有验证归属；
 * QOJ 只填写用户名关联个人档案，后续由浏览器插件同步。
 * 页面上的入口：「添加账号」和右栏的平台用 open(平台)，「需要你处理」的继续验证、去验证用 resume(申请) 直接到验证那一步。
 */
const props = defineProps<{
  capabilities: PlatformCapability[]
  /** 待验证的绑定申请：核验失败后从这里读最新的已核验次数 */
  pendingBindings: BindingRequest[]
  /** 页面的刷新：申请、绑定之后调用；silent 时读不到不弹提示 */
  reload: (options?: { silent?: boolean }) => Promise<unknown>
}>()

type Step = 'platform' | 'handle' | 'confirm' | 'prove' | 'done'
type Outcome = 'BOUND' | 'APPEALED' | 'WATCHING'

const { isMobile } = useBreakpoint()
const visible = ref(false)
const step = ref<Step>('platform')
const platform = ref('')
const handle = ref('')
const credential = ref('')
const lookup = ref<IdentityLookup | null>(null)
const request = ref<BindingRequest | null>(null)
const outcome = ref<Outcome | null>(null)
const busy = ref(false)
const proofError = ref('')
const showNotes = ref(false)
const handleInput = ref<{ focus: () => void } | null>(null)
const matiji = ref<InstanceType<typeof WizardMatiji> | null>(null)

const capability = computed(() => props.capabilities.find((c) => c.platform === platform.value))
const guide = computed(() => bindingGuides[platform.value] ?? { label: '账号标识', short: '账号', example: '', help: '该平台暂未提供在线绑定说明。' })
const name = computed(() => capability.value?.displayName ?? platformName(platform.value))
/** verify 能验证归属；watch 只能个人关注、能查到账号；credential 要登录态、查不了公开资料。还没选平台时按最常见的 verify 显示五步 */
const flow = computed<'verify' | 'watch' | 'credential' | 'browser'>(() => {
  if (platform.value === 'QOJ') return 'browser'
  const c = capability.value
  if (!c || c.ownershipProof) return 'verify'
  return c.requiresCredential ? 'credential' : 'watch'
})

const steps = computed<{ key: Step; label: string }[]>(() => {
  if (flow.value === 'browser') return [{ key: 'platform', label: '选平台' }, { key: 'handle', label: '填用户名' }, { key: 'done', label: '连接插件' }]
  if (flow.value === 'credential') {
    return [{ key: 'platform', label: '选平台' }, { key: 'handle', label: '填账号和登录态' }, { key: 'done', label: '完成' }]
  }
  const list: { key: Step; label: string }[] = [
    { key: 'platform', label: '选平台' },
    { key: 'handle', label: guide.value.nickname ? '按昵称查找' : '填账号' },
    { key: 'confirm', label: '确认是你' }
  ]
  if (flow.value === 'verify') list.push({ key: 'prove', label: '验证归属' })
  list.push({ key: 'done', label: '完成' })
  return list
})
const stepIndex = computed(() => Math.max(0, steps.value.findIndex((s) => s.key === step.value)))

/** 中文和以字母开头的词之间空一格：「填 Codeforces 用户名」「填洛谷用户名」 */
const spaced = (text: string) => (/^[A-Za-z0-9]/.test(text) ? ` ${text}` : text)

const heading = computed(() => {
  switch (step.value) {
    case 'platform': return { title: '选择平台', text: '先选你已经在刷题的平台' }
    case 'handle':
      if (guide.value.nickname) return { title: '按昵称查找', text: guide.value.help }
      return { title: `填写${spaced(guide.value.short)}${flow.value === 'credential' ? '和登录态' : ''}`, text: guide.value.help }
    case 'confirm': return { title: '确认是你的账号', text: lookupHint.value }
    case 'prove': return { title: '验证账号归属', text: '' }
    default: return { title: '', text: '' }
  }
})

const lookupHint = computed(() => {
  const r = lookup.value
  if (!r) return ''
  switch (r.state) {
    case 'MINE_VERIFIED': return '这个账号已经是你的正式绑定，不需要再验证。'
    case 'MINE_PENDING': return '你已有一份未完成的验证申请，可以继续验证；重复获取不会延长有效期。'
    case 'OWNED_BY_OTHER': return '这个账号已被另一位同学验证。如果它确实属于你，可以获取验证串完成验证，系统会生成申诉交管理员审核；审核前不会改动任何人的记录。'
    case 'MINE_UNVERIFIED': return r.proofSupported
      ? '这个账号已在你的列表里（待认领）。完成验证后即成为正式绑定，原有记录保留。'
      : '这个账号已在你的个人关注里，不用再添加。'
    default: return r.proofSupported
      ? '确认是你本人的账号后获取验证串：把它写进公开资料并核验通过，才会正式绑定并开始同步。验证前不占用这个账号。'
      : '这个平台暂不支持验证归属，可以添加为个人关注：记录只在你的档案里显示，不进校内排行。'
  }
})

/** 确认、验证两步顶上的账号：确认时来自查询结果，从申请继续时来自申请 */
const account = computed(() => {
  if (step.value === 'confirm' && lookup.value) {
    const r = lookup.value
    return { platform: r.platform, handle: r.handle, uid: r.platformUid, displayName: r.displayName !== r.handle ? r.displayName : '' }
  }
  if (step.value === 'prove' && request.value) {
    const r = request.value
    return { platform: r.platform, handle: r.handle, uid: r.platformUid, displayName: '' }
  }
  return null
})

/** 验证串的有效期按这个时间算：打开期间每 15 秒走一次 */
const now = ref(Date.now())
let clock: number | undefined
watch(visible, (open) => {
  window.clearInterval(clock)
  if (open) {
    now.value = Date.now()
    clock = window.setInterval(() => (now.value = Date.now()), 15_000)
  }
})
onBeforeUnmount(() => window.clearInterval(clock))
const proofExpired = computed(() => !!request.value && (request.value.expired || Date.parse(request.value.expiresAt) <= now.value))

/** 每一步只有一个主按钮 */
const primary = computed<{ label: string; type?: 'primary' | 'warning'; disabled?: boolean } | null>(() => {
  switch (step.value) {
    case 'platform':
      if (!platform.value) return { label: '下一步', disabled: true }
      return { label: `下一步：填${spaced(guide.value.short)}` }
    case 'handle':
      if (guide.value.nickname) return { label: '按昵称查找' }
      return { label: flow.value === 'browser' ? '添加并连接插件' : flow.value === 'credential' ? '添加个人关注' : '查询账号' }
    case 'confirm': {
      const state = lookup.value?.state
      if (!lookup.value?.proofSupported) return { label: state === 'AVAILABLE' ? '添加个人关注' : '完成' }
      if (state === 'MINE_VERIFIED') return { label: '完成' }
      if (state === 'MINE_PENDING') return { label: '继续验证' }
      if (state === 'OWNED_BY_OTHER') return { label: '这是我的，获取验证串并申诉', type: 'warning' }
      return { label: '是我本人的，获取验证串' }
    }
    case 'prove': return { label: proofExpired.value ? '重新获取验证串' : '立即验证' }
    default: return { label: '完成' }
  }
})

const secondary = computed(() => {
  switch (step.value) {
    case 'platform': return '取消'
    case 'handle':
    case 'confirm': return '上一步'
    case 'prove': return '稍后再验证'
    default: return '再添加一个'
  }
})

function reset() {
  step.value = 'platform'
  platform.value = ''
  handle.value = ''
  credential.value = ''
  lookup.value = null
  request.value = null
  outcome.value = null
  busy.value = false
  proofError.value = ''
  showNotes.value = false
}

/** 光标放进这一步的输入框（换步、对话框打开以后） */
async function focusStep() {
  await nextTick()
  if (step.value !== 'handle') return
  if (guide.value.nickname) matiji.value?.focus()
  else handleInput.value?.focus()
}

function selectable(code: string) {
  return props.capabilities.some((c) => c.platform === code && c.syncSupported)
}

/** 从头打开；给了平台就选好、直接到填账号那一步 */
function open(code?: string) {
  reset()
  if (code && selectable(code)) {
    platform.value = code
    step.value = 'handle'
  }
  visible.value = true
}

/** 从一条待验证的申请继续：直接到验证那一步 */
function resume(pending: BindingRequest) {
  reset()
  platform.value = pending.platform
  request.value = pending
  step.value = 'prove'
  visible.value = true
}

function validInput() {
  if (!capability.value?.syncSupported) {
    ElMessage.warning('该平台暂未开放在线绑定')
    return false
  }
  const value = handle.value.trim()
  if (!value) {
    ElMessage.warning(`请填写${guide.value.label}`)
    return false
  }
  if (guide.value.numeric && !/^\d+$/.test(value)) {
    ElMessage.warning('此平台请填写纯数字 UID，不要填写昵称或完整链接')
    return false
  }
  if (flow.value === 'credential' && !credential.value.trim()) {
    ElMessage.warning('请填写本人的登录态')
    return false
  }
  return true
}

/** 只查平台公开资料，确认是不是自己的账号，不创建任何记录 */
async function lookupAccount(key: string) {
  busy.value = true
  lookup.value = null
  try {
    lookup.value = await get<IdentityLookup>('/binding-requests/lookup', { platform: platform.value, handle: key })
    step.value = 'confirm'
  } catch (e) {
    notifyError(e)
  } finally {
    busy.value = false
  }
}

function onPickMatiji(candidate: MatijiCandidate) {
  void lookupAccount(candidate.userId)
}

/** 个人关注：只在自己的档案里显示，不进校内排行 */
async function addWatching() {
  busy.value = true
  try {
    await post('/accounts', {
      platform: platform.value,
      handle: handle.value.trim(),
      credential: credential.value.trim() || undefined
    })
    outcome.value = 'WATCHING'
    step.value = 'done'
    await props.reload()
  } catch (e) {
    notifyError(e)
  } finally {
    busy.value = false
  }
}

/** 确认身份后签发验证串（已有未过期的申请时后端原样返回） */
async function startProof(target: { platform: string; handle: string; lookupKey?: string | null }) {
  busy.value = true
  try {
    request.value = await post<BindingRequest>('/binding-requests', { platform: target.platform, handle: target.lookupKey ?? target.handle })
    proofError.value = ''
    step.value = 'prove'
    await props.reload()
  } catch (e) {
    notifyError(e)
  } finally {
    busy.value = false
  }
}

async function verify() {
  if (!request.value) return
  busy.value = true
  proofError.value = ''
  try {
    const result = await post<{ outcome: 'BOUND' | 'APPEALED'; accountId: number | null; appealId: number | null }>(
      `/binding-requests/${request.value.id}/verify`)
    outcome.value = result.outcome
    step.value = 'done'
    await props.reload()
  } catch (e) {
    // 失败原因留在这一步里，不只闪一下提示：同学改完资料通常要来回核验几次
    if (isEmailGateError(e)) notifyError(e)
    else proofError.value = e instanceof ApiError ? e.message : '核验请求失败，请稍后重试'
    await props.reload({ silent: true })
    const current = props.pendingBindings.find((r) => r.id === request.value?.id)
    if (current && request.value) request.value = { ...request.value, attempts: current.attempts }
  } finally {
    busy.value = false
  }
}

/** 「不是这个账号」：确认时回去重填；验证时顺手取消这条申请 */
async function notMine() {
  if (step.value === 'prove' && request.value) {
    busy.value = true
    try {
      await del(`/binding-requests/${request.value.id}`)
      await props.reload()
    } catch (e) {
      notifyError(e)
      return
    } finally {
      busy.value = false
    }
    request.value = null
    proofError.value = ''
  }
  lookup.value = null
  step.value = 'handle'
  void focusStep()
}

async function onPrimary() {
  if (busy.value) return
  switch (step.value) {
    case 'platform':
      if (!platform.value) return
      step.value = 'handle'
      void focusStep()
      return
    case 'handle':
      if (guide.value.nickname) {
        await matiji.value?.search()
        return
      }
      if (!validInput()) return
      if (flow.value === 'credential' || flow.value === 'browser') await addWatching()
      else await lookupAccount(handle.value.trim())
      return
    case 'confirm': {
      const found = lookup.value
      if (!found) return
      if (!found.proofSupported) {
        if (found.state === 'AVAILABLE') await addWatching()
        else visible.value = false
        return
      }
      if (found.state === 'MINE_VERIFIED') visible.value = false
      else await startProof(found)
      return
    }
    case 'prove':
      if (proofExpired.value && request.value) await startProof(request.value)
      else await verify()
      return
    default:
      visible.value = false
  }
}

function onSecondary() {
  switch (step.value) {
    case 'handle':
      step.value = 'platform'
      return
    case 'confirm':
      lookup.value = null
      step.value = 'handle'
      void focusStep()
      return
    case 'done':
      reset()
      return
    default:
      // 取消、稍后再验证：申请留着，「需要你处理」里可以继续
      visible.value = false
  }
}

const DONE: Record<Outcome, { icon: string; tone: string; title: string; text: string }> = {
  BOUND: {
    icon: 'i-lucide-check',
    tone: 'bg-success-soft text-success-ink',
    title: '已正式绑定',
    text: '已开始首次同步，大约 1–3 分钟。现在可以删掉公开资料里的验证串。'
  },
  APPEALED: {
    icon: 'i-lucide-scale',
    tone: 'bg-info-soft text-info-ink',
    title: '已提交申诉',
    text: '验证成功，但这个账号已归另一位同学。系统已生成申诉，等管理员审核；审核前双方的记录都不会变动。可以在「需要你处理」里补充说明。'
  },
  WATCHING: {
    icon: 'i-lucide-check',
    tone: 'bg-success-soft text-success-ink',
    title: '已添加个人关注',
    text: '已开始首次同步，大约 1–3 分钟。个人关注的记录只在你自己的档案里显示，不进校内排行。'
  }
}
const done = computed(() => outcome.value === 'WATCHING' && flow.value === 'browser'
  ? { ...DONE.WATCHING, title: '已添加 QOJ 账号', text: '接下来连接浏览器插件，在已登录 QOJ 的浏览器里同步。无需复制 Cookie；未验证归属的记录仅用于个人统计，不参与公开排行与动态。' }
  : (outcome.value ? DONE[outcome.value] : null))
const doneHandle = computed(() => request.value?.handle ?? lookup.value?.handle ?? handle.value.trim())

defineExpose({ open, resume })
</script>

<template>
  <!-- 多步的流程：点遮罩不关，免得误点丢了填到一半的内容 -->
  <ResponsiveDialog v-model="visible" title="添加平台账号" :width="640" fullscreen-on-mobile :close-on-click-modal="false" @opened="focusStep">
    <template #header-extra>
      <span class="num text-[13.5px] text-muted">{{ stepIndex + 1 }} / {{ steps.length }}</span>
    </template>

    <div class="flex flex-col gap-4 md:gap-5" data-test="bind-wizard" :data-step="step">
      <div
        v-if="isMobile"
        class="-mx-5 h-[3px] bg-line-soft"
        role="progressbar"
        :aria-valuenow="stepIndex + 1"
        aria-valuemin="1"
        :aria-valuemax="steps.length"
        :aria-label="`第 ${stepIndex + 1} 步，共 ${steps.length} 步`"
      >
        <span class="block h-full bg-brand transition-[width] duration-300" :style="{ width: `${((stepIndex + 1) / steps.length) * 100}%` }" />
      </div>
      <WizardStepper v-else :steps="steps.map((s) => s.label)" :current="stepIndex" />

      <div v-if="heading.title" class="flex flex-col gap-1">
        <!-- 电脑上步骤条已写了这一步叫什么，标题只留给读屏软件；手机上没有步骤条，照常显示 -->
        <h3 class="m-0 text-[21px] text-ink font-bold leading-8 md:sr-only">{{ heading.title }}</h3>
        <p v-if="heading.text" class="m-0 text-sm text-muted leading-relaxed md:text-[13.5px]">{{ heading.text }}</p>
      </div>

      <!-- 选平台 -->
      <WizardPlatforms v-if="step === 'platform'" v-model="platform" :capabilities="capabilities" />

      <!-- 填账号 -->
      <template v-else-if="step === 'handle'">
        <WizardMatiji v-if="guide.nickname" ref="matiji" :busy="busy" @pick="onPickMatiji" />
        <div v-else class="flex flex-col gap-3.5">
          <label class="flex flex-col gap-1.5">
            <span class="text-sm text-ink font-semibold">{{ guide.label }}</span>
            <el-input
              ref="handleInput"
              v-model="handle"
              size="large"
              :placeholder="guide.example"
              :inputmode="guide.numeric ? 'numeric' : 'text'"
              @keyup.enter="onPrimary"
            />
          </label>
          <label v-if="flow === 'credential'" class="flex flex-col gap-1.5">
            <span class="text-sm text-ink font-semibold">登录态</span>
            <el-input v-model="credential" size="large" type="password" show-password autocomplete="off" placeholder="本人的 Cookie 字符串" @keyup.enter="onPrimary" />
            <span class="text-[12.5px] text-muted leading-relaxed">只用来同步，加密保存在平台服务器上，界面与日志都不回显；不能证明账号是你的。</span>
          </label>
        </div>
        <div v-if="capability" class="flex flex-col items-start gap-2">
          <el-button link type="primary" class="text-[13px]" @click="showNotes = !showNotes">
            {{ showNotes ? '收起平台说明' : `${name}能同步什么` }}
          </el-button>
          <div v-if="showNotes" class="w-full rounded-control bg-surface-2 px-3 py-2.5 text-[12.5px] text-body leading-relaxed">
            <p class="m-0 mb-1.5">{{ capability.notes }}</p>
            <div class="flex flex-wrap gap-1.5">
              <span class="chip" :class="{ off: !capability.submissionHistory }">逐条提交历史{{ capability.submissionHistory ? '' : '：不支持' }}</span>
              <span class="chip" :class="{ off: !capability.totalsSnapshot }">平台统计题数{{ capability.totalsSnapshot ? '' : '：不支持' }}</span>
              <span class="chip">{{ capability.requiresCredential ? '需要本人登录态' : '不需要登录态' }}</span>
            </div>
          </div>
        </div>
      </template>

      <!-- 确认是你、验证归属：顶上是查到的账号 -->
      <div v-if="account" class="flex items-center gap-3 rounded-xl bg-surface-2 px-3.5 py-3" data-test="wizard-account">
        <PlatformLogo :platform="account.platform" :size="36" />
        <span class="min-w-0 flex flex-1 flex-col gap-0.5">
          <span class="truncate text-[15px] text-ink font-semibold">{{ platformName(account.platform) }} · <span class="mono">{{ account.handle }}</span></span>
          <span class="truncate text-[12.5px] text-muted">
            <span class="num">UID {{ account.uid }}</span><template v-if="account.displayName"> · 平台显示名 {{ account.displayName }}</template>
          </span>
        </span>
        <button v-if="lookup?.state !== 'MINE_VERIFIED'" type="button" class="not-mine" :disabled="busy" @click="notMine">不是这个账号</button>
      </div>

      <WizardProof v-if="step === 'prove' && request" :request="request" :now="now" :home-url="capability?.homeUrl" :error="proofError" />

      <!-- 完成 -->
      <div v-if="step === 'done' && done" class="flex flex-col items-center gap-3 py-4 text-center md:py-6" data-test="wizard-done">
        <span class="size-14 flex items-center justify-center rounded-full" :class="done.tone" aria-hidden="true">
          <span :class="done.icon" class="text-[28px]" />
        </span>
        <h3 class="m-0 text-lg text-ink font-bold">{{ done.title }}</h3>
        <p class="m-0 text-sm text-body">{{ name }} · <span class="mono">{{ doneHandle }}</span></p>
        <p class="m-0 max-w-[460px] text-[13.5px] text-muted leading-relaxed">{{ done.text }}</p>
        <RouterLink v-if="flow === 'browser'" to="/qoj-sync" class="text-brand-600 font-semibold" @click="visible = false">连接 QOJ 插件 →</RouterLink>
      </div>
    </div>

    <template #footer>
      <el-button :disabled="busy && step !== 'prove'" @click="onSecondary">{{ secondary }}</el-button>
      <el-button
        v-if="primary"
        :type="primary.type ?? 'primary'"
        :loading="busy || (step === 'handle' && !!matiji?.searching)"
        :disabled="primary.disabled"
        data-test="wizard-primary"
        @click="onPrimary"
      >
        {{ primary.label }}
      </el-button>
    </template>
  </ResponsiveDialog>
</template>

<style scoped>
.not-mine {
  flex-shrink: 0;
  height: 32px;
  padding: 0 8px;
  border: 0;
  border-radius: 8px;
  background: transparent;
  color: var(--text-muted);
  font: inherit;
  font-size: 13px;
  cursor: pointer;
}

.not-mine:hover:not(:disabled) {
  color: var(--text-strong);
}

.chip {
  padding: 1px 8px;
  border: 1px solid var(--border);
  border-radius: 999px;
  background: var(--bg-elevated);
  color: var(--text);
}

.chip.off {
  color: var(--text-muted);
}
</style>
