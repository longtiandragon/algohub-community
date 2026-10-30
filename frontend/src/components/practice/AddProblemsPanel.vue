<script setup lang="ts">
import { ref } from 'vue'
import { ElMessage } from 'element-plus'
import { get, notifyError, post } from '@/api/client'
import DifficultyTag from '@/components/DifficultyTag.vue'
import { problemCodeBare } from '@/utils/problemLabel'
import { httpUrl, LIST_PLATFORM_OPTIONS, trimmed } from './types'

/** 管理员往题单里加题：按平台 + 题号或标题搜已同步的题库，一题一行「加入」。搜索接口只给管理员用。 */
const props = defineProps<{ listId: number; existing: Set<number>; management?: boolean }>()
const emit = defineEmits<{ added: [] }>()

interface ProblemSearchItem {
  id: number
  platform: string
  problemKey: string
  title: string | null
  url: string | null
  difficultyLabel: string | null
  difficultyValue?: number | null
}

const SEARCH_LIMIT = 20
const platform = ref('CODEFORCES')
const keyword = ref('')
const searching = ref(false)
const searched = ref(false)
const results = ref<ProblemSearchItem[]>([])
const pending = ref<number | null>(null)

async function search() {
  searching.value = true
  try {
    results.value = await get<ProblemSearchItem[]>('/admin/problem-lists/problems', { platform: platform.value, keyword: trimmed(keyword.value), limit: SEARCH_LIMIT })
    searched.value = true
  } catch (e) {
    notifyError(e)
  } finally {
    searching.value = false
  }
}

async function add(row: ProblemSearchItem) {
  pending.value = row.id
  try {
    const result = await post<{ added: number }>(`${props.management ? '/admin/problem-lists' : '/problem-lists'}/${props.listId}/items`, { problemIds: [row.id] })
    if (result.added > 0) ElMessage.success(`已加入「${row.problemKey}」`)
    else ElMessage.info('这道题已经在题单里了')
    emit('added')
  } catch (e) {
    notifyError(e)
  } finally {
    pending.value = null
  }
}
</script>

<template>
  <section class="flex flex-col gap-3 border border-line rounded-card border-solid bg-surface p-4 md:(px-5 py-[18px])" aria-labelledby="add-problems-title" data-test="add-problems">
    <div class="flex items-center gap-2">
      <h2 id="add-problems-title" class="m-0 text-[15px] text-ink font-semibold">添加题目</h2>
      <span class="rounded-md bg-surface-2 px-1.5 py-0.5 text-xs text-body">仅管理员</span>
    </div>
    <form class="flex flex-col gap-2 md:flex-row" @submit.prevent="search">
      <el-select v-model="platform" class="md:!w-[150px]" aria-label="平台">
        <el-option v-for="p in LIST_PLATFORM_OPTIONS" :key="p.value" :label="p.label" :value="p.value" />
      </el-select>
      <el-input v-model="keyword" class="flex-1" placeholder="题号或标题，例如 1600A 或 tree" clearable aria-label="题号或标题" />
      <el-button type="primary" native-type="submit" :loading="searching">搜索</el-button>
    </form>
    <ul v-if="results.length" class="m-0 list-none border border-line-soft rounded-control border-solid p-0">
      <li v-for="row in results" :key="row.id" class="flex items-center gap-3 border-b border-line-soft border-b-solid px-3 py-2 last:border-b-0">
        <span class="shrink-0 text-[13px] text-brand-600 font-mono">{{ problemCodeBare(row).code }}</span>
        <a v-if="httpUrl(row.url)" :href="httpUrl(row.url)" target="_blank" rel="noopener noreferrer" class="min-w-0 flex-1 truncate text-sm text-ink">{{ problemCodeBare(row).name || row.problemKey }}</a>
        <span v-else class="min-w-0 flex-1 truncate text-sm text-ink">{{ problemCodeBare(row).name || row.problemKey }}</span>
        <DifficultyTag :platform="row.platform" :value="row.difficultyValue" :label="row.difficultyLabel" />
        <el-button size="small" :type="existing.has(row.id) ? 'default' : 'primary'" plain :disabled="existing.has(row.id)" :loading="pending === row.id" @click="add(row)">
          {{ existing.has(row.id) ? '已在题单' : '加入' }}
        </el-button>
      </li>
    </ul>
    <p v-else-if="searched" class="m-0 text-[13px] text-muted">没有搜到，换个题号或关键词试试。</p>
    <p class="m-0 text-xs text-muted">只能搜到已同步进题库的题，最多列出 {{ SEARCH_LIMIT }} 条；同一道题重复加入不会多出一条。</p>
  </section>
</template>
