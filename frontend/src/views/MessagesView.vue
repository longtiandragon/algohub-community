<script setup lang="ts">
import { computed, onBeforeUnmount, ref, watch } from 'vue'
import { get, post, notifyError } from '@/api/client'
import { mailLabel, type StudyInbox, type StudyNotice } from '@/api/reminders'
import { dayTime } from '@/utils/statistics'
import PageHeader from '@/components/ui/PageHeader.vue'
import SectionCard from '@/components/ui/SectionCard.vue'
import EmptyState from '@/components/ui/EmptyState.vue'
import Skeleton from '@/components/ui/Skeleton.vue'

type Category = 'ALL' | 'SYSTEM' | 'FRIEND'
const category = ref<Category>('ALL')
const categories = [{ value: 'ALL', label: '全部' }, { value: 'SYSTEM', label: '系统通知' }, { value: 'FRIEND', label: '好友消息' }] as const
const items = ref<StudyNotice[]>([]), hasMore = ref(false), loading = ref(false), reading = ref(false), error = ref('')
const unread = ref(0), counts = ref({ SYSTEM: 0, FRIEND: 0 })
const retryAppend = ref(false)
const currentUnread = computed(() => category.value === 'ALL' ? unread.value : counts.value[category.value])
let generation = 0
async function load(append = false) {
  if (append && (loading.value || !hasMore.value)) return
  const token = ++generation
  loading.value = true; error.value = ''; retryAppend.value = append
  try {
    const data = await get<StudyInbox>('/reminders/inbox', { category: category.value, before: append ? items.value.at(-1)?.id : 0 })
    if (token !== generation) return
    items.value = append ? [...items.value, ...data.items] : data.items
    hasMore.value = data.hasMore; unread.value = data.unread; counts.value = data.unreadByCategory
    window.dispatchEvent(new Event('study-inbox-changed'))
  } catch (e) {
    if (token === generation) { error.value = '消息暂时加载失败，请重试'; notifyError(e) }
  } finally { if (token === generation) loading.value = false }
}
function retry() { return load(retryAppend.value) }
watch(category, () => { items.value = []; hasMore.value = false; void load() }, { immediate: true })
onBeforeUnmount(() => { generation++ })
async function markRead(id?: number) {
  if (reading.value) return
  reading.value = true
  try {
    await post(id ? `/reminders/inbox/${id}/read` : `/reminders/inbox/read-all?category=${category.value}`, {})
    // 更新已读状态时保留已经加载的历史页。
    const readAt = new Date().toISOString()
    items.value = items.value.map(item => (!id || item.id === id) ? { ...item, readAt: item.readAt || readAt } : item)
    const data = await get<StudyInbox>('/reminders/inbox', { category: category.value })
    unread.value = data.unread; counts.value = data.unreadByCategory
    window.dispatchEvent(new Event('study-inbox-changed'))
  } catch (e) { notifyError(e) } finally { reading.value = false }
}
</script>

<template>
  <div class="page mx-auto max-w-[1000px] flex flex-col gap-4" data-test="messages-page">
    <PageHeader title="消息" description="系统通知、学习周报和好友消息都在这里。">
      <template #actions><el-button :loading="loading" :disabled="reading" @click="load()">刷新消息</el-button></template>
    </PageHeader>
    <div class="flex flex-wrap items-center justify-between gap-3 text-sm">
      <span class="text-muted">{{ unread ? `${unread} 条未读消息` : '暂无未读消息' }}</span>
      <div class="flex flex-wrap gap-4"><RouterLink to="/friends">关注与好友 →</RouterLink><RouterLink to="/reminders">目标与提醒设置 →</RouterLink></div>
    </div>
    <SectionCard title="站内信" flush data-test="messages-inbox">
      <template #actions><el-button text :disabled="!currentUnread || loading || reading" :loading="reading" @click="markRead()">{{ category === 'ALL' ? '全部已读' : '本类已读' }}</el-button></template>
      <div class="px-4 pb-4 md:px-5">
        <el-radio-group v-model="category" :disabled="reading" aria-label="消息分类" size="small">
          <el-radio-button v-for="tab in categories" :key="tab.value" :value="tab.value">{{ tab.label }}<span v-if="tab.value === 'ALL' ? unread : counts[tab.value]" class="ml-1 num">{{ tab.value === 'ALL' ? unread : counts[tab.value] }}</span></el-radio-button>
        </el-radio-group>
      </div>
      <div v-if="error" role="alert" class="px-4 pb-4 text-sm text-muted">{{ error }} <el-button text :disabled="loading || reading" @click="retry">重试</el-button></div>
      <div v-if="loading && !items.length" class="flex flex-col gap-3 px-4 pb-4"><Skeleton v-for="i in 3" :key="i" class="h-20 w-full" /></div>
      <EmptyState v-else-if="!items.length && !error" icon="i-lucide-mail" :title="category === 'FRIEND' ? '还没有好友消息' : category === 'SYSTEM' ? '还没有系统通知' : '还没有消息'" :description="category === 'FRIEND' ? '互相关注后，可以给好友发送固定的学习提醒。' : '收到新消息后，会显示在这里。'" :bordered="false" />
      <ul v-else class="m-0 list-none p-0">
        <li v-for="item in items" :key="item.id" class="message-row" :class="{ 'is-unread': !item.readAt }">
          <span class="message-icon" :class="item.category === 'FRIEND' ? 'bg-brand-soft text-brand-600' : 'bg-surface-2 text-muted'" aria-hidden="true"><span :class="item.category === 'FRIEND' ? 'i-lucide-message-circle' : 'i-lucide-bell'" /></span>
          <div class="min-w-0 flex-1">
            <div class="flex flex-wrap items-center justify-between gap-x-4 gap-y-1 text-xs text-muted">
              <span v-if="item.category === 'FRIEND'">好友消息 · <RouterLink v-if="item.senderId" :to="`/users/${item.senderId}`">{{ item.senderName || '已注销用户' }}</RouterLink><span v-else>已注销用户</span></span>
              <span v-else>系统通知</span>
              <time class="num" :datetime="item.createdAt">{{ dayTime(item.createdAt) }}</time>
            </div>
            <h2 class="mb-1 mt-2 flex items-center gap-2 text-[15px] text-ink leading-6" :class="item.readAt ? 'font-medium' : 'font-semibold'"><span v-if="!item.readAt" class="size-1.5 shrink-0 rounded-full bg-brand-btn" /><span v-if="!item.readAt" class="sr-only">未读：</span>{{ item.title }}</h2>
            <p class="m-0 whitespace-pre-wrap break-words text-sm text-body leading-6">{{ item.body }}</p>
            <div class="mt-2 flex flex-wrap items-center gap-3 text-xs text-muted">
              <span v-if="item.category === 'SYSTEM' && item.emailStatus">{{ mailLabel[item.emailStatus] || '站内通知' }}</span>
              <span v-if="item.category === 'SYSTEM' && item.emailNote" class="break-words">{{ item.emailNote }}</span>
              <button v-if="!item.readAt" type="button" class="read-button" :disabled="reading || loading" @click="markRead(item.id)">标为已读</button>
              <span v-else>已读</span>
            </div>
          </div>
        </li>
      </ul>
      <div v-if="hasMore" class="border-t border-line-soft px-4 py-3 text-center"><el-button text :loading="loading" :disabled="reading" @click="load(true)">加载更早的消息</el-button></div>
    </SectionCard>
  </div>
</template>

<style scoped>
.message-row { display: flex; gap: 12px; padding: 20px; overflow-wrap: anywhere; }
.message-row + .message-row { border-top: 1px solid var(--border-soft); }
.message-row.is-unread { background: color-mix(in srgb, var(--brand-soft) 30%, transparent); }
.message-icon { width: 32px; height: 32px; flex-shrink: 0; display: flex; align-items: center; justify-content: center; border-radius: 9px; }
.read-button { border: 0; padding: 0; background: none; color: var(--brand-600); font: inherit; cursor: pointer; }
.read-button:disabled { opacity: .5; cursor: default; }
.read-button:focus-visible { outline: 2px solid var(--brand-500); outline-offset: 3px; }
@media (max-width: 767px) { .message-row { padding: 16px; gap: 10px; } }
</style>
