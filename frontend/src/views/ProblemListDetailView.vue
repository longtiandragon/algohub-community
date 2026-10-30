<script setup lang="ts">
import { computed, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ElMessage, ElMessageBox } from 'element-plus'
import { del, get, notifyError, post } from '@/api/client'
import { useAuthStore } from '@/stores/auth'
import PlatformLogo from '@/components/PlatformLogo.vue'
import GoalRing from '@/components/activity/GoalRing.vue'
import StarIcon from '@/components/contest/StarIcon.vue'
import EmptyState from '@/components/ui/EmptyState.vue'
import SegmentedControl from '@/components/ui/SegmentedControl.vue'
import Skeleton from '@/components/ui/Skeleton.vue'
import AddProblemsPanel from '@/components/practice/AddProblemsPanel.vue'
import ListEditDialog from '@/components/practice/ListEditDialog.vue'
import ListItemRow from '@/components/practice/ListItemRow.vue'
import ListLeaderboard from '@/components/practice/ListLeaderboard.vue'
import { httpUrl, readablePlatforms, type LeaderboardResponse, type ListDetail, type ListItem } from '@/components/practice/types'
import { platformName } from '@/utils/platformBrand'
import { problemCodeBare } from '@/utils/problemLabel'

type Mark = 'LATER' | 'REVIEW' | 'DONE' | null

const LEADERBOARD_LIMIT = 20

const route = useRoute()
const router = useRouter()
const auth = useAuthStore()
const props = defineProps<{ management?: boolean }>()
const management = computed(() => props.management === true && auth.isAdmin)
const apiBase = computed(() => management.value ? '/admin/problem-lists' : '/problem-lists')
const listPath = computed(() => management.value ? '/admin/problem-lists' : '/problem-lists')

const detail = ref<ListDetail | null>(null)
const leaderboard = ref<LeaderboardResponse | null>(null)
/** 题单里这些题的收藏状态，加载题单后一次取回 */
const marks = ref<Record<number, Mark>>({})
const loading = ref(false)
const loadError = ref(false)
const filter = ref<'ALL' | 'UNSOLVED'>('ALL')
const favoritePending = ref(false)
const editVisible = ref(false)

const listId = computed(() => {
  const raw = route.params.id
  return Array.isArray(raw) ? raw[0] : raw
})
const list = computed(() => detail.value?.list ?? null)
const items = computed(() => detail.value?.items ?? [])
const total = computed(() => detail.value?.itemCount ?? 0)
const solved = computed(() => Math.min(detail.value?.solvedCount ?? 0, total.value))
const unsolved = computed(() => items.value.filter((i) => !i.solvedByMe))
const visibleItems = computed(() => (filter.value === 'UNSOLVED' ? unsolved.value : items.value))
const filterOptions = computed(() => [
  { value: 'ALL' as const, label: `全部 ${items.value.length}` },
  { value: 'UNSOLVED' as const, label: `没通过的 ${unsolved.value.length}` }
])

/** 做下一题：按题单顺序第一道还没通过的 */
const next = computed(() => {
  const item = unsolved.value[0]
  return item ? { item, code: problemCodeBare(item).code || item.problemKey, url: httpUrl(item.url) } : null
})

const isMine = computed(() => list.value?.ownerId != null && auth.me?.id !== undefined && list.value.ownerId === auth.me.id)
const canEdit = computed(() => list.value !== null && (management.value || isMine.value))
const canAdd = computed(() => canEdit.value && auth.isAdmin)
const existingIds = computed(() => new Set(items.value.map((i) => i.problemId)))

const author = computed(() => {
  const current = list.value
  if (!current) return ''
  if (current.ownerId === null) return '管理员整理'
  return isMine.value ? '我' : (current.ownerName ?? '同学')
})
const scopeText = computed(() => {
  const current = list.value
  if (!current || current.ownerId === null) return ''
  return { PRIVATE: '仅自己', SCHOOL: '本校可见', PUBLIC: '全站公开' }[current.shareScope]
})
const readable = (text: string | null | undefined) => readablePlatforms(text, platformName)

async function loadMarks() {
  const ids = [...new Set(items.value.map((i) => i.problemId).filter(Boolean))]
  if (!ids.length) {
    marks.value = {}
    return
  }
  try {
    const found = await get<Record<string, Exclude<Mark, null>>>('/bookmarks/status', { problemIds: ids.join(',') })
    marks.value = Object.fromEntries(ids.map((id) => [id, found[String(id)] ?? null]))
  } catch {
    // 取不到就当作都没标记，不影响看题单
    marks.value = {}
  }
}

let requestId = 0
async function load() {
  const id = listId.value
  if (!id) return
  const current = ++requestId
  loading.value = true
  try {
    // 详情和完成榜互不依赖，并行请求
    const [detailData, boardData] = await Promise.all([
      get<ListDetail>(`${apiBase.value}/${id}`),
      management.value ? Promise.resolve(null) : get<LeaderboardResponse>(`/problem-lists/${id}/leaderboard`, { limit: LEADERBOARD_LIMIT })
    ])
    if (current !== requestId) return
    detail.value = detailData
    leaderboard.value = boardData
    loadError.value = false
    await loadMarks()
  } catch (e) {
    if (current !== requestId) return
    if (!detail.value) loadError.value = true
    else notifyError(e)
  } finally {
    if (current === requestId) loading.value = false
  }
}

watch(
  () => [listId.value, management.value],
  () => {
    detail.value = null
    leaderboard.value = null
    loadError.value = false
    filter.value = 'ALL'
    void load()
  },
  { immediate: true }
)

async function toggleFavorite() {
  if (management.value) return
  const current = list.value
  if (!current) return
  const nextState = !current.favorited
  favoritePending.value = true
  try {
    if (nextState) await post(`/problem-lists/${current.id}/favorite`)
    else await del(`/problem-lists/${current.id}/favorite`)
    current.favorited = nextState
    current.favoriteCount = Math.max(0, (current.favoriteCount ?? 0) + (nextState ? 1 : -1))
  } catch (e) {
    notifyError(e)
  } finally {
    favoritePending.value = false
  }
}

async function deleteList() {
  const current = list.value
  if (!current || !canEdit.value) return
  try {
    await ElMessageBox.confirm(`删除后「${current.title}」和里面的题目都会消失，收藏过它的同学也看不到了。删除后不能恢复。`, '删除题单', {
      type: 'warning',
      confirmButtonText: '删除',
      cancelButtonText: '取消',
      confirmButtonClass: 'el-button--danger'
    })
  } catch {
    return
  }
  try {
    await del(`${apiBase.value}/${current.id}`)
    ElMessage.success('题单已删除')
    router.push(management.value ? listPath.value : '/problem-lists?tab=mine')
  } catch (e) {
    notifyError(e)
  }
}

async function removeItem(item: ListItem) {
  const current = list.value
  if (!current || !canEdit.value) return
  const { code, name } = problemCodeBare(item)
  try {
    await ElMessageBox.confirm(`把「${code} ${name}」移出这个题单？`, '移出题目', { type: 'warning', confirmButtonText: '移出', cancelButtonText: '取消' })
  } catch {
    return
  }
  try {
    await del(`${apiBase.value}/${current.id}/items/${item.problemId}`)
    ElMessage.success('已移出题单')
    await load()
  } catch (e) {
    notifyError(e)
  }
}

function onMore(command: string) {
  if (command === 'edit') editVisible.value = true
  else if (command === 'delete') void deleteList()
}
</script>

<template>
  <div class="flex flex-col gap-4 md:gap-[18px]" data-test="page">
    <nav aria-label="位置" class="hidden items-center gap-1.5 text-[13.5px] text-muted md:flex">
      <RouterLink :to="listPath" class="text-brand-600 hover:underline">{{ management ? '题单管理' : '题单训练' }}</RouterLink>
      <span class="i-lucide-chevron-right text-sm" aria-hidden="true" />
      <span class="min-w-0 truncate" aria-current="page">{{ list?.title ?? '题单详情' }}</span>
    </nav>

    <EmptyState
      v-if="loadError"
      icon="i-lucide-list-x"
      title="打不开这个题单"
      description="它可能已经被删除，或者作者没有分享给你。"
      action="回到题单训练"
      :to="listPath"
    />

    <template v-else>
      <!-- 头部：标题、平台、分组、作者、说明；右边进度圆环、做下一题、收藏 -->
      <section class="flex flex-col gap-5 border border-line rounded-[16px] border-solid bg-surface p-4 md:(p-6) lg:(flex-row items-center gap-7 px-[26px])" data-test="list-head">
        <div class="min-w-0 flex flex-1 flex-col gap-2.5">
          <template v-if="list">
            <h1 class="m-0 text-xl text-ink font-bold leading-[1.4] [overflow-wrap:anywhere] md:text-2xl">{{ list.title }}</h1>
            <div class="flex flex-wrap items-center gap-2 text-[12.5px]">
              <span v-if="list.platform" class="h-6 inline-flex items-center gap-1.5 border border-line rounded-full border-solid pl-[3px] pr-2.5 text-body">
                <PlatformLogo :platform="list.platform" :size="18" />{{ platformName(list.platform) }}
              </span>
              <span v-if="list.category" class="h-6 inline-flex items-center rounded-full bg-surface-2 px-2.5 text-body">{{ list.category }}</span>
              <el-tooltip v-if="list.source === 'GENERATED'" :content="readable(list.generatedRule) || '按规则从已同步题库自动筛选，不是人工挑的'" placement="top">
                <span class="h-6 inline-flex cursor-help items-center rounded-full bg-surface-2 px-2.5 text-body" tabindex="0">自动生成</span>
              </el-tooltip>
              <span class="text-[13px] text-muted">
                {{ author }}<template v-if="scopeText"> · {{ scopeText }}</template> · {{ total }} 题 · {{ list.favoriteCount ?? 0 }} 人收藏
              </span>
            </div>
            <p v-if="list.description" class="m-0 max-w-[620px] text-sm text-body leading-[1.7] [overflow-wrap:anywhere]">{{ list.description }}</p>
            <p v-if="list.source === 'GENERATED' && list.generatedRule" class="m-0 text-[12.5px] text-muted">生成规则：{{ readable(list.generatedRule) }}</p>
          </template>
          <template v-else>
            <Skeleton class="h-8 w-2/3" />
            <Skeleton class="h-5 w-1/2" />
            <Skeleton class="h-5 w-3/4" />
          </template>
        </div>

        <div v-if="list" class="flex items-center gap-4 md:gap-[18px]">
          <span class="relative size-[76px] shrink-0 md:size-24" role="img" :aria-label="`已通过 ${solved} / ${total} 题`">
            <GoalRing :solved="solved" :target="total || null" :size="96" :stroke="10" class="size-full" />
            <span class="absolute inset-0 flex flex-col items-center justify-center" aria-hidden="true">
              <span class="num text-lg text-ink font-semibold md:text-[22px]">{{ solved }}/{{ total }}</span>
              <span class="text-[11.5px] text-muted">已通过</span>
            </span>
          </span>
          <div class="min-w-0 flex flex-1 flex-col gap-2 lg:flex-none">
            <a
              v-if="next && httpUrl(next.url)"
              :href="httpUrl(next.url)"
              target="_blank"
              rel="noopener noreferrer"
              class="next-btn"
              data-test="next-problem"
              :aria-label="`做下一题 ${next.code}（在新标签页打开）`"
            >
              做下一题 <span class="font-mono">{{ next.code }}</span><span class="i-lucide-external-link text-sm" aria-hidden="true" />
            </a>
            <p v-else-if="total > 0 && !next" class="m-0 inline-flex items-center gap-1.5 text-sm text-success-ink font-semibold">
              <span class="i-lucide-circle-check text-base" aria-hidden="true" />全部通过了
            </p>
            <div class="flex gap-2">
              <button
                v-if="!management"
                type="button"
                class="fav-btn"
                :aria-pressed="Boolean(list.favorited)"
                :disabled="favoritePending"
                data-test="list-favorite"
                @click="toggleFavorite"
              >
                <StarIcon :on="Boolean(list.favorited)" :size="16" />{{ list.favorited ? '已收藏' : '收藏题单' }}
              </button>
              <el-dropdown v-if="canEdit" trigger="click" placement="bottom-end" @command="onMore">
                <button type="button" class="fav-btn !px-2.5" aria-label="更多操作" data-test="list-more">
                  <span class="i-lucide-ellipsis text-lg" aria-hidden="true" />
                </button>
                <template #dropdown>
                  <el-dropdown-menu>
                    <el-dropdown-item command="edit">编辑题单</el-dropdown-item>
                    <el-dropdown-item command="delete" class="!text-danger-ink">删除题单</el-dropdown-item>
                  </el-dropdown-menu>
                </template>
              </el-dropdown>
            </div>
          </div>
        </div>
      </section>

      <div class="grid grid-cols-1 items-start gap-4 lg:(grid-cols-[minmax(0,2fr)_minmax(0,1fr)] gap-5)">
        <div class="min-w-0 flex flex-col gap-4">
          <section class="overflow-hidden border border-line rounded-card border-solid bg-surface" aria-labelledby="list-items-title" data-test="list-items">
            <div class="flex items-center justify-between gap-3 border-b border-line-soft border-b-solid px-4 py-3 md:(px-5 py-3.5)">
              <h2 id="list-items-title" class="m-0 text-base text-ink font-semibold">题目</h2>
              <SegmentedControl v-if="items.length" v-model="filter" :options="filterOptions" label="筛选题目" class="small" />
            </div>
            <div v-if="!detail" class="flex flex-col gap-3 p-5">
              <Skeleton v-for="i in 5" :key="i" class="h-9 w-full" />
            </div>
            <EmptyState
              v-else-if="!items.length"
              :bordered="false"
              icon="i-lucide-list-plus"
              title="这个题单还没有题目"
              :description="list?.source === 'GENERATED' ? '对应平台的题库同步之后，规则会自动选出题目。' : canAdd ? '在下面「添加题目」里搜题加入。' : '等作者加题后再来。'"
            />
            <EmptyState v-else-if="!visibleItems.length" :bordered="false" icon="i-lucide-party-popper" title="这个题单的题你都通过了" />
            <ol v-else class="m-0 list-none p-0">
              <ListItemRow
                v-for="item in visibleItems"
                :key="item.itemId"
                v-model:mark="marks[item.problemId]"
                :item="item"
                :removable="canEdit"
                @remove="removeItem(item)"
              />
            </ol>
            <p v-if="detail?.note && items.length" class="m-0 border-t border-line-soft border-t-solid px-4 py-3 text-[12.5px] text-muted leading-[1.6] md:px-5">{{ readable(detail.note) }}</p>
          </section>

          <AddProblemsPanel v-if="canAdd && list" :list-id="list.id" :existing="existingIds" :management="management" @added="load" />
        </div>

        <ListLeaderboard v-if="!management" :board="leaderboard" :total="total" :me-id="auth.me?.id" :loading="loading" class="lg:sticky lg:top-6" />
      </div>
    </template>

    <ListEditDialog v-model="editVisible" :list="list" :management="management" @saved="load" />
  </div>
</template>

<style scoped>
.next-btn {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  gap: 6px;
  height: 40px;
  padding: 0 16px;
  border-radius: 10px;
  background: var(--brand-btn);
  color: #fff;
  font-size: 14px;
  font-weight: 600;
  text-decoration: none;
  white-space: nowrap;
}

.next-btn:hover {
  filter: brightness(1.08);
}

.fav-btn {
  display: inline-flex;
  flex: 1;
  align-items: center;
  justify-content: center;
  gap: 6px;
  height: 36px;
  padding: 0 14px;
  border: 1px solid var(--border-strong);
  border-radius: 10px;
  background: var(--bg-elevated);
  color: var(--text-strong);
  font: inherit;
  font-size: 13.5px;
  cursor: pointer;
}

.fav-btn:hover {
  background: var(--surface-hover);
}

.fav-btn[aria-label] {
  flex: none;
}

.next-btn:focus-visible,
.fav-btn:focus-visible {
  outline: 2px solid var(--brand-500);
  outline-offset: 2px;
}
</style>
