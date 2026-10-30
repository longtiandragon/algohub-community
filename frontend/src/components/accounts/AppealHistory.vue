<script setup lang="ts">
import type { OwnershipAppeal } from '@/api/types'
import PlatformLogo from '@/components/PlatformLogo.vue'
import StatusBadge from '@/components/ui/StatusBadge.vue'
import { platformName } from '@/utils/platformBrand'
import { formatTime } from './format'

/** 处理过的归属申诉（审核中的在「需要你处理」里）：结果、时间、我的说明、审核意见 */
defineProps<{ appeals: OwnershipAppeal[] }>()

const STATUS: Record<string, { label: string; tone: 'success' | 'danger' | 'neutral' | 'warning' }> = {
  PENDING: { label: '待审核', tone: 'warning' },
  APPROVED: { label: '已通过', tone: 'success' },
  REJECTED: { label: '已驳回', tone: 'danger' },
  WITHDRAWN: { label: '已撤回', tone: 'neutral' }
}
</script>

<template>
  <div class="flex flex-col gap-3">
    <p class="m-0 text-[12.5px] text-muted leading-relaxed">
      申诉通过后只把「正式绑定」转给你并重新同步，原归属者的记录与登录态不会搬给你；驳回则保持现状。
    </p>
    <ul class="m-0 flex list-none flex-col gap-3 p-0" data-test="appeal-history">
      <li v-for="appeal in appeals" :key="appeal.id" class="flex items-start gap-3">
        <PlatformLogo :platform="appeal.platform" :size="24" class="mt-0.5 shrink-0" />
        <div class="min-w-0 flex flex-1 flex-col gap-1">
          <div class="flex flex-wrap items-center gap-2">
            <span class="text-sm text-ink font-semibold">{{ platformName(appeal.platform) }} · <span class="mono font-normal">{{ appeal.handle }}</span></span>
            <StatusBadge variant="label" :tone="STATUS[appeal.status]?.tone ?? 'neutral'">{{ STATUS[appeal.status]?.label ?? appeal.status }}</StatusBadge>
          </div>
          <span class="text-[12.5px] text-muted">
            {{ formatTime(appeal.createdAt) }} 提交<template v-if="appeal.reviewedAt"> · {{ formatTime(appeal.reviewedAt) }} 审核</template>
          </span>
          <p v-if="appeal.reason" class="m-0 text-[12.5px] text-muted leading-relaxed">我的说明：{{ appeal.reason }}</p>
          <p v-if="appeal.reviewNote" class="m-0 text-[13px] text-body leading-relaxed">审核意见：{{ appeal.reviewNote }}</p>
        </div>
      </li>
    </ul>
  </div>
</template>
