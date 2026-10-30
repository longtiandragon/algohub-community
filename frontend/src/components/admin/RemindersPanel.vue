<script setup lang="ts">
import { onMounted, ref } from 'vue'
import { ElMessage } from 'element-plus'
import { get, post, notifyError } from '@/api/client'
import { mailLabel, noticeTime, type StudyGoals } from '@/api/reminders'
import SectionCard from '@/components/ui/SectionCard.vue'
interface Member { id: number; displayName: string; username: string; enrollmentYear: number | null; className: string | null; schoolName: string }
interface Delivery { id: number; title: string; createdAt: string; status: string; lastError: string | null; readAt: string | null }
const search = ref('')
const members = ref<Member[]>([])
const selected = ref<Member | null>(null)
const goals = ref<StudyGoals | null>(null)
const weekly = ref<number | undefined>()
const monthly = ref<number | undefined>()
const message = ref('')
const deliveries = ref<Delivery[]>([])
const busy = ref(false)
const loading = ref(false)
const hasMore = ref(false)
const status = ref<{ enabled: boolean; mailConfigured: boolean } | null>(null)
let requestId = crypto.randomUUID()
let selectionVersion = 0
async function load(append = false) {
  loading.value = true
  try { const rows = await get<Member[]>('/admin/reminders/users', { search: search.value, after: append ? members.value.at(-1)?.id : 0 }); members.value = append ? [...members.value, ...rows] : rows; hasMore.value = rows.length === 50 }
  catch (e) { notifyError(e) } finally { loading.value = false }
}
async function select(user: Member) {
  const version = ++selectionVersion
  selected.value = user; goals.value = null; deliveries.value = []; message.value = ''; requestId = crypto.randomUUID()
  try {
    const [g, d] = await Promise.all([get<StudyGoals>(`/admin/reminders/users/${user.id}`), get<Delivery[]>(`/admin/reminders/users/${user.id}/deliveries`)])
    if (version !== selectionVersion) return
    goals.value = g; weekly.value = g.assignedWeekly ?? undefined; monthly.value = g.assignedMonthly ?? undefined; deliveries.value = d
  } catch (e) { notifyError(e) }
}
async function save() {
  if (!selected.value) return
  busy.value = true
  try { await post(`/admin/reminders/users/${selected.value.id}/goals`, { weekly: weekly.value ?? null, monthly: monthly.value ?? null }); await select(selected.value); ElMessage.success('要求已更新，已生成通知') }
  catch (e) { notifyError(e) } finally { busy.value = false }
}
async function send() {
  if (!selected.value) return
  busy.value = true
  try { await post(`/admin/reminders/users/${selected.value.id}/send`, { message: message.value, requestId }); requestId = crypto.randomUUID(); await select(selected.value); ElMessage.success('站内提醒已发送，邮件按接收设置入队') }
  catch (e) { notifyError(e) } finally { busy.value = false }
}
onMounted(async () => { await load(); try { status.value = await get('/reminders/status') } catch (e) { notifyError(e) } })
</script>

<template>
  <div class="space-y-4">
    <SectionCard title="学习提醒管理" description="年级默认月目标在「年级配置」维护。周四、每月15日提醒；周一周报，均为北京时间09:00。">
      <p v-if="status && (!status.enabled || !status.mailConfigured)" class="text-sm text-muted" role="status">{{ !status.enabled ? '当前环境的自动任务与邮件发送未启用。' : '邮件服务未配置，邮件会保留在待发送队列。' }}</p>
      <form class="mb-4 flex gap-2" @submit.prevent="load()"><el-input v-model="search" clearable placeholder="搜索姓名、用户名或年级" aria-label="搜索提醒对象" :disabled="loading" /><el-button native-type="submit" :loading="loading">搜索</el-button></form>
      <div class="grid max-h-[320px] gap-2 overflow-y-auto p-1 md:grid-cols-2 lg:grid-cols-3" aria-label="提醒对象列表">
        <button v-for="user in members" :key="user.id" type="button" class="member" :class="{ selected: selected?.id === user.id }" :disabled="busy" @click="select(user)">
          <strong>{{ user.displayName || user.username }}</strong><span class="text-xs text-muted">{{ user.enrollmentYear ? `${user.enrollmentYear}级` : '未填年级' }} · {{ user.className || user.username }}</span>
        </button>
      </div>
      <p v-if="!members.length" class="text-muted">{{ loading ? '正在加载…' : '没有匹配的已入校用户' }}</p>
      <el-button v-if="hasMore" class="mt-3" :loading="loading" @click="load(true)">加载更多用户</el-button>
    </SectionCard>
    <p v-if="selected && !goals" role="status">正在加载 {{ selected.displayName }} 的目标…</p>
    <template v-if="selected && goals">
      <SectionCard :title="`${selected.displayName} · 单独调整要求`" :description="`本周 ${goals.weekly.solved}/${goals.weekly.target} 题，本月 ${goals.monthly.solved}/${goals.monthly.target} 题。提醒目标取学校要求与个人计划的较高值。`">
        <el-form label-position="top" @submit.prevent="save">
          <div class="grid max-w-[600px] gap-x-6 md:grid-cols-2">
            <el-form-item label="每周要求（题）"><el-input-number v-model="weekly" :min="0" :max="10000" :precision="0" aria-label="管理员每周要求" /></el-form-item>
            <el-form-item label="每月要求（题）"><el-input-number v-model="monthly" :min="0" :max="10000" :precision="0" aria-label="管理员每月要求" /></el-form-item>
          </div>
          <p class="text-sm text-muted">清空后恢复默认要求（周目标默认无要求，月目标按年级）；填0表示免除该周期的学校要求。保存后会通知用户。</p>
          <el-button native-type="submit" type="primary" :loading="busy">保存并通知</el-button>
        </el-form>
      </SectionCard>
      <SectionCard title="发送学习提醒" description="自动附上当前周、月目标与进度；可再加一段留言。">
        <el-input v-model="message" type="textarea" :rows="3" maxlength="1000" show-word-limit aria-label="管理员提醒留言" placeholder="例如：本月已过半，记得同步平台记录并安排好练习时间。" />
        <p class="text-sm text-muted">站内消息立即可见；{{ goals.emailEnabled && goals.email ? '邮件将发送至该用户已验证的登录邮箱。' : '该用户未开启邮件或未绑定已验证邮箱，仅保留站内消息。' }}</p>
        <el-button type="primary" :loading="busy" @click="send">发送提醒给 {{ selected.displayName }}</el-button>
      </SectionCard>
      <SectionCard title="最近发送记录" description="待发送不代表已送达。邮件服务未配置时保留队列；失败自动重试，最多5次。">
        <template #actions><el-button text :disabled="busy" @click="select(selected)">刷新</el-button></template>
        <p v-if="!deliveries.length" class="text-muted">暂无记录</p>
        <div v-for="item in deliveries" :key="item.id" class="mb-3 text-sm leading-6"><strong>{{ item.title }}</strong> · {{ mailLabel[item.status] }} · {{ item.readAt ? '站内已读' : '站内未读' }}<br><span class="text-muted">{{ noticeTime(item.createdAt) }}{{ item.lastError ? ` · ${item.lastError}` : '' }}</span></div>
      </SectionCard>
    </template>
  </div>
</template>

<style scoped>
.member { display: flex; flex-direction: column; gap: 6px; padding: 12px; border: 1px solid var(--border); border-radius: 10px; background: var(--bg-elevated); color: var(--text-strong); text-align: left; font: inherit; cursor: pointer; min-width: 0; overflow-wrap: anywhere; }
.member:hover,.member.selected { border-color: var(--brand-500); background: var(--brand-soft); }
.member:focus-visible { outline: 2px solid var(--brand-500); outline-offset: 2px; }
</style>
