<script setup lang="ts">
import { computed, ref, watch } from 'vue'
import type { AuthEvent } from '@/api/types'
import ResponsiveList from '@/components/ui/ResponsiveList.vue'
import SectionCard from '@/components/ui/SectionCard.vue'
import type { ListColumn } from '@/components/ui/types'
import { AUTH_EVENT_LABELS, deviceName } from '@/utils/userAgent'

/**
 * 登录记录（认证记录）：登录、退出、失败、改密码、绑定第三方账号等，一页 10 条；电脑是表格，手机是卡片。
 * 每次打开页面都会记一条「刷新登录状态」，默认不列，免得把真正要看的挤下去。
 */
const props = defineProps<{ events: AuthEvent[]; loading?: boolean }>()

const PAGE_SIZE = 10
const includeRefresh = ref(false)
const page = ref(1)
const filtered = computed(() => (includeRefresh.value ? props.events : props.events.filter((e) => e.event !== 'TOKEN_REFRESH')))
const paged = computed(() => filtered.value.slice((page.value - 1) * PAGE_SIZE, page.value * PAGE_SIZE).map((e, i) => ({ ...e, key: `${e.createdAt}-${i}` })))
watch(includeRefresh, () => {
  page.value = 1
})
const columns: ListColumn[] = [
  { key: 'event', label: '事件', width: 150 },
  { key: 'createdAt', label: '时间', minWidth: 170 },
  { key: 'device', label: '设备', minWidth: 150 },
  { key: 'ip', label: '来源 IP', width: 130 },
  { key: 'detail', label: '说明', minWidth: 140 }
]
const formatTime = (value: string | null) => (value ? new Date(value).toLocaleString('zh-CN', { timeZone: 'Asia/Shanghai', hour12: false }) : '—')
const label = (event: string) => AUTH_EVENT_LABELS[event] ?? event
const danger = (event: string) => event === 'LOGIN_FAILED' || event === 'LOGIN_LOCKED'
</script>

<template>
  <SectionCard data-test="settings-events">
    <template #title>登录记录</template>
    <template #actions>
      <el-checkbox v-model="includeRefresh" size="small">包含自动刷新登录状态</el-checkbox>
    </template>
    <ResponsiveList :items="paged" :columns="columns" row-key="key" :loading="loading" empty-text="还没有登录记录">
      <template #cell-event="{ row }"><span :class="{ 'text-danger-ink': danger(row.event) }">{{ label(row.event) }}</span></template>
      <template #cell-createdAt="{ row }">{{ formatTime(row.createdAt) }}</template>
      <template #cell-device="{ row }"><span :title="row.userAgent || '未知设备'">{{ deviceName(row.userAgent) }}</span></template>
      <template #cell-ip="{ row }"><span class="mono text-xs">{{ row.ip ?? '—' }}</span></template>
      <template #cell-detail="{ row }"><span class="line-clamp-2" :title="row.detail ?? ''">{{ row.detail || '—' }}</span></template>
      <template #card="{ row }">
        <div class="flex flex-col gap-1">
          <span class="flex items-baseline justify-between gap-3">
            <span class="text-sm font-semibold" :class="danger(row.event) ? 'text-danger-ink' : 'text-ink'">{{ label(row.event) }}</span>
            <span class="shrink-0 text-xs text-muted">{{ formatTime(row.createdAt) }}</span>
          </span>
          <span class="text-xs text-muted">{{ deviceName(row.userAgent) }}<template v-if="row.ip"> · <span class="mono">{{ row.ip }}</span></template></span>
          <span v-if="row.detail" class="text-xs text-body">{{ row.detail }}</span>
        </div>
      </template>
    </ResponsiveList>
    <el-pagination
      v-if="filtered.length > PAGE_SIZE"
      v-model:current-page="page"
      class="mt-3 justify-end"
      small
      layout="prev, pager, next, total"
      :pager-count="5"
      :page-size="PAGE_SIZE"
      :total="filtered.length"
    />
  </SectionCard>
</template>
