<script setup lang="ts">
import { computed, onMounted, ref, watch } from 'vue'
import { useRoute, useRouter, type LocationQueryRaw } from 'vue-router'
import { get, notifyError } from '@/api/client'
import type { PlatformCapability } from '@/api/types'
import PlatformLogo from '@/components/PlatformLogo.vue'
import ProblemRow, { type ProblemsetRow } from '@/components/practice/ProblemRow.vue'
import EmptyState from '@/components/ui/EmptyState.vue'
import FilterBar from '@/components/ui/FilterBar.vue'
import InfoTip from '@/components/ui/InfoTip.vue'
import PageHeader from '@/components/ui/PageHeader.vue'
import Skeleton from '@/components/ui/Skeleton.vue'
import type { FilterField, FilterValues } from '@/components/ui/types'
import { useBreakpoint } from '@/composables/useBreakpoint'
import { categoryName } from '@/utils/problemLabel'

type Mark = 'LATER' | 'REVIEW' | 'DONE' | null

interface CatalogRow {
  platform: string
  catalogCount: number
  solvedCount: number
  minDifficulty: number | null
  maxDifficulty: number | null
}

interface ProblemsetPage {
  items: ProblemsetRow[]
  total: number
  page: number
  size: number
  onlyRated: boolean
  catalog: CatalogRow[]
  note: string
}

const PAGE_SIZE = 20
/** 后端最多翻到第 400 页 */
const MAX_PAGE = 400
const DIFF_MIN = 0
const DIFF_MAX = 3500
const SORT_DEFAULT = 'POPULAR'

const route = useRoute()
const router = useRouter()
const { isMobile } = useBreakpoint()

// ---------- 筛选条件都放在网址上：刷新、返回、分享都停在同一组结果 ----------
const str = (v: unknown) => (typeof v === 'string' ? v : '')
const num = (v: unknown) => {
  const n = Number(str(v))
  return str(v) !== '' && Number.isFinite(n) ? n : null
}
const keyword = ref(str(route.query.q))
const filters = computed<FilterValues>(() => ({
  platform: str(route.query.platform),
  category: str(route.query.category),
  status: str(route.query.status),
  sort: str(route.query.sort) || SORT_DEFAULT
}))
const range = computed<[number, number] | null>(() => {
  const min = num(route.query.min)
  const max = num(route.query.max)
  return min === null || max === null ? null : [min, max]
})
const page = computed(() => Math.max(1, num(route.query.page) ?? 1))

function update(patch: Record<string, string | number | null>, keepPage = false) {
  const query: LocationQueryRaw = { ...route.query }
  for (const [key, value] of Object.entries(patch)) {
    if (value === null || value === '' || (key === 'sort' && value === SORT_DEFAULT)) delete query[key]
    else query[key] = String(value)
  }
  if (!keepPage) delete query.page
  void router.replace({ query })
}

function setFilters(values: FilterValues) {
  update({ platform: values.platform, category: values.category, status: values.status, sort: values.sort })
}

// 关键词：停手 400ms 再搜，回车立刻搜
let typing: ReturnType<typeof setTimeout> | undefined
watch(keyword, (value) => {
  clearTimeout(typing)
  typing = setTimeout(() => update({ q: value.trim() || null }), 400)
})
function searchNow() {
  clearTimeout(typing)
  update({ q: keyword.value.trim() || null })
}
watch(
  () => route.query.q,
  (q) => {
    if (str(q) !== keyword.value.trim()) keyword.value = str(q)
  }
)

// 难度滑块：拖动时只改草稿，松手才搜
const sliderDraft = ref<number[]>(range.value ?? [DIFF_MIN, DIFF_MAX])
watch(range, (value) => (sliderDraft.value = value ?? [DIFF_MIN, DIFF_MAX]))
function commitRange(value: number | number[]) {
  const [min, max] = value as number[]
  update(min === DIFF_MIN && max === DIFF_MAX ? { min: null, max: null } : { min, max })
}
const rangeText = computed(() => (range.value ? `${range.value[0]} – ${range.value[1]}` : '不限'))

const hasFilters = computed(
  () => !!(keyword.value.trim() || filters.value.platform || filters.value.category || filters.value.status || range.value || filters.value.sort !== SORT_DEFAULT)
)
function clearAll() {
  keyword.value = ''
  clearTimeout(typing)
  void router.replace({ query: {} })
}

// ---------- 数据 ----------
const data = ref<ProblemsetPage | null>(null)
const loading = ref(false)
const platforms = ref<PlatformCapability[]>([])
const categories = ref<string[]>([])
const marks = ref<Record<number, Mark>>({})

const items = computed(() => data.value?.items ?? [])
const total = computed(() => data.value?.total ?? 0)
const catalog = computed(() => data.value?.catalog ?? [])
const pageCount = computed(() => Math.min(MAX_PAGE, Math.ceil(total.value / PAGE_SIZE)))
/** 题库为空说明还没同步过：引导去同步，而不是让人以为平台坏了 */
const catalogEmpty = computed(() => data.value !== null && catalog.value.length === 0)

function platformLabel(value: string) {
  return platforms.value.find((p) => p.platform === value)?.displayName ?? value
}

const fields = computed<FilterField[]>(() => {
  // 平台只列题库里有题的
  const synced = catalog.value.map((c) => c.platform)
  return [
    {
      key: 'platform',
      label: '平台',
      defaultValue: '',
      display: 'pills',
      options: [{ label: '全部平台', value: '' }, ...synced.map((p) => ({ label: platformLabel(p), value: p }))]
    },
    {
      key: 'category',
      label: '算法方向',
      defaultValue: '',
      display: 'select',
      options: [{ label: '全部方向', value: '' }, ...categories.value.map((c) => ({ label: categoryName(c), value: c }))]
    },
    {
      key: 'status',
      label: '状态',
      defaultValue: '',
      display: 'pills',
      options: [
        { label: '全部状态', value: '' },
        { label: '还没通过', value: 'UNSOLVED' },
        { label: '已通过', value: 'SOLVED' }
      ]
    },
    {
      key: 'sort',
      label: '排序',
      defaultValue: SORT_DEFAULT,
      display: 'pills',
      options: [
        { label: '按通过人数', value: 'POPULAR' },
        { label: '难度从低到高', value: 'DIFFICULTY_ASC' },
        { label: '难度从高到低', value: 'DIFFICULTY_DESC' },
        { label: '按题号', value: 'KEY' }
      ]
    }
  ]
})

let requestId = 0
async function load() {
  const id = ++requestId
  loading.value = true
  try {
    const result = await get<ProblemsetPage>('/problems', {
      platform: filters.value.platform || undefined,
      category: filters.value.category || undefined,
      status: filters.value.status || undefined,
      sort: filters.value.sort,
      keyword: str(route.query.q).trim() || undefined,
      minDifficulty: range.value?.[0],
      maxDifficulty: range.value?.[1],
      page: page.value,
      size: PAGE_SIZE
    })
    if (id !== requestId) return
    data.value = result
    await loadMarks(id)
  } catch (e) {
    if (id === requestId) notifyError(e)
  } finally {
    if (id === requestId) loading.value = false
  }
}

/** 一次取回当前页所有题的收藏状态，不每题请求一次 */
async function loadMarks(id: number) {
  const ids = items.value.map((i) => i.id)
  if (!ids.length) {
    marks.value = {}
    return
  }
  try {
    const found = await get<Record<string, Exclude<Mark, null>>>('/bookmarks/status', { problemIds: ids.join(',') })
    if (id === requestId) marks.value = Object.fromEntries(ids.map((i) => [i, found[String(i)] ?? null]))
  } catch {
    marks.value = {}
  }
}

async function loadOptions() {
  try {
    platforms.value = await get<PlatformCapability[]>('/platforms/capabilities')
  } catch {
    platforms.value = []
  }
  try {
    const map = await get<{ categories: { category: string }[] }>('/skill-map')
    categories.value = (map.categories ?? []).map((c) => c.category)
  } catch {
    categories.value = []
  }
}

watch(
  () => [route.query.platform, route.query.category, route.query.status, route.query.sort, route.query.q, route.query.min, route.query.max, route.query.page],
  () => void load()
)

const listTop = ref<HTMLElement | null>(null)
function goPage(value: number) {
  update({ page: value > 1 ? value : null }, true)
  listTop.value?.scrollIntoView({ block: 'start', behavior: 'smooth' })
}

onMounted(() => {
  void loadOptions()
  void load()
})

/** 题库覆盖率：这个平台的题我做了多少 */
function coverageTitle(row: CatalogRow) {
  const pct = row.catalogCount ? `，${Math.round((row.solvedCount / row.catalogCount) * 1000) / 10}%` : ''
  return `${platformLabel(row.platform)}：题库 ${row.catalogCount} 题，你通过了 ${row.solvedCount} 题${pct}`
}

const TIPS = ['难度和通过人数是各平台公开的数字，只在同一个平台里比较，不跨平台换算。', '「已通过」来自你自己已同步的通过记录；没同步到的题这里也显示没通过。']
</script>

<template>
  <div class="flex flex-col gap-4 md:gap-[18px]" data-test="page">
    <PageHeader title="题库" class="!mb-0">
      <template #description>
        按平台、难度和算法方向找题；绿色对勾是你已经通过的
        <span class="hidden md:inline">
          <InfoTip title="难度和通过人数" label="难度和通过人数怎么看" class="ml-0.5 align-[-3px]">
            <p v-for="(tip, i) in TIPS" :key="i" class="m-0" :class="{ 'mt-1.5': i }">{{ tip }}</p>
          </InfoTip>
        </span>
      </template>
      <template #actions>
        <div v-if="catalog.length" class="flex flex-wrap gap-2" data-test="coverage">
          <span v-for="c in catalog" :key="c.platform" class="coverage-chip" :title="coverageTitle(c)">
            <PlatformLogo :platform="c.platform" :size="20" />
            <span class="sr-only">{{ platformLabel(c.platform) }}：已通过</span>
            <strong class="num text-ink">{{ c.solvedCount }}</strong><span class="num">/ {{ c.catalogCount.toLocaleString('en-US') }}</span>
          </span>
        </div>
      </template>
      <template #mobile-actions>
        <InfoTip title="难度和通过人数" label="难度和通过人数怎么看" topbar>
          <p v-for="(tip, i) in TIPS" :key="i" class="m-0" :class="{ 'mt-1.5': i }">{{ tip }}</p>
        </InfoTip>
      </template>
    </PageHeader>

    <div v-if="isMobile && catalog.length" class="flex flex-wrap gap-2" data-test="coverage-mobile">
      <span v-for="c in catalog" :key="c.platform" class="coverage-chip" :title="coverageTitle(c)">
        <PlatformLogo :platform="c.platform" :size="20" />
        <span class="sr-only">{{ platformLabel(c.platform) }}：已通过</span>
        <strong class="num text-ink">{{ c.solvedCount }}</strong><span class="num">/ {{ c.catalogCount.toLocaleString('en-US') }}</span>
      </span>
    </div>

    <!-- 筛选：关键词、平台 / 方向 / 状态 / 排序、难度区间 -->
    <section class="flex flex-col gap-3.5 border border-line rounded-card border-solid bg-surface p-3.5 md:(px-[18px] py-4)" aria-label="筛选题目" data-test="filters">
      <div class="flex flex-col gap-2.5 lg:(flex-row items-center)">
        <el-input
          v-model="keyword"
          class="search-input lg:flex-1"
          :placeholder="isMobile ? '题号或标题' : '题号或标题，比如 4A 或 Watermelon'"
          aria-label="题号或标题"
          clearable
          data-test="keyword"
          @keyup.enter="searchNow"
        >
          <template #prefix><span class="i-lucide-search text-[17px]" aria-hidden="true" /></template>
        </el-input>
        <div class="min-w-0">
          <FilterBar :model-value="filters" :fields="fields" sheet-title="筛选题目" @update:model-value="setFilters" />
        </div>
      </div>
      <div class="flex items-center gap-3 md:gap-3.5">
        <span id="diff-label" class="shrink-0 text-[13px] text-muted">难度</span>
        <el-slider
          v-model="sliderDraft"
          range
          :min="DIFF_MIN"
          :max="DIFF_MAX"
          :step="100"
          class="min-w-0 flex-1 px-2"
          range-start-label="难度下限"
          range-end-label="难度上限"
          @change="commitRange"
        />
        <span class="num shrink-0 text-[13.5px] text-ink font-semibold" data-test="range-text">{{ rangeText }}</span>
        <button v-if="hasFilters" type="button" class="link-btn shrink-0" data-test="clear-filters" @click="clearAll">清除</button>
      </div>
      <p v-if="data?.onlyRated && range" class="m-0 text-xs text-muted">按难度筛选时，没有评级的题不在结果里。</p>
    </section>

    <section ref="listTop" class="scroll-mt-20 overflow-hidden border border-line rounded-card border-solid bg-surface" aria-label="题目列表" data-test="problems">
      <div v-if="!data" class="flex flex-col gap-3 p-5">
        <Skeleton v-for="i in 8" :key="i" class="h-9 w-full" />
      </div>
      <EmptyState
        v-else-if="catalogEmpty"
        :bordered="false"
        icon="i-lucide-database"
        title="题库还没有同步"
        description="题库来自各平台公开的题目清单，要管理员在管理后台同步一次；同步之前这里是空的，不拿同学做过的题充数。"
      />
      <EmptyState
        v-else-if="!items.length"
        :bordered="false"
        icon="i-lucide-search-x"
        title="没有符合条件的题"
        description="换个难度区间或算法方向试试。"
        :action="hasFilters ? '清除筛选' : undefined"
        @action="clearAll"
      />
      <template v-else>
        <div class="pset-head" aria-hidden="true">
          <span />
          <span>题号</span>
          <span>题目</span>
          <span>算法方向</span>
          <span>难度</span>
          <span class="text-right">通过人数</span>
          <span />
        </div>
        <ol class="m-0 list-none p-0 transition-opacity" :class="{ 'opacity-60': loading }">
          <ProblemRow v-for="row in items" :key="row.id" v-model:mark="marks[row.id]" :row="row" />
        </ol>
        <div class="flex flex-col items-center gap-2 border-t border-line-soft border-t-solid px-4 py-3 md:(flex-row justify-between px-5)">
          <span class="text-[13px] text-muted" data-test="total">
            符合条件 <span class="num">{{ total.toLocaleString('en-US') }}</span> 道 · 第 <span class="num">{{ page }}</span> / <span class="num">{{ pageCount }}</span> 页
          </span>
          <el-pagination
            v-if="pageCount > 1"
            :current-page="page"
            :page-size="PAGE_SIZE"
            :total="Math.min(total, MAX_PAGE * PAGE_SIZE)"
            :layout="isMobile ? 'prev, next' : 'prev, pager, next'"
            :pager-count="7"
            @current-change="goPage"
          />
        </div>
      </template>
      <p v-if="data?.note && items.length" class="m-0 border-t border-line-soft border-t-solid px-4 py-3 text-[12.5px] text-muted leading-[1.6] md:px-5">
        {{ data.note }}<template v-if="total > MAX_PAGE * PAGE_SIZE">最多翻到第 {{ MAX_PAGE }} 页，再往后请缩小筛选范围。</template>
      </p>
    </section>
  </div>
</template>

<style scoped>
.coverage-chip {
  display: inline-flex;
  align-items: center;
  gap: 6px;
  height: 32px;
  padding: 0 12px 0 6px;
  border: 1px solid var(--border);
  border-radius: 999px;
  background: var(--bg-elevated);
  color: var(--text);
  font-size: 13px;
  white-space: nowrap;
  cursor: help;
}

.search-input :deep(.el-input__wrapper) {
  min-height: 40px;
}

.pset-head {
  display: none;
}

@media (min-width: 768px) {
  .pset-head {
    display: grid;
    grid-template-columns: 22px 96px minmax(0, 2fr) minmax(0, 1.4fr) 64px 80px 32px;
    column-gap: 12px;
    align-items: center;
    height: 42px;
    padding: 0 20px;
    border-bottom: 1px solid var(--border-soft);
    background: var(--bg-subtle);
    color: var(--text-muted);
    font-size: 12.5px;
  }
}

.link-btn {
  height: 30px;
  padding: 0 8px;
  border: 0;
  border-radius: 8px;
  background: transparent;
  color: var(--brand-600);
  font: inherit;
  font-size: 13px;
  cursor: pointer;
}

.link-btn:hover {
  background: var(--surface-hover);
}

.link-btn:focus-visible {
  outline: 2px solid var(--brand-500);
  outline-offset: 2px;
}
</style>
