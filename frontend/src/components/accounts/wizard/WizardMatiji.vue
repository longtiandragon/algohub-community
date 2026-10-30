<script setup lang="ts">
import { onBeforeUnmount, ref, watch } from 'vue'
import { ElMessage } from 'element-plus'
import { get, notifyError } from '@/api/client'
import type { MatijiCandidate } from './types'

/**
 * 码蹄集只能按昵称找：先查站内共享的昵称索引（立即返回，不请求码蹄集），没有本人再实时翻排行榜
 * （翻到的整页同时写进索引，每页计入查询限频，可以随时停）。同名账号核对学校、题数、排名后选本人。
 */
defineProps<{
  /** 选中后父组件在查这个账号，按钮先禁用 */
  busy?: boolean
}>()
const emit = defineEmits<{ pick: [candidate: MatijiCandidate] }>()

const nickname = ref('')
const input = ref<{ focus: () => void } | null>(null)
const results = ref<MatijiCandidate[]>([])
/** 昵称索引的覆盖情况：已记下多少人 / 排行榜总人数；查过一次才有 */
const index = ref<{ indexed: number; total: number | null; lastRunAt: string | null } | null>(null)
const progress = ref('')
const searching = ref(false)
const scanning = ref(false)
let nextStart = 0
let run = 0

function stop() {
  run++
  scanning.value = false
}
watch(nickname, () => {
  stop()
  results.value = []
  progress.value = ''
  index.value = null
  nextStart = 0
})
onBeforeUnmount(stop)

function add(list: MatijiCandidate[]) {
  for (const candidate of list) if (!results.value.some((r) => r.userId === candidate.userId)) results.value.push(candidate)
}

/** 先查共享昵称索引 */
async function search() {
  const name = nickname.value.trim()
  if (!name) {
    ElMessage.warning('请填写完整昵称')
    return
  }
  stop()
  results.value = []
  nextStart = 0
  searching.value = true
  try {
    const found = await get<{ candidates: MatijiCandidate[]; indexed: number; total: number | null; lastRunAt: string | null }>(
      '/accounts/matiji-search', { nickname: name })
    index.value = found
    add(found.candidates)
    const coverage = found.total ? `（索引已记下 ${found.indexed} / ${found.total} 名）` : `（索引已记下 ${found.indexed} 名）`
    progress.value = found.candidates.length
      ? `在昵称索引里找到 ${found.candidates.length} 个同名账号${coverage}。核对学校与题数后选择本人；没有本人可以继续在排行榜里查找。`
      : `昵称索引里暂未找到${coverage}，这不代表没有这个账号，可以继续在排行榜里实时查找。`
  } catch (e) {
    notifyError(e)
  } finally {
    searching.value = false
  }
}

/** 实时翻排行榜 */
async function scan() {
  const name = nickname.value.trim()
  if (!name) return
  const current = ++run
  scanning.value = true
  try {
    while (current === run) {
      const page = await get<{ candidates: MatijiCandidate[]; nextStart: number; total: number; hasMore: boolean }>(
        '/accounts/matiji-search', { nickname: name, start: nextStart })
      if (current !== run) return
      add(page.candidates)
      nextStart = page.nextStart
      progress.value = `已实时检查排行榜 ${page.nextStart} / ${page.total} 名，共找到 ${results.value.length} 个同名账号。可随时停止并选择本人；没查完不代表没有其他同名账号。`
      if (!page.hasMore) break
    }
  } catch (e) {
    if (current === run) notifyError(e)
  } finally {
    if (current === run) scanning.value = false
  }
}

function pick(candidate: MatijiCandidate) {
  stop()
  emit('pick', candidate)
}

defineExpose({ search, searching, focus: () => input.value?.focus() })
</script>

<template>
  <div class="flex flex-col gap-3">
    <label class="flex flex-col gap-1.5">
      <span class="text-sm text-ink font-semibold">码蹄集昵称</span>
      <el-input ref="input" v-model="nickname" size="large" placeholder="例如：示例同学" @keyup.enter="search" />
    </label>
    <p v-if="progress" class="m-0 text-[13px] text-muted leading-relaxed" role="status">{{ progress }}</p>

    <ul v-if="results.length" class="m-0 flex list-none flex-col overflow-hidden border border-line rounded-xl border-solid p-0" data-test="matiji-results">
      <li v-for="candidate in results" :key="candidate.userId" class="candidate">
        <span class="min-w-0 flex flex-1 flex-col gap-0.5">
          <span class="truncate text-sm text-ink font-semibold">{{ candidate.nickname }}<span class="mono ml-1.5 text-xs text-muted font-normal">ID {{ candidate.userId }}</span></span>
          <span class="truncate text-[12.5px] text-muted">{{ candidate.school || '学校未填' }} · 通过 {{ candidate.passNum }} 题 · 排名 {{ candidate.rankOrder }}</span>
        </span>
        <el-button :disabled="busy" @click="pick(candidate)">是这个账号</el-button>
      </li>
    </ul>

    <div v-if="index" class="flex flex-wrap gap-2">
      <el-button v-if="scanning" @click="stop">停止查找</el-button>
      <el-button v-else @click="scan">{{ nextStart ? '继续在排行榜里查找' : '在排行榜里实时查找' }}</el-button>
    </div>
  </div>
</template>

<style scoped>
.candidate {
  display: flex;
  align-items: center;
  gap: 12px;
  padding: 10px 14px;
  border-top: 1px solid var(--border-soft);
}

.candidate:first-child {
  border-top: 0;
}
</style>
