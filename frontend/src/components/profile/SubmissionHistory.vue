<script setup lang="ts">
import { computed, onBeforeUnmount, onMounted, ref, watch } from 'vue'
import { get, notifyError } from '@/api/client'
import DifficultyTag from '@/components/DifficultyTag.vue'
import PlatformLogo from '@/components/PlatformLogo.vue'
import TopBarActions from '@/components/layout/TopBarActions.vue'
import FilterBar from '@/components/ui/FilterBar.vue'
import InfoTip from '@/components/ui/InfoTip.vue'
import Skeleton from '@/components/ui/Skeleton.vue'
import StatusBadge from '@/components/ui/StatusBadge.vue'
import type { FilterField, FilterValues } from '@/components/ui/types'
import { useBreakpoint } from '@/composables/useBreakpoint'
import { downloadHistoryCsv } from '@/utils/historyExport'
import { platformName } from '@/utils/platformBrand'
import { problemCodeBare } from '@/utils/problemLabel'
import { beijingClock, beijingTime, campusDate, dayTime, shiftDate } from '@/utils/statistics'
import { verdictOf } from '@/utils/verdict'
import HistoryDateFilter from './HistoryDateFilter.vue'

/**
 * 做题记录：本人的每次通过提交，加上没有提交明细的已通过题目（洛谷题单、Tracker），新的在前，时间是北京时间。
 * 同一题重复通过会保留每次提交。电脑上是表格，一页 15 条；手机上按天分组的卡片，「加载更早的记录」往下接。
 * 筛选平台、日期；
 * 导出 CSV（可带原始标签）按当前筛选导出全部，最多 10000 条。从概览点某一天进来时按那天筛好。
 */
interface Row {
  id: number
  platform: string
  title: string | null
  problemKey: string | null
  url: string | null
  verdict: string
  accepted: boolean | null
  submittedAt: string | null
  timePrecision?: string
  tags: string | null
  recordKind: 'SUBMISSION' | 'SOLVED'
  difficulty: string | null
}

const props = defineProps<{ selectedDate?: string; selectedPlatform?: string; userId?: number; publicPlatforms?: string[] }>()
/** 日期不再是带进来的那一天（看全部、改了日期）时清掉，档案页的每日记录也不再圈着那天 */
const emit = defineEmits<{ 'update:selectedDate': [value: string] }>()

const { isMobile } = useBreakpoint()
/** 电脑一页 15 条；手机每次多读 20 条 */
const pageSize = computed(() => (isMobile.value ? 20 : 15))
const rows = ref<Row[]>([])
const total = ref(0)
const page = ref(1)
const loaded = ref(false)
const filters = ref<FilterValues>({ platform: props.selectedPlatform ?? '' })
const dates = ref<string[]>(props.selectedDate ? [props.selectedDate, props.selectedDate] : [])
const busy = ref(false)
const loadingMore = ref(false)
const exporting = ref(false)
const loadError = ref('')
const platforms = ref<string[]>([])
let refreshTimer: ReturnType<typeof setInterval> | undefined
let requestId = 0

/** 平台选项：已绑定的平台；从概览带过来的平台不在里面时也列上，免得筛选片写着「全部平台」却只看一个 */
const platformOptions = computed(() => {
  const current = filters.value.platform
  return [...new Set([...platforms.value, ...(typeof current === 'string' && current ? [current] : [])])]
})
const fields = computed<FilterField[]>(() => [
  {
    key: 'platform',
    label: '平台',
    defaultValue: '',
    display: 'pills',
    options: [{ label: '全部平台', value: '' }, ...platformOptions.value.map((p) => ({ label: platformName(p), value: p }))]
  }
])

function params() {
  return {
    platform: filters.value.platform ?? '',
    accepted: true,
    from: dates.value[0],
    to: dates.value[1]
  }
}

/** append：手机上「加载更早的记录」，接在后面；否则换成这一页 */
async function load(append = false) {
  const request = ++requestId
  if (append) loadingMore.value = true
  else busy.value = true
  loadError.value = ''
  try {
    const data = await get<{ rows: Row[]; total: number }>(props.userId ? `/history/user/${props.userId}` : '/history', { ...params(), page: page.value, size: pageSize.value })
    if (request !== requestId) return
    rows.value = append ? [...rows.value, ...data.rows] : data.rows
    total.value = data.total
    loaded.value = true
  } catch (e) {
    if (request !== requestId) return
    if (append) {
      page.value -= 1
      notifyError(e)
    } else {
      rows.value = []
      total.value = 0
      loadError.value = e instanceof Error ? e.message : '做题记录加载失败，请重试'
    }
  } finally {
    if (request === requestId) {
      busy.value = false
      loadingMore.value = false
    }
  }
}
function search() {
  page.value = 1
  void load()
}
function loadMore() {
  page.value += 1
  void load(true)
}
watch([filters, dates, isMobile], search)
watch(dates, (value) => {
  const day = props.selectedDate
  if (day && !(value.length === 2 && value[0] === day && value[1] === day)) emit('update:selectedDate', '')
})
watch(
  () => [props.selectedDate, props.selectedPlatform],
  () => {
    dates.value = props.selectedDate ? [props.selectedDate, props.selectedDate] : []
    filters.value = { platform: props.selectedPlatform ?? '' }
  }
)

async function exportCsv(includeTags: boolean) {
  if (exporting.value) return
  exporting.value = true
  try {
    await downloadHistoryCsv({ ...params(), includeTags })
  } catch (e) {
    notifyError(e)
  } finally {
    exporting.value = false
  }
}
const onExport = (command: string) => exportCsv(command === 'tags')

const safeUrl = (url: string | null) => (url && /^https?:\/\//i.test(url) ? url : undefined)
const label = (row: Row) => {
  const { code, name } = problemCodeBare({ platform: row.platform, problemKey: row.problemKey ?? '', title: row.title })
  return { code, name: name || row.problemKey || '题目信息缺失' }
}
const verdict = (row: Row) => verdictOf(row.verdict, row.accepted)
const kindText = (row: Row) => (row.recordKind === 'SOLVED' ? '已通过题目' : '逐次提交')
const KIND_TIP = {
  SOLVED: '源站只提供「已通过」这一条（如洛谷题单、Tracker），没有逐次提交；时间是已知的最早通过时间，不知道时写「时间未知」。',
  SUBMISSION: '源站的已通过提交记录，每次通过各占一行；同一题重复通过不会合并。'
}
const singleDay = computed(() => (dates.value.length === 2 && dates.value[0] === dates.value[1] ? dates.value[0] : ''))
const countText = computed(() => total.value.toLocaleString('zh-CN'))

/** 手机：按北京时间的日子分组，「今天 · 9月28日」「昨天 · 9月27日」「9月25日 周五」，没有时间的放最后 */
const groups = computed(() => {
  const today = campusDate()
  const list: { key: string; title: string; rows: Row[] }[] = []
  for (const row of rows.value) {
    const key = row.submittedAt ? campusDate(new Date(row.submittedAt)) : 'unknown'
    let group = list.at(-1)
    if (!group || group.key !== key) {
      group = { key, title: dayTitle(key, today), rows: [] }
      list.push(group)
    }
    group.rows.push(row)
  }
  return list
})
function dayTitle(key: string, today: string) {
  if (key === 'unknown') return '时间未知'
  const [year, month, day] = key.split('-').map(Number)
  const monthDay = `${month}月${day}日`
  if (key === today) return `今天 · ${monthDay}`
  if (key === shiftDate(today, -1)) return `昨天 · ${monthDay}`
  const weekday = '日一二三四五六'[new Date(`${key}T00:00:00Z`).getUTCDay()]
  return `${key.slice(0, 4) === today.slice(0, 4) ? monthDay : `${year}年${monthDay}`} 周${weekday}`
}

const emptyText = computed(() => {
  if (loadError.value) return '加载失败，请重试；这不代表你没有做题记录'
  if (dates.value.length) return '这段日期没有记录。洛谷题单这类没有时间的记录，清除日期后才能看到。'
  return '当前筛选下还没有记录。'
})

onMounted(async () => {
  refreshTimer = setInterval(() => {
    if (page.value === 1 && !busy.value && !loadingMore.value && !document.hidden) void load()
  }, 60000)
  void load()
  if (props.userId) { platforms.value = props.publicPlatforms ?? []; return }
  try {
    const accounts = await get<{ platform: string }[]>('/accounts')
    platforms.value = [...new Set(accounts.map((a) => a.platform))]
  } catch (e) {
    notifyError(e)
  }
})
onBeforeUnmount(() => {
  clearInterval(refreshTimer)
  ++requestId
})
</script>

<template>
  <section class="history" data-test="history">
    <div class="hidden items-center justify-between gap-3 md:flex">
      <h2 class="m-0 flex items-center gap-1 text-base text-ink font-semibold">
        做题记录
        <InfoTip title="这个列表包含什么">
          <p class="m-0">只显示已通过的记录。每次通过提交各占一行，不按题目去重；没有提交明细的平台按已通过题目展示。源站没给时间的写「时间未知」，不代表那天通过。</p>
          <p class="m-0 mt-1.5">有时间的按时间从新到旧，时间未知的排在最后；设了日期会排除时间未知的记录。每分钟自动刷新第一页。</p>
          <p class="m-0 mt-1.5">导出 CSV 按当前筛选导出全部（最多 10000 条），时间为 UTC，含记录类型。</p>
        </InfoTip>
      </h2>
      <div class="flex items-center gap-2">
        <el-button :loading="busy" aria-label="刷新记录" title="刷新记录" @click="search">
          <span v-if="!busy" class="i-lucide-refresh-cw text-[15px]" aria-hidden="true" />
        </el-button>
        <el-dropdown v-if="!userId" trigger="click" @command="onExport">
          <el-button :loading="exporting" data-test="history-export">
            <span v-if="!exporting" class="i-lucide-download mr-1.5 text-[15px]" aria-hidden="true" />导出 CSV<span class="i-lucide-chevron-down ml-1 text-sm" aria-hidden="true" />
          </el-button>
          <template #dropdown>
            <el-dropdown-menu>
              <el-dropdown-item command="plain">导出 CSV</el-dropdown-item>
              <el-dropdown-item command="tags">导出 CSV（含原始标签）</el-dropdown-item>
            </el-dropdown-menu>
          </template>
        </el-dropdown>
      </div>
    </div>

    <div class="flex flex-col gap-2 md:(flex-row items-center justify-between gap-3)">
      <FilterBar v-model="filters" :fields="fields" sheet-title="筛选做题记录">
        <HistoryDateFilter v-model="dates" />
      </FilterBar>
      <div class="flex items-center gap-1 text-[12.5px] text-muted">
        <span data-test="history-count">共 <span class="num">{{ countText }}</span> 条 · 新的在前 · 北京时间</span>
        <!-- 手机上说明和刷新放在这一行 -->
        <span class="md:hidden"><InfoTip title="这个列表包含什么">
          <p class="m-0">只显示已通过的记录，同一题多次通过不会合并。没有提交明细的平台按已通过题目展示；时间未知的放在最后，设了日期会排除它们。</p>
          <p class="m-0 mt-1.5">导出 CSV 按当前筛选导出全部（最多 10000 条），时间为 UTC。</p>
        </InfoTip></span>
        <button type="button" class="refresh-link md:hidden" :disabled="busy" @click="search">刷新</button>
      </div>
    </div>

    <p v-if="singleDay" class="m-0 flex flex-wrap items-center gap-x-2 text-[13px] text-body" data-test="history-day">
      正在查看 {{ singleDay }} 的记录
      <button type="button" class="refresh-link" @click="dates = []">看全部记录</button>
    </p>

    <el-alert v-if="loadError" :title="loadError" type="error" :closable="false" show-icon>
      <el-button text :loading="busy" @click="search">重新加载</el-button>
    </el-alert>

    <!-- 电脑：表格 -->
    <template v-if="!isMobile">
      <el-table v-loading="busy" :data="rows" row-key="id" class="history-table">
        <el-table-column label="题目" min-width="184">
          <template #default="{ row }">
            <div class="flex min-w-0 items-center gap-2.5">
              <PlatformLogo :platform="row.platform" :size="20" :title="platformName(row.platform)" />
              <span class="mono code">{{ label(row as Row).code }}</span>
              <a v-if="safeUrl(row.url)" :href="safeUrl(row.url)" target="_blank" rel="noopener noreferrer" class="title text-ink hover:text-brand-600">{{ label(row as Row).name }}</a>
              <span v-else class="title text-ink">{{ label(row as Row).name }}</span>
            </div>
            <div v-if="row.tags" class="tags" :title="row.tags">{{ row.tags }}</div>
          </template>
        </el-table-column>
        <el-table-column label="难度" width="104">
          <template #default="{ row }">
            <DifficultyTag v-if="row.difficulty" :platform="row.platform" :label="row.difficulty" />
            <span v-else class="text-muted">—</span>
          </template>
        </el-table-column>
        <el-table-column label="结果" width="116">
          <template #default="{ row }">
            <StatusBadge variant="label" :tone="verdict(row as Row).tone" :title="`平台原文：${row.verdict}`" data-test="verdict">{{ verdict(row as Row).label }}</StatusBadge>
          </template>
        </el-table-column>
        <el-table-column label="时间" width="116">
          <template #default="{ row }">
            <time v-if="row.submittedAt" :datetime="row.submittedAt" :title="beijingTime(row.submittedAt)" class="num whitespace-nowrap">{{ dayTime(row.submittedAt) }}</time>
            <span v-else class="text-muted">时间未知</span>
          </template>
        </el-table-column>
        <el-table-column label="类型" width="124">
          <template #default="{ row }">
            <span class="whitespace-nowrap text-[12.5px] text-muted" :title="KIND_TIP[(row as Row).recordKind]">{{ kindText(row as Row) }}</span>
          </template>
        </el-table-column>
        <template #empty>
          <div class="flex flex-col items-center gap-2 py-4">
            <span>{{ emptyText }}</span>
            <el-button v-if="dates.length && !loadError" size="small" @click="dates = []">清除日期</el-button>
          </div>
        </template>
      </el-table>
      <el-pagination
        v-if="total > pageSize"
        v-model:current-page="page"
        :page-size="pageSize"
        :total="total"
        layout="total, prev, pager, next"
        :pager-count="5"
        class="justify-end"
        @current-change="() => load()"
      />
    </template>

    <!-- 手机：按天分组的卡片 -->
    <template v-else>
      <div v-if="!loaded && busy" class="flex flex-col gap-3" aria-busy="true">
        <div v-for="n in 3" :key="n" class="flex flex-col gap-2.5 border border-line rounded-card border-solid bg-surface p-4">
          <Skeleton class="h-4 w-1/2" />
          <Skeleton class="h-3 w-4/5" />
        </div>
      </div>
      <div v-else-if="!rows.length && !loadError" class="flex flex-col items-center gap-3 border border-line rounded-card border-solid bg-surface px-4 py-8 text-center text-[13.5px] text-muted">
        {{ emptyText }}
        <el-button v-if="dates.length" @click="dates = []">清除日期</el-button>
      </div>
      <div v-else class="flex flex-col gap-4" :class="{ 'opacity-60': busy }">
        <section v-for="group in groups" :key="group.key" class="flex flex-col gap-1.5" data-test="history-group">
          <div class="flex items-baseline justify-between px-0.5">
            <h3 class="m-0 text-[13.5px] text-ink font-semibold">{{ group.title }}</h3>
            <span class="text-xs text-muted">{{ group.rows.length }} 条</span>
          </div>
          <ul class="m-0 list-none overflow-hidden border border-line rounded-card border-solid bg-surface p-0">
            <li v-for="row in group.rows" :key="row.id" class="card-row">
              <component
                :is="safeUrl(row.url) ? 'a' : 'div'"
                :href="safeUrl(row.url)"
                :target="safeUrl(row.url) ? '_blank' : undefined"
                :rel="safeUrl(row.url) ? 'noopener noreferrer' : undefined"
                class="card-link"
              >
                <PlatformLogo :platform="row.platform" :size="26" :title="platformName(row.platform)" />
                <span class="min-w-0 flex flex-1 flex-col gap-0.5">
                  <span class="truncate text-[14.5px] text-ink">{{ label(row).name }}</span>
                  <span class="mono truncate text-xs text-muted">{{ label(row).code }} · <span class="num">{{ row.submittedAt ? beijingClock(row.submittedAt) : kindText(row) }}</span></span>
                </span>
                <StatusBadge variant="label" :tone="verdict(row).tone" :title="`平台原文：${row.verdict}`" class="shrink-0" data-test="verdict">{{ verdict(row).label }}</StatusBadge>
              </component>
            </li>
          </ul>
        </section>
        <el-button v-if="rows.length < total" size="large" class="w-full" :loading="loadingMore" @click="loadMore">加载更早的记录</el-button>
        <p v-else-if="total > pageSize" class="m-0 text-center text-xs text-muted">没有更早的记录了</p>
      </div>
      <TopBarActions v-if="!userId">
        <el-dropdown trigger="click" @command="onExport">
          <button type="button" class="topbar-icon" aria-label="导出 CSV" :disabled="exporting">
            <span class="i-lucide-download text-xl" aria-hidden="true" />
          </button>
          <template #dropdown>
            <el-dropdown-menu>
              <el-dropdown-item command="plain">导出 CSV</el-dropdown-item>
              <el-dropdown-item command="tags">导出 CSV（含原始标签）</el-dropdown-item>
            </el-dropdown-menu>
          </template>
        </el-dropdown>
      </TopBarActions>
    </template>
  </section>
</template>

<style scoped>
.history {
  display: flex;
  flex-direction: column;
  gap: 12px;
}

@media (min-width: 768px) {
  .history {
    gap: 14px;
    padding: 20px 22px;
    border: 1px solid var(--border);
    border-radius: var(--radius);
    background: var(--bg-elevated);
  }
}

.code {
  flex-shrink: 0;
  min-width: 64px;
  color: var(--brand-600);
  font-size: 12.5px;
  white-space: nowrap;
}

.title {
  min-width: 0;
  overflow: hidden;
  font-size: 13.5px;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.tags {
  margin: 2px 0 0 30px;
  overflow: hidden;
  color: var(--text-muted);
  font-size: 12px;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.refresh-link {
  padding: 0;
  border: 0;
  background: none;
  color: var(--brand-600);
  font: inherit;
  cursor: pointer;
}

.refresh-link:disabled {
  color: var(--text-faint);
  cursor: default;
}

.card-row + .card-row {
  border-top: 1px solid var(--border-soft);
}

.card-link {
  display: flex;
  align-items: center;
  gap: 12px;
  min-height: 60px;
  padding: 10px 14px;
  color: inherit;
  text-decoration: none;
}

.card-link:focus-visible {
  outline: 2px solid var(--brand-500);
  outline-offset: -2px;
}
</style>
