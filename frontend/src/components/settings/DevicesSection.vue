<script setup lang="ts">
import { computed, ref } from 'vue'
import type { SessionInfo } from '@/api/types'
import ResponsiveList from '@/components/ui/ResponsiveList.vue'
import SectionCard from '@/components/ui/SectionCard.vue'
import StatusBadge from '@/components/ui/StatusBadge.vue'
import type { ListColumn } from '@/components/ui/types'
import { deviceName } from '@/utils/userAgent'

/**
 * 登录设备：当前设备排最前，默认只列最近 5 台；电脑是表格，手机是卡片（ResponsiveList）。
 * 「退出所有设备」连当前设备一起退出，由页面确认后执行。
 */
const props = defineProps<{ sessions: SessionInfo[]; loading?: boolean }>()
const emit = defineEmits<{ 'logout-all': [] }>()

const PREVIEW = 5
const showAll = ref(false)
const ordered = computed(() =>
  [...props.sessions].sort((a, b) => Number(b.current) - Number(a.current) || String(b.createdAt ?? '').localeCompare(String(a.createdAt ?? '')))
)
const visible = computed(() => (showAll.value ? ordered.value : ordered.value.slice(0, PREVIEW)))
const columns: ListColumn[] = [
  { key: 'device', label: '设备', minWidth: 220 },
  { key: 'createdAt', label: '登录时间', minWidth: 170 },
  { key: 'hash', label: '会话标识', width: 110 }
]
const formatTime = (value: string | null) => (value ? new Date(value).toLocaleString('zh-CN', { timeZone: 'Asia/Shanghai', hour12: false }) : '—')
</script>

<template>
  <SectionCard :description="`共 ${sessions.length} 台，当前设备排在最前`" data-test="settings-devices">
    <template #title>登录设备</template>
    <template #actions>
      <el-button size="small" type="danger" plain @click="emit('logout-all')">退出所有设备</el-button>
    </template>
    <ResponsiveList :items="visible" :columns="columns" row-key="hash" :loading="loading" empty-text="没有登录中的设备">
      <template #cell-device="{ row }">
        <span class="inline-flex flex-wrap items-center gap-2">
          <span :title="row.device || '未知设备'">{{ deviceName(row.device) }}</span>
          <StatusBadge v-if="row.current" variant="label" tone="success">当前设备</StatusBadge>
        </span>
      </template>
      <template #cell-createdAt="{ row }">{{ formatTime(row.createdAt) }}</template>
      <template #cell-hash="{ row }"><span class="mono text-xs">{{ row.hash }}</span></template>
      <template #card="{ row }">
        <div class="flex flex-col gap-1">
          <span class="flex flex-wrap items-center gap-2 text-sm text-ink">
            {{ deviceName(row.device) }}
            <StatusBadge v-if="row.current" variant="label" tone="success">当前设备</StatusBadge>
          </span>
          <span class="text-xs text-muted">{{ formatTime(row.createdAt) }} 登录 · <span class="mono">{{ row.hash }}</span></span>
        </div>
      </template>
    </ResponsiveList>
    <el-button v-if="sessions.length > PREVIEW" link type="primary" class="mt-2" @click="showAll = !showAll">
      {{ showAll ? '收起' : `显示全部 ${sessions.length} 台` }}
    </el-button>
    <p class="mb-0 mt-3 text-[12.5px] text-muted leading-relaxed">会话标识是刷新令牌摘要的前 8 位，用来区分设备，不能反推出令牌。有不认识的设备，可以「退出所有设备」后重新登录。</p>
  </SectionCard>
</template>
