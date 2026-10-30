<script setup lang="ts">
import { onBeforeUnmount, ref, watch } from 'vue'
import { ElMessage } from 'element-plus'
import { get, post, del, notifyError } from '@/api/client'
import { useAuthStore } from '@/stores/auth'
type Relationship = { following: boolean; mutual: boolean }
const props = defineProps<{ userId: number; initial?: Relationship }>()
const emit = defineEmits<{ change: [value: Relationship] }>()
const auth = useAuthStore()
const state = ref<Relationship | null>(null), busy = ref(false), composing = ref(false), sending = ref(false), open = ref(false)
const templates = ref<{ code: string; text: string }[]>([]), selected = ref('')
let generation = 0, requestId = ''
watch(() => [props.userId, props.initial] as const, async () => {
  const token = ++generation
  state.value = props.initial ?? null; open.value = false; selected.value = ''; requestId = ''; busy.value = false; composing.value = false; sending.value = false
  if (props.userId === auth.me?.id || props.initial) return
  try { const value = await get<Relationship>(`/social/users/${props.userId}`); if (generation === token) state.value = value } catch { /* 不可见档案不展示社交入口。 */ }
}, { immediate: true })
onBeforeUnmount(() => { generation++ })
async function toggle() {
  if (!state.value || busy.value) return
  const id = props.userId, token = generation
  busy.value = true
  try {
    const next = state.value.following ? await del<Relationship>(`/social/users/${id}/follow`) : await post<Relationship>(`/social/users/${id}/follow`, {})
    if (generation !== token) return
    state.value = next; emit('change', next)
    ElMessage.success(next.mutual ? '已互相关注，可以发送固定消息了' : next.following ? '已关注' : '已取消关注')
  } catch (e) { if (generation === token) notifyError(e) } finally { if (generation === token) busy.value = false }
}
async function compose() {
  if (composing.value || sending.value || busy.value) return
  const token = generation
  composing.value = true
  try {
    const [next, list] = await Promise.all([get<Relationship>(`/social/users/${props.userId}`), get<{code:string;text:string}[]>('/social/templates')])
    if (token !== generation) return
    state.value = next
    if (!next.mutual) { ElMessage.info('互相关注后才能发送好友消息'); emit('change', next); return }
    templates.value = list; selected.value = ''; requestId = ''; open.value = true
  } catch (e) { if (generation === token) notifyError(e) }
  finally { if (generation === token) composing.value = false }
}
watch(selected, () => { requestId = '' })
async function send() {
  if (!selected.value || sending.value || composing.value) return
  const token = generation, recipient = props.userId
  sending.value = true
  requestId ||= crypto.randomUUID()
  try {
    await post(`/social/users/${recipient}/messages`, { templateCode: selected.value, requestId })
    if (generation !== token) return
    open.value = false; requestId = ''; ElMessage.success('已发送到对方的站内信')
  } catch (e) { if (generation === token) notifyError(e) } finally { if (generation === token) sending.value = false }
}
</script>

<template>
  <div v-if="state && userId !== auth.me?.id" class="flex flex-wrap items-center gap-2" data-test="friend-actions">
    <el-button :type="state.following ? 'default' : 'primary'" :loading="busy" :disabled="sending || composing" :title="state.following ? '点击取消关注' : '关注这位同学'" @click="toggle"><span v-if="!state.following" class="i-lucide-plus mr-1" aria-hidden="true" />{{ state.mutual ? '互相关注' : state.following ? '已关注' : '关注' }}</el-button>
    <el-button :loading="composing" :disabled="!state.mutual || busy || sending" :title="state.mutual ? '发送学习提醒' : '互相关注后才能发送消息'" @click="compose">发消息</el-button>
    <el-dialog v-model="open" title="给好友发条学习提醒" width="min(480px, 94vw)" :close-on-click-modal="false" :show-close="!sending" :close-on-press-escape="!sending" append-to-body>
      <p class="mt-0 text-sm text-muted">选择一句固定消息，对方会在站内信的「好友」分类看到。</p>
      <el-radio-group v-model="selected" :disabled="sending" class="!flex flex-col items-stretch gap-3" aria-label="固定消息">
        <el-radio v-for="item in templates" :key="item.code" :value="item.code" class="!m-0 !h-auto rounded-control border border-solid border-line px-3 py-3"><span class="whitespace-normal leading-6">{{ item.text }}</span></el-radio>
      </el-radio-group>
      <p class="mb-0 mt-4 text-xs text-muted">每位好友每天最多 3 条，间隔至少 1 分钟；取消互关后不能继续发送。</p>
      <template #footer><el-button :disabled="sending" @click="open=false">取消</el-button><el-button type="primary" :loading="sending" :disabled="!selected" @click="send">发送</el-button></template>
    </el-dialog>
  </div>
</template>
