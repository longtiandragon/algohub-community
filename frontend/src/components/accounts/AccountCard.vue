<script setup lang="ts">
import { computed } from 'vue'
import type { PlatformAccount } from '@/api/types'
import AccountDataFacts from '@/components/AccountDataFacts.vue'
import PlatformLogo from '@/components/PlatformLogo.vue'
import StatusBadge from '@/components/ui/StatusBadge.vue'
import { isSyncActive } from '@/stores/accounts'
import { platformName } from '@/utils/platformBrand'
import { TONE_DOT, TONE_INK, accountFacts, accountNote, accountProblem, accountStatus, bindingKind } from './accountState'

/**
 * 「我的账号」里的一张卡片：平台、账号（等宽字）、绑定方式；状态行；出错时一段原因；三格数字（手机上是一行）；
 * 补充说明；底部「同步」「查看数据」「⋯」（验证归属、同步明细、登录态、解绑）。接口在页面里调，这里只发出操作。
 */
const props = defineProps<{
  account: PlatformAccount
  /** 展开各数据项的同步明细 */
  detailOpen: boolean
}>()

const emit = defineEmits<{
  sync: []
  verify: []
  toggleDetail: []
  credential: []
  deleteCredential: []
  unbind: []
}>()

const status = computed(() => accountStatus(props.account))
const problem = computed(() => accountProblem(props.account))
const facts = computed(() => accountFacts(props.account))
const factsLine = computed(() => facts.value.map((fact) => `${fact.label} ${fact.value}`).join(' · '))
const note = computed(() => accountNote(props.account))
const kind = computed(() => bindingKind(props.account))
const identity = computed(() => {
  const { handle, platformUid } = props.account
  return platformUid && platformUid !== handle ? `${handle} · UID ${platformUid}` : handle
})
const active = computed(() => isSyncActive(props.account))
/** 经申诉转给别人的旧账号带着说明，不再给「验证归属」 */
const canVerify = computed(() => props.account.bindingKind === 'CLAIMABLE' && !props.account.ownershipNote)

function onCommand(command: string) {
  if (command === 'verify') emit('verify')
  else if (command === 'detail') emit('toggleDetail')
  else if (command === 'credential') emit('credential')
  else if (command === 'deleteCredential') emit('deleteCredential')
  else if (command === 'unbind') emit('unbind')
}
</script>

<template>
  <article :id="`account-${account.id}`" class="account-card" :class="`tone-${status.tone}`" data-test="account-card">
    <div class="flex items-center gap-3">
      <PlatformLogo :platform="account.platform" :size="40" class="shrink-0" />
      <div class="min-w-0 flex flex-1 flex-col gap-0.5">
        <span class="truncate text-base text-ink font-semibold leading-6">{{ platformName(account.platform) }}</span>
        <span class="truncate text-[12.5px] text-muted leading-[18px]" :title="identity">
          <span class="mono">{{ identity }}</span><template v-if="account.hasCredential"> · 已保存登录态</template>
        </span>
      </div>
      <StatusBadge variant="label" :tone="kind.tone">{{ kind.label }}</StatusBadge>
    </div>

    <span class="flex items-center gap-2 text-[13px] leading-5" :class="TONE_INK[status.tone]" data-test="account-status">
      <span class="size-2 shrink-0 rounded-full" :class="TONE_DOT[status.tone]" aria-hidden="true" />{{ status.text }}
    </span>

    <!-- 原因和卡片同一个色调：失败、归属不一致是红色，登录态失效、暂停这类要处理的是琥珀色 -->
    <p
      v-if="problem"
      class="m-0 rounded-control px-3 py-2.5 text-[12.5px] leading-relaxed"
      :class="status.tone === 'bad' ? 'bg-danger-soft text-danger-ink' : 'bg-warning-soft text-warning-ink'"
      data-test="account-problem"
    >
      {{ problem }}
    </p>
    <p v-if="account.ownershipNote" class="m-0 rounded-control bg-surface-2 px-3 py-2.5 text-[12.5px] text-body leading-relaxed">
      {{ account.ownershipNote }}
    </p>
    <!-- 洛谷：公开题单没有通过时间，要在浏览器里同步一次评测记录，洛谷的题才进榜单和动态 -->
    <div
      v-if="account.platform === 'LUOGU' && account.ownershipStatus === 'VERIFIED'"
      class="flex flex-col gap-1 rounded-control px-3 py-2.5 text-[12.5px] leading-relaxed"
      :class="account.historyComplete ? 'bg-surface-2 text-body' : 'bg-warning-tint text-warning-ink'"
      data-test="luogu-history"
    >
      <strong :class="account.historyComplete ? 'text-ink' : ''">{{ account.timedProblems ? '已收录带时间的通过记录' : '浏览器同步可补通过时间' }}</strong>
      <span>公开题单和额外评测题合并计入累计；有通过时间的题参与近期排行和动态。同一道题只计一次，不需要洛谷密码或 Cookie。</span>
      <span class="flex flex-wrap gap-x-3 gap-y-1">
        <RouterLink to="/luogu-sync" class="text-brand-600 font-semibold">{{ account.historyComplete ? '自动同步设置' : '去同步' }} →</RouterLink>
        <RouterLink :to="{ path: '/profile', query: { tab: 'records', platform: 'LUOGU' } }" class="text-brand-600">做题记录 →</RouterLink>
      </span>
    </div>

    <div v-if="account.platform === 'QOJ' && !account.ownershipNote && account.ownershipStatus !== 'MISMATCH'" class="rounded-control bg-info-soft px-3 py-2.5 text-[12.5px] leading-relaxed">
      在已登录 QOJ 的浏览器里同步 AC 记录，无需为浏览器同步复制 Cookie。
      <RouterLink to="/qoj-sync" class="text-brand-600 font-semibold">浏览器同步与自动同步设置 →</RouterLink>
    </div>
    <div v-if="account.platform === 'LEETCODE_CN' && account.ownershipStatus === 'VERIFIED'" class="rounded-control bg-info-soft px-3 py-2.5 text-[12.5px] leading-relaxed">
      通过插件补齐历史 AC 明细，热力图每题只计首次 AC；普通同步更新力扣过题总数。
      <RouterLink to="/leetcode-sync" class="text-brand-600 font-semibold">力扣浏览器同步 →</RouterLink>
    </div>
    <!-- 电脑、平板：已通过（和 Rating）两格；手机：一行 -->
    <dl class="m-0 hidden grid-cols-2 gap-2 border-y border-y-solid border-line-soft py-3 md:grid">
      <div v-for="fact in facts" :key="fact.label" class="min-w-0 flex flex-col gap-0.5">
        <dt class="text-xs text-muted leading-[18px]">{{ fact.label }}</dt>
        <dd class="num m-0 truncate leading-6" :class="fact.muted ? 'text-[15px] text-muted font-medium' : 'text-[17px] text-ink font-semibold'">{{ fact.value }}</dd>
      </div>
    </dl>
    <p class="m-0 text-[13px] text-body leading-5 md:hidden">{{ factsLine }}</p>

    <p v-if="note" class="m-0 text-[12.5px] text-muted leading-relaxed">{{ note }}</p>

    <div class="flex items-center gap-2 max-md:(border-t border-t-solid border-line-soft pt-2.5)">
      <RouterLink v-if="account.platform === 'QOJ'" to="/qoj-sync" class="text-brand-600 font-semibold">插件同步 →</RouterLink>
      <el-button v-else :disabled="active" @click="emit('sync')">
        <span class="i-lucide-refresh-cw mr-1.5 text-sm" aria-hidden="true" />{{ active ? '同步中' : account.platform === 'LUOGU' ? '更新公开题单' : '同步' }}
      </el-button>
      <RouterLink to="/profile" class="px-1.5 text-[13.5px] leading-8">查看数据</RouterLink>
      <span class="flex-1" />
      <el-dropdown trigger="click" @command="onCommand">
        <button type="button" class="more-button" :aria-label="`${platformName(account.platform)} 的更多操作`">
          <span class="i-lucide-ellipsis text-lg" aria-hidden="true" />
        </button>
        <template #dropdown>
          <el-dropdown-menu>
            <el-dropdown-item v-if="canVerify" command="verify">验证归属</el-dropdown-item>
            <el-dropdown-item command="detail">{{ detailOpen ? '收起同步明细' : '查看同步明细' }}</el-dropdown-item>
            <el-dropdown-item v-if="!['LUOGU', 'QOJ', 'LEETCODE_CN'].includes(account.platform)" command="credential">{{ account.hasCredential ? '更新登录态' : '设置登录态' }}</el-dropdown-item>
            <el-dropdown-item v-if="account.hasCredential" command="deleteCredential">删除登录态</el-dropdown-item>
            <el-dropdown-item command="unbind" divided class="danger-item">解绑</el-dropdown-item>
          </el-dropdown-menu>
        </template>
      </el-dropdown>
    </div>

    <div v-if="detailOpen" class="rounded-control bg-surface-2 p-3" data-test="account-detail">
      <AccountDataFacts items-only :account="account" />
    </div>
  </article>
</template>

<style scoped>
.account-card {
  display: flex;
  flex-direction: column;
  gap: 12px;
  min-width: 0;
  padding: 16px;
  border: 1px solid var(--border);
  border-radius: var(--radius);
  background: var(--bg-elevated);
  /* 从「需要你处理」点「查看账号」滚过来时，不贴着顶栏 */
  scroll-margin-top: 72px;
}

.account-card.tone-warn {
  border-color: var(--warning-line);
}

.account-card.tone-bad {
  border-color: color-mix(in srgb, var(--danger) 35%, var(--bg-elevated));
}

@media (min-width: 768px) {
  .account-card {
    padding: 18px;
  }
}

.more-button {
  display: flex;
  align-items: center;
  justify-content: center;
  width: 34px;
  height: 34px;
  padding: 0;
  border: 0;
  border-radius: 9px;
  background: transparent;
  color: var(--text-muted);
  cursor: pointer;
}

.more-button:hover {
  background: var(--surface-hover);
  color: var(--text-strong);
}

.more-button:focus-visible {
  outline: 2px solid var(--brand-500);
  outline-offset: 1px;
}

:global(.el-dropdown-menu__item.danger-item) {
  color: var(--danger-ink);
}
</style>
