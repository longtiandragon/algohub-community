<script setup lang="ts">
import { computed, ref, watch } from 'vue'
import { useRoute, useRouter, type LocationQueryRaw } from 'vue-router'
import { ElMessage, ElMessageBox } from 'element-plus'
import { del, get, notifyError } from '@/api/client'
import type { PlatformCapability } from '@/api/types'
import BookmarkCard from '@/components/practice/BookmarkCard.vue'
import BookmarkEditDialog from '@/components/practice/BookmarkEditDialog.vue'
import type { BookmarkRow } from '@/components/practice/types'
import EmptyState from '@/components/ui/EmptyState.vue'
import FilterBar from '@/components/ui/FilterBar.vue'
import InfoTip from '@/components/ui/InfoTip.vue'
import PageHeader from '@/components/ui/PageHeader.vue'
import PageTabs from '@/components/ui/PageTabs.vue'
import Skeleton from '@/components/ui/Skeleton.vue'
import type { FilterField, FilterValues } from '@/components/ui/types'
import { useBreakpoint } from '@/composables/useBreakpoint'
import { categoryName } from '@/utils/problemLabel'

interface BookmarkPage {
  items: BookmarkRow[]
  total: number
  page: number
  size: number
  counts: Record<string, { count: number; due: number }>
  note: string
}

const PAGE_SIZE = 20
/** 标签按画布：全部 / 待复习 / 稍后再做 / 已过关；?tab= 记住 */
const TABS = [
  { key: 'all', label: '全部', status: '', count: 'TOTAL' },
  { key: 'review', label: '待复习', status: 'REVIEW', count: 'REVIEW' },
  { key: 'later', label: '稍后再做', status: 'LATER', count: 'LATER' },
  { key: 'done', label: '已过关', status: 'DONE', count: 'DONE' }
] as const

const route = useRoute()
const router = useRouter()
const { isMobile } = useBreakpoint()

const str = (v: unknown) => (typeof v === 'string' ? v : '')
const tab = computed(() => TABS.find((t) => t.key === route.query.tab) ?? TABS[0])
const filters = computed<FilterValues>(() => ({ platform: str(route.query.platform), category: str(route.query.category) }))
const dueOnly = computed(() => route.query.due === '1')
const page = computed(() => Math.max(1, Number(str(route.query.page)) || 1))

function update(patch: Record<string, string | null>, keepPage = false) {
  const query: LocationQueryRaw = { ...route.query }
  for (const [key, value] of Object.entries(patch)) {
    if (!value) delete query[key]
    else query[key] = value
  }
  if (!keepPage) delete query.page
  void router.replace({ query })
}

const data = ref<BookmarkPage | null>(null)
const loading = ref(false)
const loadError = ref(false)
const platforms = ref<PlatformCapability[]>([])
const categories = ref<string[]>([])

const items = computed(() => data.value?.items ?? [])
const total = computed(() => data.value?.total ?? 0)
const countOf = (key: string) => data.value?.counts?.[key]?.count ?? 0
const dueTotal = computed(() => data.value?.counts?.TOTAL?.due ?? 0)
/** 一条收藏都没有：分组和筛选都没意义，只留引导 */
const noBookmarks = computed(() => data.value !== null && countOf('TOTAL') === 0)
const pageCount = computed(() => Math.ceil(total.value / PAGE_SIZE))

const tabLinks = computed(() =>
  TABS.map((t) => {
    const { page: _page, ...rest } = route.query
    return { key: t.key, label: `${t.label} ${countOf(t.count)}`, to: { query: { ...rest, tab: t.key === 'all' ? undefined : t.key } } }
  })
)

const fields = computed<FilterField[]>(() => [
  {
    key: 'platform',
    label: '平台',
    defaultValue: '',
    display: 'pills',
    options: [{ label: '全部平台', value: '' }, ...platforms.value.map((p) => ({ label: p.displayName, value: p.platform }))]
  },
  {
    key: 'category',
    label: '算法方向',
    defaultValue: '',
    display: 'select',
    options: [{ label: '全部方向', value: '' }, ...categories.value.map((c) => ({ label: categoryName(c), value: c }))]
  }
])

let requestId = 0
async function load() {
  const id = ++requestId
  loading.value = true
  try {
    const result = await get<BookmarkPage>('/bookmarks', {
      status: tab.value.status || undefined,
      platform: filters.value.platform || undefined,
      category: filters.value.category || undefined,
      dueOnly: dueOnly.value || undefined,
      page: page.value,
      size: PAGE_SIZE
    })
    if (id !== requestId) return
    data.value = result
    loadError.value = false
  } catch (e) {
    if (id !== requestId) return
    if (!data.value) loadError.value = true
    notifyError(e)
  } finally {
    if (id === requestId) loading.value = false
  }
}
watch(() => [route.query.tab, route.query.platform, route.query.category, route.query.due, route.query.page], () => void load(), { immediate: true })

void get<PlatformCapability[]>('/platforms/capabilities').then((d) => (platforms.value = d)).catch(() => {})
// 算法方向的取值来自算法地图，和那边同一套口径
void get<{ categories: { category: string }[] }>('/skill-map')
  .then((m) => (categories.value = (m.categories ?? []).map((c) => c.category)))
  .catch(() => {})

// ---------- 编辑、取消收藏 ----------
const editOpen = ref(false)
const editRow = ref<BookmarkRow | null>(null)
function openEdit(row: BookmarkRow) {
  editRow.value = row
  editOpen.value = true
}
async function remove(row: BookmarkRow) {
  // 有笔记的先确认，笔记会跟着删掉
  if (row.note) {
    try {
      await ElMessageBox.confirm('取消收藏后，这道题的笔记也会删掉。通过记录不受影响。', '取消收藏', {
        type: 'warning',
        confirmButtonText: '取消收藏',
        cancelButtonText: '留着'
      })
    } catch {
      return
    }
  }
  try {
    await del(`/bookmarks/${row.problemId}`)
    ElMessage.success('已取消收藏，通过记录不受影响')
    await load()
  } catch (e) {
    notifyError(e)
  }
}

const TIPS = ['「稍后再做」「待复习」「已过关」都是你自己标的，标成「已过关」不会变成通过记录。', '「已通过」来自平台同步的通过记录；到了复习日期只在站内提示，不发邮件或推送。']
</script>

<template>
  <div class="flex flex-col gap-4 md:gap-[18px]" data-test="page">
    <PageHeader title="我的收藏" class="!mb-0">
      <template #description>
        稍后再做、待复习的题和你的笔记都在这里，只有你自己能看到
        <span class="hidden md:inline">
          <InfoTip title="收藏和通过记录" label="收藏和通过记录的区别" class="ml-0.5 align-[-3px]">
            <p v-for="(tip, i) in TIPS" :key="i" class="m-0" :class="{ 'mt-1.5': i }">{{ tip }}</p>
          </InfoTip>
        </span>
      </template>
      <template #mobile-actions>
        <InfoTip title="收藏和通过记录" label="收藏和通过记录的区别" topbar>
          <p v-for="(tip, i) in TIPS" :key="i" class="m-0" :class="{ 'mt-1.5': i }">{{ tip }}</p>
        </InfoTip>
      </template>
    </PageHeader>

    <!-- 到期提醒条 -->
    <div v-if="dueTotal > 0 || dueOnly" class="flex items-center gap-3 border border-warning-line rounded-xl border-solid bg-warning-soft px-4 py-3" role="status" data-test="due-banner">
      <span class="i-lucide-calendar-clock shrink-0 text-lg text-warning-ink" aria-hidden="true" />
      <span class="min-w-0 flex-1 text-sm text-body">
        <template v-if="dueTotal > 0">今天有 <strong class="num text-ink">{{ dueTotal }}</strong> 道题到了复习日期</template>
        <template v-else>没有到期的题了</template>
      </span>
      <button type="button" class="due-btn" :aria-pressed="dueOnly" data-test="due-toggle" @click="update({ due: dueOnly ? null : '1' })">
        {{ dueOnly ? '看全部' : '只看到期的' }}
      </button>
    </div>

    <template v-if="!noBookmarks">
      <div class="flex flex-col gap-3 md:(flex-row items-end justify-between gap-4)">
        <PageTabs :tabs="tabLinks" :active="tab.key" label="收藏分组" class="min-w-0 md:flex-1" />
        <div class="min-w-0 md:(shrink-0 pb-1.5)">
          <FilterBar :model-value="filters" :fields="fields" sheet-title="筛选收藏" @update:model-value="(v) => update({ platform: String(v.platform ?? ''), category: String(v.category ?? '') })" />
        </div>
      </div>
    </template>

    <EmptyState v-if="loadError" icon="i-lucide-cloud-off" title="收藏没加载出来" description="检查网络后再试一次。" action="重试" @action="load" />
    <div v-else-if="!data" class="flex flex-col gap-3">
      <Skeleton v-for="i in 4" :key="i" class="h-[104px] rounded-card" />
    </div>
    <EmptyState
      v-else-if="noBookmarks"
      icon="i-lucide-star"
      title="还没有收藏"
      description="在题库、题单或校内动态里点题目旁的星标，把想做和想复习的题存下来。"
      action="去题库找题"
      to="/problems"
    />
    <EmptyState
      v-else-if="!items.length"
      icon="i-lucide-search-x"
      :title="dueOnly ? '这一组里没有到期的题' : '没有符合条件的收藏'"
      description="换个分组，或清掉平台、算法方向筛选再看。"
    />
    <section v-else class="flex flex-col gap-3 transition-opacity" :class="{ 'opacity-60': loading }" aria-label="收藏列表" data-test="bookmarks">
      <BookmarkCard v-for="row in items" :key="row.id" :row="row" @edit="openEdit(row)" @remove="remove(row)" />
      <div v-if="pageCount > 1" class="flex flex-col items-center gap-2 pt-1 md:(flex-row justify-between)">
        <span class="text-[13px] text-muted">共 <span class="num">{{ total }}</span> 条 · 第 <span class="num">{{ page }}</span> / <span class="num">{{ pageCount }}</span> 页</span>
        <el-pagination
          :current-page="page"
          :page-size="PAGE_SIZE"
          :total="total"
          :layout="isMobile ? 'prev, next' : 'prev, pager, next'"
          @current-change="(p: number) => update({ page: p > 1 ? String(p) : null }, true)"
        />
      </div>
    </section>

    <p v-if="data?.note && items.length" class="m-0 text-xs text-muted leading-[1.6]">{{ data.note }}</p>

    <BookmarkEditDialog v-model="editOpen" :row="editRow" @saved="load" />
  </div>
</template>

<style scoped>
.due-btn {
  flex-shrink: 0;
  height: 32px;
  padding: 0 12px;
  border: 0;
  border-radius: 8px;
  background: var(--warning-ink);
  color: var(--bg-elevated);
  font: inherit;
  font-size: 13px;
  font-weight: 600;
  cursor: pointer;
}

.due-btn[aria-pressed='true'] {
  border: 1px solid var(--warning-ink);
  background: transparent;
  color: var(--warning-ink);
}

.due-btn:focus-visible {
  outline: 2px solid var(--brand-500);
  outline-offset: 2px;
}
</style>
