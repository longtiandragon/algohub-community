<script setup lang="ts">
import { computed, onBeforeUnmount, onMounted, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ElMessage, ElMessageBox } from 'element-plus'
import { del, notifyError, post } from '@/api/client'
import type { BindingRequest, PlatformAccount } from '@/api/types'
import AccountCard from '@/components/accounts/AccountCard.vue'
import AccountTodos from '@/components/accounts/AccountTodos.vue'
import AccountsAside from '@/components/accounts/AccountsAside.vue'
import AppealHistory from '@/components/accounts/AppealHistory.vue'
import BindWizard from '@/components/accounts/BindWizard.vue'
import CredentialDialog from '@/components/accounts/CredentialDialog.vue'
import SyncTaskList from '@/components/accounts/SyncTaskList.vue'
import { buildTodos } from '@/components/accounts/todos'
import DisclosureRow from '@/components/ui/DisclosureRow.vue'
import EmptyState from '@/components/ui/EmptyState.vue'
import PageHeader from '@/components/ui/PageHeader.vue'
import Skeleton from '@/components/ui/Skeleton.vue'
import { useAccountsPage } from '@/composables/useAccountsPage'
import { useBreakpoint } from '@/composables/useBreakpoint'
import { useSyncAll } from '@/composables/useSyncAll'
import { isSyncActive, useAccounts } from '@/stores/accounts'
import { useAuthStore } from '@/stores/auth'

/**
 * 平台账号：我绑了哪些账号？哪里出了问题、怎么修？
 * 电脑两栏：左边「需要你处理」→「我的账号」两列卡片 → 默认收起的同步记录；右边绑定方式和支持的平台。
 * 窄屏单列，右栏排到最后；手机上同步和添加在顶栏，同步记录进二级页面。
 * 添加账号、继续验证都在添加向导（BindWizard）里：电脑上是对话框，手机上占满整屏。
 */
const auth = useAuthStore()
const store = useAccounts()
const { isMobile } = useBreakpoint()
const { accounts, capabilities, tasks, pendingBindings, myAppeals, loading, load } = useAccountsPage()
const { syncingAll, syncProgress, syncAllResult, onSyncAll } = useSyncAll({
  load,
  tasks,
  hasAccounts: () => accounts.value.length > 0
})

const wizard = ref<InstanceType<typeof BindWizard> | null>(null)
const credentialDialog = ref<InstanceType<typeof CredentialDialog> | null>(null)

/** 申请剩几分钟按这个时间算，半分钟走一次 */
const now = ref(Date.now())
const clock = window.setInterval(() => (now.value = Date.now()), 30_000)
onBeforeUnmount(() => window.clearInterval(clock))

const todos = computed(() => buildTodos(accounts.value, pendingBindings.value, myAppeals.value, now.value))
const resolvedAppeals = computed(() => myAppeals.value.filter((appeal) => appeal.status !== 'PENDING'))
const syncingCount = computed(() => accounts.value.filter(isSyncActive).length)
const tasksDescription = computed(() => (tasks.value.length ? `最近 ${tasks.value.length} 次同步任务的结果和耗时` : '每次同步任务的结果和耗时'))

/** 打开添加向导；给了平台就选好、直接到填账号那一步 */
function openAdd(platform?: string) {
  wizard.value?.open(platform)
}

/**
 * 带着 ?add= 进来直接打开添加向导：总览的常用平台带着平台（?add=CODEFORCES），「去绑定」不带（?add=）。
 * 打开后把参数去掉，关了向导再刷新页面不会又弹出来。
 */
const route = useRoute()
const router = useRouter()
async function applyAddQuery() {
  const value = route.query.add
  if (value === undefined) return
  openAdd(typeof value === 'string' && value ? value.toUpperCase() : undefined)
  const { add: _, ...rest } = route.query
  await router.replace({ query: rest })
}
watch(() => route.query.add, () => void applyAddQuery())

onMounted(async () => {
  await load()
  await applyAddQuery()
})

/** 从一条待验证的申请继续：向导直接到验证那一步 */
function openProof(request: BindingRequest) {
  wizard.value?.resume(request)
}

async function onSync(account: PlatformAccount) {
  try {
    await post(`/accounts/${account.id}/sync`)
    ElMessage.success('已提交同步任务，完成后这里会自动更新')
    await load()
  } catch (e) {
    notifyError(e)
  }
}

async function onUnbind(row: PlatformAccount) {
  try {
    await ElMessageBox.confirm(
      `解绑 ${row.platform} 的 ${row.handle}？平台上保存的该账号记录与凭据会一并删除。`,
      '确认解绑',
      { type: 'warning' }
    )
    await del(`/accounts/${row.id}`)
    ElMessage.success('已解绑')
    await load()
  } catch (e) {
    if (e !== 'cancel') notifyError(e)
  }
}

async function deleteCredential(row: PlatformAccount) {
  try {
    await del(`/accounts/${row.id}/credential`)
    ElMessage.success('已删除平台上保存的登录态；如需退出原平台会话请在原站操作')
    await load()
  } catch (e) {
    notifyError(e)
  }
}

/** 待认领的旧记录：同一套申请与核验，验证后账号 id、登录态与记录原样保留 */
async function openOwnership(row: PlatformAccount) {
  try {
    openProof(await post<BindingRequest>(`/accounts/${row.id}/ownership/challenge`))
    await load()
  } catch (e) { notifyError(e) }
}

/** 「需要你处理」里点「查看账号」：滚到那张卡片 */
function focusAccount(account: PlatformAccount) {
  document.getElementById(`account-${account.id}`)?.scrollIntoView({ block: 'start', behavior: 'smooth' })
}

/** 各账号展开的同步明细 */
const detailOpen = ref<number[]>([])
function toggleDetail(id: number) {
  detailOpen.value = detailOpen.value.includes(id) ? detailOpen.value.filter((x) => x !== id) : [...detailOpen.value, id]
}

/** 默认收起的几行 */
const showTasks = ref(false)
const showAppeals = ref(false)
</script>

<template>
  <div data-test="page">
    <PageHeader title="平台账号" :description="isMobile ? undefined : '绑定后自动同步你的通过记录；验证过归属的账号才会进入校内排行和动态。'">
      <template v-if="store.loaded" #meta>
        <span class="pill"><span class="num mr-1 font-semibold">{{ accounts.length }}</span>个账号</span>
        <span v-if="store.healthyCount" class="pill text-success-ink">
          <span class="size-[7px] rounded-full bg-success" aria-hidden="true" />{{ store.healthyCount }} 个正常
        </span>
        <span v-if="syncingCount" class="pill text-brand-600">
          <span class="size-[7px] rounded-full bg-brand-400" aria-hidden="true" />{{ syncingCount }} 个正在同步
        </span>
        <span v-if="todos.length" class="pill pill-warn">
          <span class="size-[7px] rounded-full bg-warning" aria-hidden="true" />{{ todos.length }} 件事需要处理
        </span>
      </template>
      <template #actions>
        <el-button size="large" :loading="syncingAll" :disabled="!accounts.length" @click="onSyncAll">
          <span v-if="!syncingAll" class="i-lucide-refresh-cw mr-2 text-base" aria-hidden="true" />{{ syncingAll ? '正在同步各平台' : '同步全部' }}
        </el-button>
        <el-button size="large" type="primary" @click="openAdd()">
          <span class="i-lucide-plus mr-1.5 text-base" aria-hidden="true" />添加账号
        </el-button>
      </template>
      <template #mobile-actions>
        <button
          type="button"
          class="topbar-icon"
          :disabled="syncingAll || !accounts.length"
          :aria-label="syncingAll ? '正在同步各平台' : '同步全部账号'"
          @click="onSyncAll"
        >
          <span class="i-lucide-refresh-cw text-xl" :class="{ 'animate-spin motion-reduce:animate-none': syncingAll }" aria-hidden="true" />
        </button>
        <button type="button" class="topbar-add" aria-label="添加账号" @click="openAdd()">
          <span class="i-lucide-plus text-lg" aria-hidden="true" />
        </button>
      </template>
    </PageHeader>

    <div class="grid grid-cols-1 items-start gap-4 md:gap-5 xl:grid-cols-[minmax(0,2fr)_minmax(0,1fr)]">
      <div class="min-w-0 flex flex-col gap-4 md:gap-5">
        <div v-if="syncAllResult" class="sync-note" data-test="sync-all-note">
          <strong class="text-ink">本轮已处理 {{ syncAllResult.total }} 个平台</strong>
          <span>新加入队列 {{ syncAllResult.queued }} 个 · 已在队列 {{ syncAllResult.alreadyQueued }} 个</span>
          <span role="status" aria-live="polite">{{ syncProgress }}</span>
          <span class="text-muted">不同 OJ 的接口速度不同，页面会在 90 秒内自动跟踪；没完成的任务继续在后台运行。</span>
        </div>

        <AccountTodos
          v-if="todos.length"
          :items="todos"
          :reload="load"
          @sync="onSync"
          @verify="openOwnership"
          @credential="(account) => credentialDialog?.open(account)"
          @proof="openProof"
          @focus="focusAccount"
        />

        <section class="flex flex-col gap-3" aria-labelledby="my-accounts-title">
          <div class="flex flex-wrap items-baseline gap-x-2 gap-y-1">
            <h2 id="my-accounts-title" class="m-0 text-base text-ink font-semibold leading-6 md:text-[17px]">我的账号</h2>
            <span v-if="accounts.length" class="hidden text-[13px] text-muted md:inline">点「同步」立刻更新一个账号；其余操作在 ⋯ 里</span>
          </div>

          <div v-if="!store.loaded && !store.error" class="grid grid-cols-1 gap-3.5 md:grid-cols-2" aria-busy="true">
            <div v-for="n in 2" :key="n" class="flex flex-col gap-3 border border-line rounded-card border-solid bg-surface p-4 md:p-[18px]">
              <div class="flex items-center gap-3"><Skeleton class="size-10" /><Skeleton class="h-4 w-1/2" /></div>
              <Skeleton class="h-3 w-3/5" />
              <Skeleton class="h-10 w-full" />
            </div>
          </div>
          <div v-else-if="!store.loaded" class="flex flex-col items-start gap-2 border border-line rounded-card border-solid bg-surface p-4">
            <p class="m-0 text-[13px] text-danger-ink">{{ store.error }}</p>
            <el-button size="small" @click="load()">重试</el-button>
          </div>
          <EmptyState
            v-else-if="!accounts.length"
            icon="i-lucide-link"
            title="还没有绑定平台账号"
            description="先绑你已经在刷题的平台，绑定后自动同步你的通过记录"
            action="添加第一个账号"
            @action="openAdd()"
          />
          <div v-else class="grid grid-cols-1 gap-3.5 md:grid-cols-2">
            <AccountCard
              v-for="account in accounts"
              :key="account.id"
              :account="account"
              :detail-open="detailOpen.includes(account.id)"
              @sync="onSync(account)"
              @verify="openOwnership(account)"
              @toggle-detail="toggleDetail(account.id)"
              @credential="credentialDialog?.open(account)"
              @delete-credential="deleteCredential(account)"
              @unbind="onUnbind(account)"
            />
          </div>
        </section>

        <section class="overflow-hidden border border-line rounded-card border-solid bg-surface">
          <DisclosureRow
            v-model:open="showTasks"
            title="同步记录"
            :description="tasksDescription"
            icon="i-lucide-list"
            :to="isMobile ? '/accounts/sync-log' : undefined"
          >
            <div class="flex flex-col gap-2">
              <div class="flex justify-end">
                <el-button link type="primary" :loading="loading" @click="load()">刷新</el-button>
              </div>
              <SyncTaskList :tasks="tasks" />
            </div>
          </DisclosureRow>
          <DisclosureRow
            v-if="resolvedAppeals.length"
            v-model:open="showAppeals"
            title="处理过的归属申诉"
            :description="`${resolvedAppeals.length} 条，含审核结果和意见`"
            icon="i-lucide-history"
          >
            <AppealHistory :appeals="resolvedAppeals" />
          </DisclosureRow>
        </section>
      </div>

      <AccountsAside :capabilities="capabilities" :admin="auth.isAdmin" @add="openAdd" />
    </div>

    <BindWizard ref="wizard" :capabilities="capabilities" :pending-bindings="pendingBindings" :reload="load" />
    <CredentialDialog ref="credentialDialog" :reload="load" />
  </div>
</template>

<style scoped>
/* 页头下的状态胶囊 */
.pill {
  display: inline-flex;
  align-items: center;
  gap: 6px;
  height: 28px;
  padding: 0 12px;
  border: 1px solid var(--border);
  border-radius: 999px;
  background: var(--bg-elevated);
  font-size: 13px;
  white-space: nowrap;
}

.pill-warn {
  border-color: var(--warning-line);
  background: var(--warning-soft);
  color: var(--warning-ink);
}

/* 一键同步全部之后的进度 */
.sync-note {
  display: grid;
  gap: 3px;
  padding: 12px 16px;
  border: 1px solid color-mix(in srgb, var(--brand-400) 42%, var(--border));
  border-radius: var(--radius);
  background: color-mix(in srgb, var(--brand-soft) 60%, var(--bg-elevated));
  font-size: 13px;
  line-height: 1.6;
}
</style>
