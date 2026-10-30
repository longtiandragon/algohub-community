<script setup lang="ts">
import { computed, onBeforeUnmount, onMounted, ref, watch } from 'vue'
import { get, post, notifyError } from '@/api/client'
import type { HeatmapData, PlatformCapability } from '@/api/types'
import CampusWeekCard from '@/components/activity/CampusWeekCard.vue'
import FeedGroups from '@/components/activity/FeedGroups.vue'
import StreakCard from '@/components/activity/StreakCard.vue'
import type { BookmarkMark, FeedItem } from '@/components/activity/types'
import WeekOverview from '@/components/activity/WeekOverview.vue'
import BottomSheet from '@/components/ui/BottomSheet.vue'
import ChipSelect from '@/components/ui/ChipSelect.vue'
import InfoTip from '@/components/ui/InfoTip.vue'
import PageHeader from '@/components/ui/PageHeader.vue'
import WeeklyGoalPanel from '@/components/WeeklyGoalPanel.vue'
import { useBreakpoint } from '@/composables/useBreakpoint'
import { useEnrollmentYears } from '@/composables/useEnrollmentYears'
import { useAuthStore } from '@/stores/auth'
import { platformName } from '@/utils/platformBrand'
import { campusDate, shiftDate } from '@/utils/statistics'

/**
 * 校内动态：同学们最近在练什么。
 * 电脑左边是动态（按天分组、日期行吸顶），右边是近 7 天校内、我的连续练习、本周目标、今日一题；
 * 手机上是本周概况 → 今日一题 → 筛选 → 动态。可以只看同届（按学校登记的入学年级）和某个平台。
 * 动态用游标翻页，「加载更早的动态」接在后面；每分钟自动刷新第一页，翻过页或标签页在后台时不刷新。
 */
interface FeedResponse {
  items: FeedItem[]
  nextCursor: string | null
  hasMore: boolean
  note: string
}
interface ActivitySummary {
  activeUsers: number
  totalEvents: number
  latestAt: string | null
  windowDays: number
  note: string
  /** 动态里各平台多少条、最近一条（不限时间） */
  platforms?: { platform: string; events: number; latestAt: string | null }[]
  /** 绑了但拿不到通过时间、不会出现在动态里的平台 */
  untimedPlatforms?: string[]
}
interface StreakInfo {
  currentStreak: number
  longestStreak: number
  activeDays: number
  solvedToday: number
  note: string
}

const auth = useAuthStore()
/** 1024 起右边一列；平板和手机一样，本周概况和今日一题放在动态上面 */
const { isMobile, lg: wide } = useBreakpoint()
const PAGE_SIZE = 30

const { years: availableYears } = useEnrollmentYears()
const scope = ref<'PUBLIC' | 'SCHOOL'>('PUBLIC')
const followingOnly = ref(false)
const selectedYears = ref<number[]>([])
const year = computed(() => selectedYears.value.length === 1 ? selectedYears.value[0] : null)
const yearLabel = computed(() => selectedYears.value.length ? selectedYears.value.map(y => `${y} 级`).join('、') : '全部年级')
const publicSaving = ref(false)
async function togglePublic(value: boolean) {
  publicSaving.value = true
  try {
    await post('/me/privacy', { publicActivity: value })
    await auth.refreshMe()
    refresh()
  } catch (e) { notifyError(e) }
  finally { publicSaving.value = false }
}
const platform = ref('')
const platforms = ref<PlatformCapability[]>([])
const platformOptions = computed(() => [{ label: '全部平台', value: '' }, ...platforms.value.map((p) => ({ label: p.displayName, value: p.platform }))])

const summary = ref<ActivitySummary | null>(null)
const streak = ref<StreakInfo | null>(null)
const weekDays = ref<Record<string, number> | null>(null)
const items = ref<FeedItem[]>([])
const cursor = ref<string | null>(null)
const hasMore = ref(false)
const loading = ref(false)
const loaded = ref(false)
const note = ref('')
const appendedPages = ref(false)
/** 当前列表里这些题的收藏状态，每次加载后一次取回。 */
const marks = ref<Record<number, BookmarkMark>>({})
const coverage = ref<{ platform: string; untimedCount: number; solvedCount: number }[]>([])
/** 动态里出现过的平台（只增不减，换筛选也保留），和自己账号的覆盖情况一起算「哪些平台有通过时间」 */

// 相对时间需要随时间自然变化，这里用一个低频心跳驱动模板重新计算。
const now = ref(Date.now())
let timer: number | undefined

async function loadPlatforms() {
  try {
    platforms.value = await get<PlatformCapability[]>('/platforms/capabilities')
  } catch (e) {
    notifyError(e)
  }
}
let summaryRequest = 0
async function loadSummary() {
  const request = ++summaryRequest
  try {
    const data = await get<ActivitySummary>('/activity/summary', { scope: scope.value, years: selectedYears.value.join(',') || undefined })
    if (request === summaryRequest) summary.value = data
  } catch (e) {
    notifyError(e)
  }
}
async function loadStreak() {
  try {
    streak.value = await get<StreakInfo>('/activity/streak')
  } catch (e) {
    notifyError(e)
  }
}
/** 最近 7 天自己每天的首次通过（连续练习的七个圆点） */
async function loadWeekDays() {
  const today = campusDate()
  try {
    const data = await get<HeatmapData>('/stats/heatmap', { from: shiftDate(today, -6), to: today })
    weekDays.value = data.metrics.FIRST_AC ?? {}
  } catch {
    weekDays.value = null
  }
}
async function loadCoverage() {
  try {
    coverage.value = await get('/activity/coverage')
  } catch {
    coverage.value = []
  }
}

/** append=true 时携带上一页的 nextCursor 继续往后取，绝不按 offset 翻页。 */
let feedRequest = 0
async function loadFeed(append = false) {
  if (append && loading.value) return
  if (append && !cursor.value) return
  const request = ++feedRequest
  loading.value = true
  try {
    const data = await get<FeedResponse>('/activity/feed', {
      platform: platform.value || undefined,
      scope: scope.value,
      followingOnly: scope.value === 'PUBLIC' && followingOnly.value,
      years: selectedYears.value.join(',') || undefined,
      cursor: append ? cursor.value ?? undefined : undefined,
      limit: PAGE_SIZE
    })
    if (request !== feedRequest) return
    const incoming = data.items ?? []
    items.value = append ? items.value.concat(incoming) : incoming
    appendedPages.value = append
    cursor.value = data.nextCursor
    hasMore.value = data.hasMore
    note.value = data.note ?? ''
    loaded.value = true
    await loadMarks(request)
  } catch (e) {
    if (request === feedRequest) notifyError(e)
  } finally {
    if (request === feedRequest) loading.value = false
  }
}

/** 一次取回列表里所有题目的收藏状态；取不到就当作未收藏，不影响看动态。 */
async function loadMarks(request = feedRequest) {
  const ids = [...new Set(items.value.map((i) => i.problemId).filter(Boolean))]
  if (!ids.length) {
    marks.value = {}
    return
  }
  try {
    const found = await get<Record<string, 'LATER' | 'REVIEW' | 'DONE'>>('/bookmarks/status', { problemIds: ids.join(',') })
    const next: Record<number, BookmarkMark> = {}
    for (const id of ids) next[id] = found[String(id)] ?? null
    if (request === feedRequest) marks.value = next
  } catch {
    if (request === feedRequest) marks.value = {}
  }
}
function setMark(problemId: number, value: BookmarkMark) {
  marks.value = { ...marks.value, [problemId]: value }
}

function refresh() {
  void loadCoverage()
  cursor.value = null
  void loadFeed(false)
  void loadSummary()
  void loadStreak()
  void loadWeekDays()
}

// 改范围或平台后已有的分页作废，从第一页重新取；范围也影响近 7 天的数
watch([platform, scope, followingOnly, () => selectedYears.value.join(',')], () => {
  cursor.value = null
  items.value = []
  marks.value = {}
  hasMore.value = false
  loaded.value = false
  appendedPages.value = false
  void loadFeed(false)
  void loadSummary()
})

onMounted(() => {
  void loadCoverage()
  void loadPlatforms()
  void loadSummary()
  void loadStreak()
  void loadWeekDays()
  void loadFeed(false)
  timer = window.setInterval(() => {
    now.value = Date.now()
    // 翻页阅读时保留当前位置，隐藏标签页也不轮询。
    if (document.visibilityState === 'visible' && !appendedPages.value && !loading.value) refresh()
  }, 60000)
})

onBeforeUnmount(() => {
  ++feedRequest
  if (timer) window.clearInterval(timer)
})

/** 有通过题却没有通过时间、因此不在时间线上的平台（写进页头的 ⓘ） */
const untimed = computed(() => coverage.value.filter((row) => Number(row.untimedCount) > 0 && (!platform.value || row.platform === platform.value)))
const platformLabel = (value: string) => platforms.value.find((p) => p.platform === value)?.displayName ?? value
function coverageReason(value: string) {
  if (value === 'LUOGU') return '洛谷公开题单没有通过时间；到「平台账号 → 洛谷」完成浏览器评测同步后，按每题最早 AC 出现在动态里。'
  if (value === 'NOWCODER') return '部分牛客或 Tracker 数据只提供通过题目，没有评测时间；这些题目保留在做题记录里，但不编入某一天的动态。'
  return '这些通过题目已保留，但还没有取得评测时间；只有通过题单或总量时，推算不出首次通过日期。'
}
/** 页头 ⓘ 的几段说明（电脑在页头右边，手机在顶栏） */
const tips = computed(() => [
  '只含已验证归属的账号；公开动态展示开启公开开关的同学，校内动态展示本校同学。关闭排行展示或档案仅自己可见的同学不出现。',
  '时间是已知最早的通过时间，历史还没补全时会标「最早已知时间」。自己的全部提交（含未通过）在个人档案的「做题记录」里。',
  ...untimed.value.map((row) => `${platformLabel(row.platform)}：你已收录的 ${row.solvedCount} 道通过题中有 ${row.untimedCount} 道没有通过时间，不在这条时间线上。${coverageReason(row.platform)}`)
])

/**
 * 动态只收有通过时间的题，按时间倒序排在一起、不分平台：时间较早的平台（如牛客、力扣）要往下翻才看得到。
 * 哪些平台会出现、各多少条，用后端按全部动态算好的统计（/activity/summary 的 platforms），不看已经加载的这几页。
 * 自己账号里只有一部分题有时间的平台（力扣只公开最近的通过）标「部分题」。
 */
const timing = computed(() => {
  const inFeed = summary.value?.platforms ?? []
  const partial = inFeed
    .map((row) => row.platform)
    .filter((p) => coverage.value.some((row) => row.platform === p && Number(row.untimedCount) > 0 && Number(row.solvedCount) > Number(row.untimedCount)))
  return { timed: inFeed, partial, untimed: summary.value?.untimedPlatforms ?? [] }
})
/** 提示里用短名：「力扣（中国站）」写成「力扣」 */
const shortName = (value: string) => (platforms.value.find((p) => p.platform === value)?.displayName ?? platformName(value)).replace(/（.*）$/, '')
const joinNames = (list: string[]) => list.map(shortName).join('、')
const emptyText = computed(() => {
  if (platform.value && timing.value.untimed.includes(platform.value)) {
    return `${shortName(platform.value)}查不到通过时间，它的题不会出现在动态里；可以在个人档案的「做题记录」里看。`
  }
  if (scope.value === 'PUBLIC' && followingOnly.value) return '当前筛选下，关注的同学暂无可见的通过动态。可以先到同学档案添加关注，或调整平台和年级筛选。'
  const who = year.value ? `${year.value} 级的同学` : '同学'
  const where = platform.value ? `在${platformLabel(platform.value)}上` : ''
  return `${who}最近${where}还没有同步到新的通过记录，也可能大家关闭了排行展示。`
})

/** 手机：点概况里的本周目标，在底部弹层里看和改 */
const goalSheet = ref(false)
const overview = ref<InstanceType<typeof WeekOverview>>()
</script>

<template>
  <div class="page" data-test="page">
    <PageHeader title="公开动态">
      <template v-if="!isMobile" #description>看看同学们最近通过了哪些题，可切换公开或校内范围。</template>
      <template #actions>
        <InfoTip title="动态包含什么" label="动态包含什么" text="包含什么">
          <p v-for="(tip, i) in tips" :key="i" class="m-0" :class="{ 'mt-1.5': i }">{{ tip }}</p>
        </InfoTip>
      </template>
      <template #mobile-actions>
        <InfoTip title="动态包含什么" label="动态包含什么" topbar>
          <p v-for="(tip, i) in tips" :key="i" class="m-0" :class="{ 'mt-1.5': i }">{{ tip }}</p>
        </InfoTip>
      </template>
    </PageHeader>

    <div class="flex flex-wrap items-center gap-3 text-sm">
      <span>公开我的动态</span>
      <el-switch :model-value="auth.me?.privacy.publicActivity ?? true" :loading="publicSaving" aria-label="公开我的动态" @change="togglePublic(Boolean($event))" />
      <span class="text-xs text-muted">关闭后仅展示在校内，原有隐私限制仍生效</span>
    </div>
    <p v-if="scope === 'SCHOOL' && !auth.approvedMembership" class="text-sm text-muted">加入学校后可查看校内动态。<RouterLink to="/select-school">选择学校</RouterLink></p>
    <div class="grid grid-cols-1 items-start gap-4 lg:grid-cols-[minmax(0,1fr)_340px] xl:grid-cols-[minmax(0,1fr)_360px]">
      <div class="min-w-0 flex flex-col gap-4">
        <!-- 手机、平板：本周概况、今日一题在动态上面 -->
        <template v-if="!wide">
          <WeekOverview ref="overview" :summary="summary" :streak="streak" :year="year" :scope-label="`${scope === 'PUBLIC' ? '公开' : '校内'} · ${yearLabel}`" @goal="goalSheet = true" />
        </template>

        <div class="flex flex-wrap items-center gap-2" data-test="feed-filters">
          <div role="radiogroup" aria-label="动态范围" class="flex gap-2">
            <button v-for="tab in [{ value: 'PUBLIC' as const, label: '公开动态' }, { value: 'SCHOOL' as const, label: '校内动态' }]" :key="tab.value" type="button" role="radio" class="filter-chip" :class="{ 'is-active': scope === tab.value }" :aria-checked="scope === tab.value" @click="scope = tab.value">{{ tab.label }}</button>
          </div>
          <el-select v-model="selectedYears" multiple collapse-tags collapse-tags-tooltip clearable placeholder="全部年级" aria-label="筛选年级（多选）" class="!w-56">
            <el-option v-for="y in availableYears" :key="y" :label="`${y} 级`" :value="y" />
          </el-select>
          <ChipSelect v-model="platform" :options="platformOptions" label="平台" default-value="" />
          <el-checkbox v-if="scope === 'PUBLIC'" v-model="followingOnly">只看我关注的人</el-checkbox>
          <el-button v-if="!isMobile" text :loading="loading" class="!ml-auto" @click="refresh">
            <span v-if="!loading" class="i-lucide-refresh-cw mr-1.5" aria-hidden="true" />刷新
          </el-button>
        </div>

        <!-- 哪些平台能进动态：放在筛选条下面，手机上也看得到，免得以为别的平台没同步 -->
        <p class="timing-hint" data-test="feed-timing-hint">
          <span class="i-lucide-clock mt-0.5 shrink-0 text-sm" aria-hidden="true" />
          <span>
            动态只收能查到通过时间的题，按时间排在一起<template v-if="timing.timed.length">，现在有：<template
              v-for="(row, i) in timing.timed"
              :key="row.platform"
            ><button type="button" class="hint-link" :aria-label="`只看${shortName(row.platform)}的动态`" @click="platform = row.platform">{{ shortName(row.platform) }} <span class="num">{{ row.events }}</span></button><template v-if="i < timing.timed.length - 1">、</template></template>（点平台名只看它，时间早的要往下翻）</template>。<template v-if="timing.partial.length">{{ joinNames(timing.partial) }}只有部分题有时间。</template><template v-if="timing.untimed.length">{{ joinNames(timing.untimed) }}查不到通过时间，不会出现在这里，但照样算进个人档案的通过数<template v-if="timing.untimed.includes('LUOGU')">；洛谷到<RouterLink to="/luogu-sync">浏览器同步</RouterLink>补上时间后就会出现</template>。</template>
          </span>
        </p>

        <FeedGroups
          :items="items"
          :marks="marks"
          :me-id="auth.me?.id"
          :now="now"
          :loading="loading"
          :loaded="loaded"
          :has-more="hasMore"
          :empty-text="emptyText"
          @more="loadFeed(true)"
          @mark="setMark"
        />
        <p class="m-0 text-xs text-muted">每分钟自动刷新；加载了更早的动态后暂停自动刷新，免得看着看着跳走。</p>
      </div>

      <!-- 电脑：右边一列（比一屏高，不吸顶） -->
      <aside v-if="wide" class="min-w-0 flex flex-col gap-4">
        <CampusWeekCard :summary="summary" :year="year" :scope-label="`${scope === 'PUBLIC' ? '公开' : '校内'} · ${yearLabel}`" />
        <StreakCard :streak="streak" :days="weekDays" />
        <WeeklyGoalPanel />
      </aside>
    </div>

    <BottomSheet v-if="!wide" v-model="goalSheet" title="本周目标">
      <WeeklyGoalPanel flat @changed="overview?.reload()" />
    </BottomSheet>
  </div>
</template>

<style scoped>
.hint-link {
  padding: 0;
  border: 0;
  background: none;
  color: var(--brand-600);
  font: inherit;
  cursor: pointer;
}

.hint-link:hover {
  text-decoration: underline;
}

.hint-link:focus-visible {
  border-radius: 4px;
  outline: 2px solid var(--brand-500);
  outline-offset: 1px;
}

.timing-hint {
  display: flex;
  gap: 6px;
  margin: 0;
  padding: 8px 12px;
  border-radius: 10px;
  background: var(--bg-subtle);
  color: var(--text-muted);
  font-size: 12.5px;
  line-height: 1.6;
}
</style>
