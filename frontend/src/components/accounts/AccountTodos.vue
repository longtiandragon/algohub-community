<script setup lang="ts">
import { ElMessage, ElMessageBox } from 'element-plus'
import { del, notifyError, post } from '@/api/client'
import type { BindingRequest, OwnershipAppeal, PlatformAccount } from '@/api/types'
import PlatformLogo from '@/components/PlatformLogo.vue'
import type { Todo } from './todos'

/**
 * 「需要你处理」：每件事一行，右侧一个按钮；申请和申诉另有「取消申请」「撤回」（手机上放在说明下面）。
 * 同步、验证归属、登录态、继续验证要用页面上的对话框和接口，发出去由页面做；重新申请、取消、补充说明、撤回在这里调接口。
 */
const props = defineProps<{
  items: Todo[]
  /** 页面的刷新：申请、申诉改了之后调用 */
  reload: () => Promise<unknown>
}>()

const emit = defineEmits<{
  sync: [account: PlatformAccount]
  verify: [account: PlatformAccount]
  credential: [account: PlatformAccount]
  /** 打开归属验证对话框 */
  proof: [request: BindingRequest]
  /** 滚到这个账号的卡片 */
  focus: [account: PlatformAccount]
}>()

const ACTION: Record<Todo['kind'], string> = {
  mismatch: '查看账号',
  credential: '更新登录态',
  failed: '立即重试',
  paused: '立即重试',
  verify: '去验证',
  request: '继续验证',
  appeal: '补充说明'
}

function actionLabel(item: Todo) {
  return item.kind === 'request' && item.request?.expired ? '重新申请' : ACTION[item.kind]
}

function secondaryLabel(item: Todo) {
  if (item.kind === 'request') return '取消申请'
  if (item.kind === 'appeal') return '撤回'
  return null
}

function run(item: Todo) {
  const { account, request, appeal } = item
  if (account && (item.kind === 'failed' || item.kind === 'paused')) emit('sync', account)
  else if (account && item.kind === 'verify') emit('verify', account)
  else if (account && item.kind === 'credential') emit('credential', account)
  else if (account && item.kind === 'mismatch') emit('focus', account)
  else if (request) void (request.expired ? renewBinding(request) : emit('proof', request))
  else if (appeal) void explainAppeal(appeal)
}

function runSecondary(item: Todo) {
  if (item.request) void cancelBinding(item.request.id)
  else if (item.appeal) void withdrawAppeal(item.appeal)
}

async function renewBinding(row: BindingRequest) {
  try {
    emit('proof', await post<BindingRequest>('/binding-requests', { platform: row.platform, handle: row.lookupKey ?? row.handle }))
    await props.reload()
  } catch (e) { notifyError(e) }
}
async function cancelBinding(id: number) {
  try { await del(`/binding-requests/${id}`); await props.reload(); ElMessage.success('申请已取消') }
  catch (e) { notifyError(e) }
}
async function explainAppeal(row: OwnershipAppeal) {
  try {
    const { value } = await ElMessageBox.prompt('补充能帮助管理员判断的信息，例如注册时间、比赛记录等（最多 500 字）。',
      '补充申诉说明', { inputValue: row.reason ?? '', inputType: 'textarea', confirmButtonText: '保存' })
    await post(`/ownership-appeals/${row.id}/reason`, { reason: value })
    ElMessage.success('说明已保存')
    await props.reload()
  } catch (e) {
    if (e !== 'cancel' && e !== 'close') notifyError(e)
  }
}
async function withdrawAppeal(row: OwnershipAppeal) {
  try {
    await ElMessageBox.confirm('撤回后这条申诉不再审核，归属保持现状。确定撤回？', '撤回申诉', { type: 'warning' })
    await post(`/ownership-appeals/${row.id}/withdraw`)
    ElMessage.success('已撤回')
    await props.reload()
  } catch (e) {
    if (e !== 'cancel' && e !== 'close') notifyError(e)
  }
}
</script>

<template>
  <section class="overflow-hidden border border-warning-line rounded-card border-solid bg-surface" data-test="account-todos">
    <header class="flex items-center gap-2.5 border-b border-b-solid border-warning-line bg-warning-soft px-4 py-3 md:(px-5 py-3.5)">
      <span class="i-lucide-triangle-alert text-lg text-warning-ink" aria-hidden="true" />
      <h2 class="m-0 text-[15px] text-ink font-semibold leading-6">需要你处理</h2>
      <span class="num text-[13px] text-warning-ink">{{ items.length }}</span>
    </header>
    <ul class="m-0 list-none p-0">
      <li v-for="item in items" :key="item.key" class="todo-row" data-test="todo">
        <PlatformLogo :platform="item.platform" :size="32" class="shrink-0" />
        <div class="min-w-0 flex flex-1 flex-col gap-0.5">
          <span class="text-sm text-ink font-semibold leading-snug md:text-[14.5px]">{{ item.title }}</span>
          <span class="text-[12.5px] text-muted leading-normal md:text-[13px]">{{ item.sub }}</span>
          <button v-if="secondaryLabel(item)" type="button" class="todo-secondary mobile-only" @click="runSecondary(item)">
            {{ secondaryLabel(item) }}
          </button>
        </div>
        <button v-if="secondaryLabel(item)" type="button" class="todo-secondary desktop-only" @click="runSecondary(item)">
          {{ secondaryLabel(item) }}
        </button>
        <el-button :type="item.kind === 'verify' ? 'primary' : undefined" class="shrink-0" @click="run(item)">{{ actionLabel(item) }}</el-button>
      </li>
    </ul>
  </section>
</template>

<style scoped>
.todo-row {
  display: flex;
  align-items: center;
  gap: 12px;
  padding: 14px 16px;
  border-top: 1px solid var(--border-soft);
}

.todo-row:first-child {
  border-top: 0;
}

.todo-secondary {
  flex-shrink: 0;
  padding: 0 6px;
  border: 0;
  background: transparent;
  color: var(--text-muted);
  font: inherit;
  font-size: 13.5px;
  line-height: 32px;
  cursor: pointer;
}

.todo-secondary:hover {
  color: var(--danger-ink);
}

.todo-secondary.mobile-only {
  align-self: flex-start;
  padding: 0;
  line-height: 24px;
  font-size: 13px;
}

.desktop-only {
  display: none;
}

@media (min-width: 768px) {
  .todo-row {
    gap: 14px;
    padding: 16px 20px;
  }

  .desktop-only {
    display: inline-block;
  }

  .mobile-only {
    display: none;
  }
}
</style>
