<script setup lang="ts">
import { computed, ref, watch } from 'vue'
import type { PlatformAccount, SyncTask } from '@/api/types'
import PlatformLogo from '@/components/PlatformLogo.vue'
import ChipSelect from '@/components/ui/ChipSelect.vue'
import ResponsiveList from '@/components/ui/ResponsiveList.vue'
import SectionCard from '@/components/ui/SectionCard.vue'
import StatusBadge from '@/components/ui/StatusBadge.vue'
import type { ListColumn } from '@/components/ui/types'
import { platformName } from '@/utils/platformBrand'
import { errorKindLabel, syncStatusText, taskStatusLabel, taskTypeLabel } from '@/utils/statistics'

/**
 * 同步状态：限流提示、账号同步状态（按状态、平台筛选，按同学或账号搜索，一页 20 个）、最近任务（按状态筛）。
 * 电脑表格、手机卡片（ResponsiveList）。任务状态和账号状态由页面传进来（点顶部「失败」时两处都筛到失败）。
 */
type AdminAccount = PlatformAccount & { username?: string }
const props = defineProps<{
  accounts: AdminAccount[]
  tasks: SyncTask[]
  throttles: { platform: string; blockedUntil: string; reason: string | null }[]
  taskStatus: string
  accountStatus: string
  /** 各状态任务的总数（顶部的数）；列表只有最近的若干条 */
  totals: Record<string, number>
  loading?: boolean
}>()
const emit = defineEmits<{
  'update:taskStatus': [value: string]
  'update:accountStatus': [value: string]
  retry: [accountId: number]
  rebuild: [accountId: number]
  unbind: [accountId: number]
}>()

type Tone = 'success' | 'brand' | 'warning' | 'danger' | 'info' | 'neutral'
const ACCOUNT_TONE: Record<string, Tone> = { OK: 'success', FAILED: 'danger', RUNNING: 'brand', SYNCING: 'brand', QUEUED: 'info', PENDING: 'info', PARTIAL: 'warning', PAUSED: 'neutral', IDLE: 'neutral' }
const TASK_TONE: Record<string, Tone> = { SUCCESS: 'success', FAILED: 'danger', RUNNING: 'brand', PENDING: 'info', CANCELLED: 'neutral' }

const search = ref('')
const platform = ref('')
const page = ref(1)
const PAGE_SIZE = 20
const platformOptions = computed(() => [{ label: '全部平台', value: '' }, ...[...new Set(props.accounts.map((a) => a.platform))].map((p) => ({ label: platformName(p), value: p }))])
/** 同一种状态的两种写法并成一种（SYNCING 算正在同步，PENDING 算排队中） */
const norm = (status: string) => (status === 'SYNCING' ? 'RUNNING' : status === 'PENDING' ? 'QUEUED' : status)
const accountStatusOptions = computed(() => [
  { label: '全部状态', value: '' },
  ...['FAILED', 'RUNNING', 'QUEUED', 'OK', 'PARTIAL', 'PAUSED', 'IDLE']
    .map((s) => ({ s, n: props.accounts.filter((a) => norm(a.syncStatus) === s).length }))
    .filter(({ s, n }) => n > 0 || s === props.accountStatus)
    .map(({ s, n }) => ({ label: `${syncStatusText(s)}（${n}）`, value: s }))
])
const filteredAccounts = computed(() => {
  const q = search.value.trim().toLowerCase()
  return props.accounts.filter((a) =>
    (!props.accountStatus || norm(a.syncStatus) === props.accountStatus) &&
    (!platform.value || a.platform === platform.value) &&
    (!q || `${a.username ?? ''} ${a.handle}`.toLowerCase().includes(q)))
})
const pagedAccounts = computed(() => filteredAccounts.value.slice((page.value - 1) * PAGE_SIZE, page.value * PAGE_SIZE))
watch([search, platform, () => props.accountStatus], () => {
  page.value = 1
})

const TASK_FILTERS = [
  { value: '', label: '全部' },
  { value: 'PENDING', label: '等待执行' },
  { value: 'RUNNING', label: '正在同步' },
  { value: 'FAILED', label: '同步失败' },
  { value: 'SUCCESS', label: '同步成功' }
]
const taskCount = (status: string) => (status ? props.tasks.filter((t) => t.status === status).length : props.tasks.length)
const filteredTasks = computed(() => (props.taskStatus ? props.tasks.filter((t) => t.status === props.taskStatus) : props.tasks))
/** 任务也一页 20 条：最近 200 条全摊开，手机上要划很久 */
const taskPage = ref(1)
const pagedTasks = computed(() => filteredTasks.value.slice((taskPage.value - 1) * PAGE_SIZE, taskPage.value * PAGE_SIZE))
watch(() => props.taskStatus, () => {
  taskPage.value = 1
})
/** 列表里的条数比总数少时说一句（列表只取最近 200 条任务） */
const partialNote = computed(() => {
  const total = props.taskStatus ? props.totals[props.taskStatus] : undefined
  return total !== undefined && total > filteredTasks.value.length
    ? `这种状态的任务一共 ${total} 条，这里只列出最近的 ${filteredTasks.value.length} 条。`
    : ''
})

const accountColumns: ListColumn[] = [
  { key: 'user', label: '同学', minWidth: 110 },
  { key: 'account', label: '平台账号', minWidth: 190 },
  { key: 'status', label: '状态', width: 120 },
  { key: 'lastSuccessAt', label: '最近成功', width: 170 },
  { key: 'error', label: '原因', minWidth: 220 },
  { key: 'actions', label: '操作', width: 260 }
]
const taskColumns: ListColumn[] = [
  { key: 'type', label: '类型', width: 130 },
  { key: 'account', label: '平台账号', minWidth: 170 },
  { key: 'status', label: '状态', width: 120 },
  { key: 'duration', label: '耗时', width: 100, align: 'right' },
  { key: 'error', label: '原因', minWidth: 220 },
  { key: 'updatedAt', label: '更新时间', width: 170 }
]
const formatTime = (value: string | null | undefined) => (value ? new Date(value).toLocaleString('zh-CN', { timeZone: 'Asia/Shanghai', hour12: false }) : '—')
</script>

<template>
  <div class="flex flex-col gap-4" data-test="admin-sync">
    <div v-for="t in throttles" :key="t.platform" class="throttle" role="status">
      <span class="i-lucide-hourglass shrink-0 text-lg" aria-hidden="true" />
      <span>
        <strong>{{ platformName(t.platform) }}</strong> 刚被源站限流，同平台的同步任务冷却到 {{ formatTime(t.blockedUntil) }} 再执行；已有数据保留不变。
        <span v-if="t.reason" class="text-muted">（{{ t.reason }}）</span>
      </span>
    </div>

    <SectionCard title="账号同步状态" :description="`共 ${accounts.length} 个账号，筛出 ${filteredAccounts.length} 个`" data-test="admin-accounts">
      <div class="mb-3 flex flex-wrap items-center gap-2">
        <el-input v-model="search" clearable placeholder="搜同学的用户名或平台账号" class="!w-60" aria-label="搜同学或账号">
          <template #prefix><span class="i-lucide-search" aria-hidden="true" /></template>
        </el-input>
        <ChipSelect :model-value="accountStatus" :options="accountStatusOptions" label="状态" default-value="" @update:model-value="emit('update:accountStatus', $event)" />
        <ChipSelect v-model="platform" :options="platformOptions" label="平台" default-value="" />
      </div>
      <ResponsiveList :items="pagedAccounts" :columns="accountColumns" row-key="id" :loading="loading" empty-text="没有符合筛选的账号">
        <template #cell-user="{ row }">{{ row.username ?? '—' }}</template>
        <template #cell-account="{ row }">
          <span class="inline-flex min-w-0 items-center gap-2"><PlatformLogo :platform="row.platform" :size="18" :title="platformName(row.platform)" /><span class="mono truncate text-[13px]">{{ row.handle }}</span></span>
        </template>
        <template #cell-status="{ row }"><StatusBadge :tone="ACCOUNT_TONE[row.syncStatus] ?? 'neutral'">{{ syncStatusText(row.syncStatus) }}</StatusBadge></template>
        <template #cell-lastSuccessAt="{ row }">{{ formatTime(row.lastSuccessAt) }}</template>
        <template #cell-error="{ row }">
          <span v-if="row.lastErrorKind" class="line-clamp-2 text-[13px] text-body" :title="row.lastErrorMsg ?? ''">{{ errorKindLabel(row.lastErrorKind) }}：{{ row.lastErrorMsg }}</span>
          <span v-else class="text-muted">—</span>
        </template>
        <template #cell-actions="{ row }">
          <span class="inline-flex whitespace-nowrap">
            <el-button size="small" text type="primary" @click="emit('retry', row.id)">重试同步</el-button>
            <el-button size="small" text @click="emit('rebuild', row.id)">重算统计</el-button>
            <el-button size="small" text type="danger" @click="emit('unbind', row.id)">代解绑</el-button>
          </span>
        </template>
        <template #card="{ row }">
          <div class="flex flex-col gap-2">
            <div class="flex items-center gap-2">
              <PlatformLogo :platform="row.platform" :size="20" />
              <span class="min-w-0 flex-1 truncate text-sm text-ink">{{ row.username ?? '—' }} · <span class="mono">{{ row.handle }}</span></span>
              <StatusBadge :tone="ACCOUNT_TONE[row.syncStatus] ?? 'neutral'">{{ syncStatusText(row.syncStatus) }}</StatusBadge>
            </div>
            <span class="text-xs text-muted">最近成功 {{ formatTime(row.lastSuccessAt) }}</span>
            <span v-if="row.lastErrorKind" class="text-xs text-danger-ink">{{ errorKindLabel(row.lastErrorKind) }}：{{ row.lastErrorMsg }}</span>
            <div class="flex gap-2">
              <el-button size="small" @click="emit('retry', row.id)">重试同步</el-button>
              <el-button size="small" @click="emit('rebuild', row.id)">重算统计</el-button>
              <el-button size="small" type="danger" plain @click="emit('unbind', row.id)">代解绑</el-button>
            </div>
          </div>
        </template>
      </ResponsiveList>
      <el-pagination
        v-if="filteredAccounts.length > PAGE_SIZE"
        v-model:current-page="page"
        class="mt-3 justify-end"
        layout="total, prev, pager, next"
        :pager-count="5"
        :page-size="PAGE_SIZE"
        :total="filteredAccounts.length"
      />
    </SectionCard>

    <SectionCard title="最近任务" data-test="admin-tasks">
      <div role="radiogroup" aria-label="任务状态" class="mb-3 flex flex-wrap gap-2">
        <button
          v-for="f in TASK_FILTERS"
          :key="f.value"
          type="button"
          role="radio"
          class="filter-chip"
          :class="{ 'is-active': taskStatus === f.value }"
          :aria-checked="taskStatus === f.value"
          @click="emit('update:taskStatus', f.value)"
        >
          {{ f.label }}<span class="num">&nbsp;{{ taskCount(f.value) }}</span>
        </button>
      </div>
      <p v-if="partialNote" class="mb-3 mt-0 text-[12.5px] text-muted" data-test="tasks-partial">{{ partialNote }}</p>
      <ResponsiveList :items="pagedTasks" :columns="taskColumns" row-key="id" :loading="loading" empty-text="没有这种状态的任务">
        <template #cell-type="{ row }">{{ taskTypeLabel(row.taskType) }}</template>
        <template #cell-account="{ row }">
          <span v-if="row.platform" class="inline-flex min-w-0 items-center gap-2"><PlatformLogo :platform="row.platform" :size="18" /><span class="mono truncate text-[13px]">{{ row.handle ?? '—' }}</span></span>
          <span v-else class="text-muted">—</span>
        </template>
        <template #cell-status="{ row }"><StatusBadge :tone="TASK_TONE[row.status] ?? 'neutral'">{{ taskStatusLabel(row.status) }}</StatusBadge></template>
        <template #cell-duration="{ row }"><span class="num">{{ row.durationMs ? `${row.durationMs} ms` : '—' }}</span></template>
        <template #cell-error="{ row }">
          <span v-if="row.errorKind" class="line-clamp-2 text-[13px] text-body" :title="row.errorMsg ?? ''">{{ errorKindLabel(row.errorKind) }}：{{ row.errorMsg }}</span>
          <span v-else class="text-muted">—</span>
        </template>
        <template #cell-updatedAt="{ row }">{{ formatTime(row.updatedAt) }}</template>
        <template #card="{ row }">
          <div class="flex flex-col gap-1.5">
            <div class="flex items-center gap-2">
              <span class="flex-1 text-sm text-ink">{{ taskTypeLabel(row.taskType) }}<template v-if="row.handle"> · <span class="mono">{{ row.handle }}</span></template></span>
              <StatusBadge :tone="TASK_TONE[row.status] ?? 'neutral'">{{ taskStatusLabel(row.status) }}</StatusBadge>
            </div>
            <span class="text-xs text-muted">{{ formatTime(row.updatedAt) }}<template v-if="row.durationMs"> · {{ row.durationMs }} ms</template></span>
            <span v-if="row.errorKind" class="text-xs text-danger-ink">{{ errorKindLabel(row.errorKind) }}：{{ row.errorMsg }}</span>
          </div>
        </template>
      </ResponsiveList>
      <el-pagination
        v-if="filteredTasks.length > PAGE_SIZE"
        v-model:current-page="taskPage"
        class="mt-3 justify-end"
        layout="total, prev, pager, next"
        :pager-count="5"
        :page-size="PAGE_SIZE"
        :total="filteredTasks.length"
      />
    </SectionCard>
  </div>
</template>

<style scoped>
.throttle {
  display: flex;
  align-items: flex-start;
  gap: 10px;
  padding: 12px 14px;
  border: 1px solid var(--warning-line);
  border-radius: 12px;
  background: var(--warning-soft);
  color: var(--warning-ink);
  font-size: 13.5px;
  line-height: 1.6;
}

.throttle strong {
  color: var(--text-strong);
}
</style>
