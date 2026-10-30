<script setup lang="ts">
import type { SyncTask } from '@/api/types'
import PlatformLogo from '@/components/PlatformLogo.vue'
import ResponsiveList from '@/components/ui/ResponsiveList.vue'
import StatusBadge from '@/components/ui/StatusBadge.vue'
import type { ListColumn } from '@/components/ui/types'
import { platformName } from '@/utils/platformBrand'
import { beijingTime, relativeTime, taskStatusLabel, taskTypeLabel } from '@/utils/statistics'

/** 同步记录：电脑上是表格，手机上是卡片列表。每条写时间、平台与账号、结果、耗时和失败原因 */
defineProps<{
  tasks: SyncTask[]
  loading?: boolean
}>()

const COLUMNS: ListColumn[] = [
  { key: 'updatedAt', label: '时间', width: 96 },
  { key: 'platform', label: '平台 · 账号', minWidth: 160 },
  { key: 'status', label: '结果', width: 104 },
  { key: 'durationMs', label: '耗时', width: 80, align: 'right' },
  { key: 'errorMsg', label: '说明', minWidth: 140 }
]

const TONE: Record<string, 'success' | 'danger' | 'brand' | 'neutral'> = {
  SUCCESS: 'success',
  FAILED: 'danger',
  RUNNING: 'brand'
}

function source(task: SyncTask) {
  return task.platform ? platformName(task.platform) : taskTypeLabel(task.taskType)
}

function duration(ms: number | null) {
  if (!ms) return '—'
  return ms >= 1000 ? `${(ms / 1000).toFixed(1)} 秒` : `${ms} 毫秒`
}

/** 失败原因；重试过的注明第几次 */
function explain(task: SyncTask) {
  const reason = task.errorKind ? task.errorMsg || '更新未完成，请重试' : ''
  const attempt = task.attempt > 1 ? `第 ${task.attempt}/${task.maxAttempt} 次尝试` : ''
  return [reason, attempt].filter(Boolean).join('；') || '—'
}
</script>

<template>
  <ResponsiveList :items="tasks" :columns="COLUMNS" row-key="id" :loading="loading" empty-text="还没有同步记录" data-test="sync-tasks">
    <template #cell-updatedAt="{ row }">
      <span :title="beijingTime(row.updatedAt)">{{ relativeTime(row.updatedAt) }}</span>
    </template>
    <template #cell-platform="{ row }">
      <span class="min-w-0 flex items-center gap-2">
        <PlatformLogo v-if="row.platform" :platform="row.platform" :size="18" />
        <span class="truncate">{{ source(row) }}<template v-if="row.handle"> · <span class="mono">{{ row.handle }}</span></template></span>
      </span>
    </template>
    <template #cell-status="{ row }">
      <StatusBadge :tone="TONE[row.status] ?? 'neutral'">{{ taskStatusLabel(row.status) }}</StatusBadge>
    </template>
    <template #cell-durationMs="{ row }">
      <span class="num">{{ duration(row.durationMs) }}</span>
    </template>
    <template #cell-errorMsg="{ row }">
      <span :class="row.errorKind ? 'text-danger-ink' : 'text-muted'">{{ explain(row) }}</span>
    </template>
    <template #card="{ row }">
      <div class="flex flex-col gap-1.5">
        <div class="flex items-center gap-2">
          <PlatformLogo v-if="row.platform" :platform="row.platform" :size="20" />
          <span class="min-w-0 flex-1 truncate text-sm text-ink font-semibold">
            {{ source(row) }}<template v-if="row.handle"> · <span class="mono font-normal">{{ row.handle }}</span></template>
          </span>
          <StatusBadge :tone="TONE[row.status] ?? 'neutral'">{{ taskStatusLabel(row.status) }}</StatusBadge>
        </div>
        <span class="text-[12.5px] text-muted">
          {{ relativeTime(row.updatedAt) }} · 耗时 {{ duration(row.durationMs) }}<template v-if="row.attempt > 1"> · 第 {{ row.attempt }}/{{ row.maxAttempt }} 次尝试</template>
        </span>
        <p v-if="row.errorKind" class="m-0 text-[12.5px] text-danger-ink leading-relaxed">{{ row.errorMsg || '更新未完成，请重试' }}</p>
      </div>
    </template>
  </ResponsiveList>
</template>
