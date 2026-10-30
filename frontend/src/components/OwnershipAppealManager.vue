<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { get, notifyError, post } from '@/api/client'
import { platformName } from '@/utils/platformBrand'

interface AppealRow {
  id: number
  platform: string
  platformUid: string
  handle: string
  status: 'PENDING' | 'APPROVED' | 'REJECTED' | 'WITHDRAWN'
  reason: string | null
  provedAt: string
  createdAt: string
  reviewedAt: string | null
  reviewNote: string | null
  appellantId: number
  appellantUsername: string
  appellantName: string
  recordedOwnerId: number | null
  recordedOwnerUsername: string | null
  recordedOwnerName: string | null
  currentOwnerId: number | null
  currentOwnerUsername: string | null
  currentOwnerName: string | null
  currentOwnerVerifiedAt: string | null
  reviewerUsername: string | null
}

const rows = ref<AppealRow[]>([])
const loading = ref(false)
const status = ref('PENDING')
const busy = ref(0)
const pendingCount = computed(() => rows.value.filter(r => r.status === 'PENDING').length)
const STATUS: Record<string, { label: string; type: 'success' | 'warning' | 'info' | 'danger' }> = {
  PENDING: { label: '待审核', type: 'warning' },
  APPROVED: { label: '已通过', type: 'success' },
  REJECTED: { label: '已驳回', type: 'danger' },
  WITHDRAWN: { label: '申请人撤回', type: 'info' }
}

async function load() {
  loading.value = true
  try { rows.value = await get<AppealRow[]>('/admin/ownership-appeals', { status: status.value || undefined }) }
  catch (e) { notifyError(e) }
  finally { loading.value = false }
}

async function review(row: AppealRow, approve: boolean) {
  if (busy.value) return
  try {
    const { value } = await ElMessageBox.prompt(
      approve
        ? `通过后：${row.currentOwnerName ?? '现归属者'} 的该账号转为个人关注（记录与登录态保留），归属交给 ${row.appellantName} 并重新同步。可填写审核意见。`
        : '驳回后归属保持现状。请填写驳回原因，申请人能看到。',
      approve ? '通过申诉' : '驳回申诉',
      { inputType: 'textarea', confirmButtonText: approve ? '确认通过' : '确认驳回', inputValue: '' })
    busy.value = row.id
    await post(`/admin/ownership-appeals/${row.id}/${approve ? 'approve' : 'reject'}`, { note: value })
    ElMessage.success(approve ? '已通过，归属已转移并排队同步' : '已驳回')
    await load()
  } catch (e) {
    if (e !== 'cancel' && e !== 'close') notifyError(e)
  } finally {
    busy.value = 0
  }
}

function formatTime(value: string | null) {
  return value ? new Date(value).toLocaleString('zh-CN', { timeZone: 'Asia/Shanghai' }) : '—'
}

onMounted(load)
</script>

<template>
  <section v-loading="loading" class="card">
    <div class="card-head">
      <span class="card-title">归属申诉</span>
      <div class="head-tools">
        <el-select v-model="status" style="width: 140px" @change="load">
          <el-option label="待审核" value="PENDING" />
          <el-option label="已通过" value="APPROVED" />
          <el-option label="已驳回" value="REJECTED" />
          <el-option label="申请人撤回" value="WITHDRAWN" />
          <el-option label="全部" value="" />
        </el-select>
        <el-button @click="load">刷新</el-button>
      </div>
    </div>
    <el-alert type="info" :closable="false" show-icon style="margin-bottom: 12px">
      只有在公开资料里完成验证的申请人才会产生申诉，说明他在「证明时间」能修改该账号资料。
      系统不按先来后到自动裁决；通过只转移「正式绑定」标记，双方的刷题记录与登录态都不会搬动。
      <template v-if="status === 'PENDING'">当前待审核 {{ pendingCount }} 条。</template>
    </el-alert>
    <div class="table-scroll">
      <el-table :data="rows" size="small" empty-text="暂无申诉">
        <el-table-column label="平台账号" min-width="170">
          <template #default="{ row }">
            <div>{{ platformName(row.platform) }} · {{ row.handle }}</div>
            <div class="muted">UID {{ row.platformUid }}</div>
          </template>
        </el-table-column>
        <el-table-column label="申请人" min-width="150">
          <template #default="{ row }">
            <div>{{ row.appellantName }}</div>
            <div class="muted">{{ row.appellantUsername }} · 证明于 {{ formatTime(row.provedAt) }}</div>
          </template>
        </el-table-column>
        <el-table-column label="现归属者" min-width="170">
          <template #default="{ row }">
            <template v-if="row.currentOwnerId">
              <div>{{ row.currentOwnerName }}</div>
              <div class="muted">{{ row.currentOwnerUsername }} · 验证于 {{ formatTime(row.currentOwnerVerifiedAt) }}</div>
            </template>
            <span v-else class="muted">当前无人持有归属</span>
            <div v-if="row.recordedOwnerId && row.recordedOwnerId !== row.currentOwnerId" class="muted">
              申诉时为 {{ row.recordedOwnerName }}（{{ row.recordedOwnerUsername }}）
            </div>
          </template>
        </el-table-column>
        <el-table-column label="申请人说明" min-width="180">
          <template #default="{ row }">
            <span v-if="row.reason">{{ row.reason }}</span>
            <span v-else class="muted">未填写</span>
          </template>
        </el-table-column>
        <el-table-column label="状态" width="150">
          <template #default="{ row }">
            <el-tag size="small" :type="STATUS[row.status]?.type ?? 'info'">{{ STATUS[row.status]?.label ?? row.status }}</el-tag>
            <div v-if="row.reviewNote" class="muted">{{ row.reviewNote }}</div>
            <div v-if="row.reviewerUsername" class="muted">{{ row.reviewerUsername }} · {{ formatTime(row.reviewedAt) }}</div>
          </template>
        </el-table-column>
        <el-table-column label="操作" width="140" fixed="right">
          <template #default="{ row }">
            <template v-if="row.status === 'PENDING'">
              <el-button size="small" text type="primary" :loading="busy === row.id" @click="review(row as AppealRow, true)">通过</el-button>
              <el-button size="small" text type="danger" :disabled="busy === row.id" @click="review(row as AppealRow, false)">驳回</el-button>
            </template>
            <span v-else class="muted">—</span>
          </template>
        </el-table-column>
      </el-table>
    </div>
  </section>
</template>

<style scoped>
.head-tools {
  display: flex;
  gap: 8px;
  flex-wrap: wrap;
}
</style>
