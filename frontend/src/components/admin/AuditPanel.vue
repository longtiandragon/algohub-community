<script setup lang="ts">
import { computed, onBeforeUnmount, onMounted, ref } from 'vue'
import { notifyError } from '@/api/client'
import { auditActionLabel, fetchAdmins, fetchAudit, formatTime, type AuditPage, type AuditRow } from '@/api/adminUsers'
import ResponsiveList from '@/components/ui/ResponsiveList.vue'
import SectionCard from '@/components/ui/SectionCard.vue'
import StatusBadge from '@/components/ui/StatusBadge.vue'
import type { ListColumn } from '@/components/ui/types'

/**
 * 操作日志：管理员的每一次修改（查看不记），新的在上。按操作人、关键词（操作或对象）筛，一页 20 条。
 * 操作列把接口模板翻成中文（认不出的原样显示），下面一行小字留着原始接口，方便对照。
 * 操作人下拉优先用管理员名单（要「管理员与权限」权限）；没有这个权限时用已经看到的记录里的操作人。
 */
const actorId = ref<number>(0)
const q = ref('')
const page = ref(1)
const size = 20
const loading = ref(false)
const result = ref<AuditPage | null>(null)
const admins = ref<{ id: number; name: string }[]>([])
const seen = ref(new Map<number, string>())
let request = 0

async function load(reset = false) {
  if (reset) page.value = 1
  const current = ++request
  loading.value = true
  try {
    const data = await fetchAudit({ actorId: actorId.value || undefined, q: q.value.trim(), page: page.value, size })
    if (current !== request) return
    result.value = data
    page.value = data.page
    for (const row of data.rows) if (row.actorId != null) seen.value.set(row.actorId, row.actorName)
  } catch (e) {
    if (current === request) notifyError(e)
  } finally {
    if (current === request) loading.value = false
  }
}

let typing: ReturnType<typeof setTimeout> | undefined
function onSearch() {
  clearTimeout(typing)
  typing = setTimeout(() => load(true), 300)
}
onBeforeUnmount(() => clearTimeout(typing))

onMounted(() => {
  fetchAdmins()
    .then((list) => (admins.value = list.map((a) => ({ id: a.id, name: a.displayName }))))
    .catch(() => (admins.value = []))
  void load()
})

const actors = computed(() => {
  const map = new Map(seen.value)
  for (const a of admins.value) map.set(a.id, a.name)
  return [...map].map(([id, name]) => ({ id, name })).sort((a, b) => a.name.localeCompare(b.name, 'zh-CN'))
})
const filtered = computed(() => !!(actorId.value || q.value.trim()))
function reset() {
  actorId.value = 0
  q.value = ''
  void load(true)
}

const columns: ListColumn[] = [
  { key: 'createdAt', label: '时间', width: 170 },
  { key: 'actor', label: '操作人', width: 130 },
  { key: 'action', label: '操作', minWidth: 240 },
  { key: 'target', label: '对象', width: 140 },
  { key: 'status', label: '结果', width: 110 },
  { key: 'ip', label: 'IP', width: 140 }
]
const action = (row: AuditRow) => auditActionLabel(row.method, row.pattern)
const endpoint = (row: AuditRow) => (/^[A-Z]+ /.test(row.pattern) ? row.pattern : `${row.method} ${row.pattern}`)
const ok = (row: AuditRow) => row.status >= 200 && row.status < 400
const resultText = (row: AuditRow) => (ok(row) ? '成功' : row.status === 403 ? '没有权限' : row.status === 409 ? '被拦下' : `失败 ${row.status}`)
/** 路径变量写成 id=12、accountId=3；只有一个 id 时直接写 #12 */
const target = (row: AuditRow) => {
  if (!row.target) return '—'
  const single = /^(?:id|userId|accountId)=(\d+)$/.exec(row.target)
  return single ? `#${single[1]}` : row.target
}
</script>

<template>
  <SectionCard title="操作日志" description="管理员的每一次修改都记在这里（只是查看不会记），新的在上面。" data-test="admin-audit">
    <div class="mb-4 flex flex-wrap items-center gap-2">
      <el-select v-model="actorId" filterable class="!w-44" aria-label="操作人" @change="load(true)">
        <el-option :value="0" label="全部管理员" />
        <el-option v-for="a in actors" :key="a.id" :value="a.id" :label="a.name" />
      </el-select>
      <el-input v-model="q" clearable placeholder="搜操作或对象，如 schools、id=12" class="search" aria-label="搜操作" @input="onSearch" @keyup.enter="load(true)" @clear="load(true)">
        <template #prefix><span class="i-lucide-search" aria-hidden="true" /></template>
      </el-input>
      <el-button v-if="filtered" text @click="reset">清除筛选</el-button>
      <el-button class="!ml-auto" :loading="loading" @click="load()"><span v-if="!loading" class="i-lucide-refresh-cw mr-1.5" aria-hidden="true" />刷新</el-button>
    </div>

    <ResponsiveList :items="result?.rows ?? []" :columns="columns" row-key="id" :loading="loading && !result" :empty-text="filtered ? '没有符合条件的记录' : '还没有管理操作记录'">
      <template #cell-createdAt="{ row }"><span class="num text-[13px]">{{ formatTime(row.createdAt) }}</span></template>
      <template #cell-actor="{ row }"><span class="text-[13.5px] text-ink">{{ row.actorName }}</span></template>
      <template #cell-action="{ row }">
        <span class="flex flex-col">
          <span class="text-[13.5px] text-ink">{{ action(row) ?? endpoint(row) }}</span>
          <span v-if="action(row)" class="mono truncate text-[11.5px] text-muted" :title="endpoint(row)">{{ endpoint(row) }}</span>
        </span>
      </template>
      <template #cell-target="{ row }"><span class="mono text-[13px]" :title="row.target ?? ''">{{ target(row) }}</span></template>
      <template #cell-status="{ row }">
        <StatusBadge :tone="ok(row) ? 'success' : 'danger'" :title="`HTTP ${row.status}`">{{ resultText(row) }}</StatusBadge>
      </template>
      <template #cell-ip="{ row }"><span class="mono text-xs text-muted">{{ row.ip ?? '—' }}</span></template>
      <template #card="{ row }">
        <div class="flex flex-col gap-1">
          <span class="flex items-baseline justify-between gap-3">
            <span class="text-sm text-ink font-semibold">{{ action(row) ?? endpoint(row) }}</span>
            <StatusBadge :tone="ok(row) ? 'success' : 'danger'">{{ resultText(row) }}</StatusBadge>
          </span>
          <span class="text-xs text-muted">{{ row.actorName }} · {{ formatTime(row.createdAt) }}<template v-if="row.target"> · 对象 <span class="mono">{{ target(row) }}</span></template></span>
          <span class="mono truncate text-[11.5px] text-faint">{{ endpoint(row) }}<template v-if="row.ip"> · {{ row.ip }}</template></span>
        </div>
      </template>
    </ResponsiveList>

    <div v-if="(result?.total ?? 0) > size" class="mt-4 flex justify-end">
      <el-pagination v-model:current-page="page" :page-size="size" :total="result?.total ?? 0" :pager-count="5" layout="total, prev, pager, next" @current-change="load()" />
    </div>
  </SectionCard>
</template>

<style scoped>
.search {
  width: 280px;
}

@media (max-width: 767px) {
  .search {
    width: 100%;
  }
}
</style>
