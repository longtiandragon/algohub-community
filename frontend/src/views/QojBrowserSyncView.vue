<script setup lang="ts">
import { computed, onBeforeUnmount, onMounted, ref, watch } from 'vue'
import { del, get, post, notifyError } from '@/api/client'
import type { PlatformAccount } from '@/api/types'
import EmptyState from '@/components/ui/EmptyState.vue'
import InfoTip from '@/components/ui/InfoTip.vue'
import PageHeader from '@/components/ui/PageHeader.vue'
import SectionCard from '@/components/ui/SectionCard.vue'

/**
 * QOJ浏览器导入：保留本人浏览器提供的评测记录，但不能独立核验源站响应。
 * 三步：装脚本 → 选账号、生成连接码（可顺带授权自动同步）→ 到QOJ页面点「AlgoHub 同步」。
 */
const accounts = ref<PlatformAccount[]>([])
const loaded = ref(false)
const selected = ref<number | null>(null)
const ticket = ref('')
const loading = ref(false)
const status = ref('')
const auto = ref(false)
const autoEnabled = ref(false)
const expiresAt = ref<string | null>(null)
const copied = ref(false)
let generation = 0
onBeforeUnmount(() => { generation++ })
const scriptUrl = '/userscripts/algohub-qoj-sync.user.js'
const eligibleAccounts = computed(() => accounts.value.filter((account) => account.platform === 'QOJ' && (account.ownershipStatus === 'VERIFIED' || (account.authMode === 'BROWSER' && !account.ownershipNote && ['UNVERIFIED', 'RESOLVED'].includes(account.ownershipStatus)))))
const current = computed(() => eligibleAccounts.value.find((account) => account.id === selected.value) ?? null)

onMounted(async () => {
  try {
    accounts.value = await get<PlatformAccount[]>('/accounts')
    selected.value = eligibleAccounts.value[0]?.id ?? null
  } catch (e) {
    notifyError(e)
  } finally {
    loaded.value = true
  }
})

watch(selected, async (id) => {
  const request = ++generation
  loading.value = false
  copied.value = false
  auto.value = false
  autoEnabled.value = false
  expiresAt.value = null
  ticket.value = ''
  status.value = ''
  if (!id) return
  try {
    const result = await get<{ enabled: boolean; expiresAt?: string }>(`/accounts/${id}/qoj-browser-import/auto-status`)
    if (request !== generation) return
    auto.value = result.enabled
    autoEnabled.value = result.enabled
    expiresAt.value = result.expiresAt ?? null
  } catch (e) {
    if (request === generation) notifyError(e)
  }
}, { flush: 'sync' })

async function begin() {
  if (!selected.value || loading.value) return
  const id = selected.value
  const request = ++generation
  const allowAuto = auto.value
  loading.value = true
  ticket.value = ''
  copied.value = false
  try {
    const result = await post<{ uid: string; ticket: string }>(`/accounts/${id}/qoj-browser-import/begin`, { auto: allowAuto })
    if (request !== generation) return
    ticket.value = result.ticket
    autoEnabled.value = allowAuto
    status.value = `连接码对应QOJ 账号 ${result.uid}，请在 60 分钟内完成首次同步。${allowAuto ? '完成后脚本会连上自动同步，请妥善保管连接码。' : ''}`
  } catch (e) {
    if (request === generation) notifyError(e)
  } finally {
    if (request === generation) loading.value = false
  }
}

async function revoke() {
  if (!selected.value || loading.value) return
  const id = selected.value
  const request = ++generation
  loading.value = true
  try {
    await del(`/accounts/${id}/qoj-browser-import/auto`)
    if (request !== generation) return
    ticket.value = ''
    copied.value = false
    autoEnabled.value = false
    auto.value = false
    expiresAt.value = null
    status.value = '已撤销自动同步授权，旧连接码已失效，进行中的同步已取消；已导入记录保留。'
  } catch (e) {
    if (request === generation) notifyError(e)
  } finally {
    if (request === generation) loading.value = false
  }
}

async function copy() {
  try {
    await navigator.clipboard.writeText(ticket.value)
    copied.value = true
  } catch {
    status.value = '浏览器没有允许复制，请手动选中连接码复制。'
  }
}

const beijing = (value: string) => new Date(value).toLocaleString('zh-CN', { timeZone: 'Asia/Shanghai', hour12: false })
</script>

<template>
  <div class="page mx-auto max-w-[760px] flex flex-col gap-4" data-test="page">
    <PageHeader title="QOJ浏览器同步" description="在已登录QOJ的浏览器里读取本人的 AC 记录，导入个人档案。" />

    <div class="flex gap-3 rounded-card bg-info-soft px-4 py-3.5 text-[13.5px] text-body leading-relaxed">
      <span class="i-lucide-info mt-0.5 shrink-0 text-lg text-info-ink" aria-hidden="true" />
      <p class="m-0">
        QOJ通过记录由你已登录的浏览器同步，服务器不会读取QOJ Cookie，也无法独立核验上传内容。同步到的 AC 题目计入通过题数，
        同一道题只计一次。未验证归属的账号仅用于个人统计，不参与公开排行与动态。
        同步完成不表示已独立核验完整历史，公开展示仍遵守档案隐私设置。
      </p>
    </div>

    <EmptyState
      v-if="loaded && !eligibleAccounts.length"
      icon="i-lucide-link"
      title="还没有添加 QOJ 账号"
      description="先在平台账号里填写 QOJ 用户名，再回来连接插件，无需 Cookie。"
      action="去平台账号"
      to="/accounts"
      class="bg-surface"
    />

    <template v-else>
      <SectionCard title="1. 安装同步脚本" description="先装浏览器扩展 Tampermonkey，再装 AlgoHub 的脚本；脚本只在QOJ页面运行。">
        <div class="flex flex-wrap items-center gap-3">
          <el-button tag="a" :href="scriptUrl" target="_blank" rel="noopener noreferrer">
            <span class="i-lucide-download mr-1.5" aria-hidden="true" />安装同步脚本
          </el-button>
          <span class="text-[12.5px] text-muted">已经装过的，更新到 v1.1.0（加速读取与上传续传）</span>
        </div>
      </SectionCard>

      <SectionCard title="2. 生成连接码" description="选择要同步的QOJ账号，生成一次性的连接码（60 分钟内有效）。">
        <div class="flex flex-col gap-4">
          <el-select v-if="eligibleAccounts.length > 1" v-model="selected" aria-label="QOJ账号" class="w-full md:!w-80">
            <el-option v-for="account in eligibleAccounts" :key="account.id" :label="`${account.handle} · 账号 ${account.platformUid}`" :value="account.id" />
          </el-select>
          <div v-else-if="current" class="flex items-center gap-2 text-sm text-ink">
            <span class="text-muted">账号</span><span class="mono">{{ current.handle }}</span><span class="text-muted">· 账号 {{ current.platformUid }}</span>
          </div>

          <div class="flex items-start gap-3 rounded-control border border-line border-solid px-3.5 py-3">
            <el-switch v-model="auto" :disabled="!selected || loading" aria-label="允许脚本自动同步" class="mt-0.5" />
            <div class="min-w-0 flex flex-col gap-1 text-[13px] leading-relaxed">
              <span class="flex items-center gap-1 text-ink font-semibold">
                首次同步后允许自动同步
                <InfoTip title="自动同步">脚本只在QOJ页面开着时，按你选的间隔（1 至 12 小时）读取本人 AC 记录；浏览器关着不会运行，也不是实时同步。间隔太短可能触发QOJ访问限制，建议 6 至 12 小时。授权 90 天后需要重新连接，随时可以撤销。</InfoTip>
              </span>
              <span v-if="autoEnabled" class="text-muted">
                已开启{{ expiresAt ? `，有效期到 ${beijing(expiresAt)}` : '' }}。
                <button type="button" class="link-button" :disabled="loading" @click="revoke">撤销授权</button>
              </span>
              <span v-else class="text-muted">不开也行：每次想更新时回到这里重新生成连接码。</span>
            </div>
          </div>

          <div class="flex flex-wrap items-center gap-2">
            <el-button type="primary" :disabled="!selected || loading" :loading="loading" @click="begin">生成连接码</el-button>
          </div>
          <div v-if="ticket" class="flex flex-col gap-2">
            <div class="flex gap-2">
              <el-input :model-value="ticket" readonly aria-label="QOJ浏览器同步连接码" class="ticket" @focus="($event.target as HTMLInputElement).select()" />
              <el-button @click="copy">
                <span :class="copied ? 'i-lucide-check' : 'i-lucide-copy'" class="mr-1.5" aria-hidden="true" />{{ copied ? '已复制' : '复制' }}
              </el-button>
            </div>
          </div>
          <p v-if="status" role="status" class="m-0 text-[12.5px] text-muted leading-relaxed">{{ status }}</p>
        </div>
      </SectionCard>

      <SectionCard title="3. 到QOJ页面同步" description="登录对应的QOJ账号，将 QOJ 界面语言切换为 English，打开任意QOJ页面，点右下角「AlgoHub 同步」，粘贴连接码，同步完成前别关页面。">
        <ul class="m-0 flex list-none flex-col gap-2 p-0 text-[12.5px] text-muted leading-relaxed">
          <li class="flex gap-2"><span class="i-lucide-clock mt-0.5 shrink-0" aria-hidden="true" />首次会读取浏览器能查到的历史记录，题多时要几分钟，不用反复点；不能据此确认历史完整。</li>
          <li class="flex gap-2"><span class="i-lucide-search mt-0.5 shrink-0" aria-hidden="true" />源站已显示 AC 但这里还未更新时，可以稍后重新同步。已连接自动同步可点「立即重新检查」，不用等下一次定时；连续请求仍受五分钟限频保护。</li>
          <li class="flex gap-2"><span class="i-lucide-shield-check mt-0.5 shrink-0" aria-hidden="true" />只上传 AC 提交编号、账号、题号、标题、语言和通过时间，不上传QOJ密码、Cookie、页面内容或没通过的提交；自动授权只能同步当前选择的账号。</li>
          <li class="flex gap-2"><span class="i-lucide-refresh-cw mt-0.5 shrink-0" aria-hidden="true" />读取完成后若上传中断，可在插件设置里点「续传上次上传」，20 分钟内继续传送已核对的数据，无需重新读取；连接码过期后需重新同步。</li>
          <li class="flex gap-2"><span class="i-lucide-triangle-alert mt-0.5 shrink-0" aria-hidden="true" />QOJ改版时脚本可能暂时失效，修好后更新脚本即可。</li>
        </ul>
      </SectionCard>
    </template>
  </div>
</template>

<style scoped>
.link-button {
  padding: 0;
  border: 0;
  background: none;
  color: var(--brand-600);
  font: inherit;
  cursor: pointer;
}

.link-button:hover {
  text-decoration: underline;
}

.ticket :deep(input) {
  font-family: var(--font-mono);
  font-size: 13px;
}
</style>
