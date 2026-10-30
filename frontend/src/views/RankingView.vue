<script setup lang="ts">
import { computed, nextTick, onBeforeUnmount, onMounted, ref, watch } from 'vue'
import { useRouter } from 'vue-router'
import { get, notifyError } from '@/api/client'
import type { PlatformCapability, Ranking } from '@/api/types'
import MyPosition from '@/components/ranking/MyPosition.vue'
import RankingCoverage from '@/components/ranking/RankingCoverage.vue'
import RankingList from '@/components/ranking/RankingList.vue'
import RankingPodium from '@/components/ranking/RankingPodium.vue'
import EmptyState from '@/components/ui/EmptyState.vue'
import FilterBar from '@/components/ui/FilterBar.vue'
import InfoTip from '@/components/ui/InfoTip.vue'
import PageHeader from '@/components/ui/PageHeader.vue'
import SegmentedControl from '@/components/ui/SegmentedControl.vue'
import Skeleton from '@/components/ui/Skeleton.vue'
import type { FilterField, FilterValues } from '@/components/ui/types'
import { useEnrollmentYears } from '@/composables/useEnrollmentYears'
import { useBreakpoint } from '@/composables/useBreakpoint'
import { useAccounts } from '@/stores/accounts'
import { useAuthStore } from '@/stores/auth'
import { platformName } from '@/utils/platformBrand'

/**
 * 校内排行：我在同届排第几？离前一名差多少？
 * 页头一句话说清范围；分段（近 7 天 / 近 30 天 / 近一年 / 累计）+ 筛选片（年级、平台），手机上筛选进底部弹层。
 * 电脑两栏：左边前三名领奖台 + 榜单（分数条），右边「我的位置」吸顶 + 统计了哪些平台；窄屏单列，手机上「我的位置」在最上面，
 * 自己那行和「我的位置」都滚出屏幕时，底部固定显示「我的名次」。默认看全部年级，不再单独放「新生榜」。
 */
type Range = 'WEEK' | 'MONTH' | 'YEAR' | 'TOTAL'
type Scope = 'GLOBAL' | 'SCHOOL'
const RANGES: { label: string; value: Range }[] = [
  { label: '近 7 天', value: 'WEEK' },
  { label: '近 30 天', value: 'MONTH' },
  { label: '近一年', value: 'YEAR' },
  { label: '累计', value: 'TOTAL' }
]
/** 一次多看 20 名，接口最多 200 */
const PAGE = 20
const MAX_LIMIT = 200

const { years: activeYears } = useEnrollmentYears()
const auth = useAuthStore()
const accounts = useAccounts(!auth.isAdmin)
const router = useRouter()
const { isMobile } = useBreakpoint()
const myYear = auth.approvedMembership?.enrollmentYear ?? null

const scope = ref<Scope>(!auth.isAdmin && auth.approvedMembership ? 'SCHOOL' : 'GLOBAL')
const scopes = computed(() => auth.approvedMembership
  ? [{ label: '总榜', value: 'GLOBAL' as Scope }, { label: '校内榜', value: 'SCHOOL' as Scope }]
  : [{ label: '总榜', value: 'GLOBAL' as Scope }])
const range = ref<Range>('WEEK')
const filters = ref<FilterValues>({ year: null, platform: null, metric: 'FIRST_AC' })
const limit = ref(PAGE)
const ranking = ref<Ranking | null>(null)
const capabilities = ref<PlatformCapability[]>([])
const loading = ref(false)
const loadingMore = ref(false)
const error = ref('')
let request = 0

async function load(more = false) {
  const current = ++request
  if (more) loadingMore.value = true
  else loading.value = true
  error.value = ''
  try {
    const data = await get<Ranking>('/stats/ranking', {
      scope: scope.value,
      type: range.value,
      metric: 'FIRST_AC',
      platform: filters.value.platform || undefined,
      year: filters.value.year ?? undefined,
      limit: limit.value
    })
    if (current === request) ranking.value = data
  } catch (e) {
    if (current === request) {
      error.value = '榜单加载失败，请重试'
      notifyError(e)
    }
  } finally {
    if (current === request) {
      loading.value = false
      loadingMore.value = false
    }
  }
}

onMounted(async () => {
  get<PlatformCapability[]>('/platforms/capabilities')
    .then((list) => (capabilities.value = list))
    .catch(() => { /* 平台筛选只是少了选项，读不到不影响榜单 */ })
  await load()
})
onBeforeUnmount(() => {
  request++
})

/** 筛选变化后从第一页重新读取。 */
function onFilters(next: FilterValues) {
  filters.value = next
  limit.value = PAGE
  void load()
}
watch(range, () => {
  limit.value = PAGE
  void load()
})
watch(scope, () => {
  filters.value = { ...filters.value, year: null }
  limit.value = PAGE
  void load()
})
function more() {
  limit.value = Math.min(MAX_LIMIT, limit.value + PAGE)
  void load(true)
}

const metric = { label: '首次通过题数', short: '首次通过' }
const unit = '题'
const rangeLabel = computed(() => RANGES.find((r) => r.value === range.value)!.label)
const scoreLabel = computed(() => (range.value === 'TOTAL' ? '累计通过' : `${rangeLabel.value}${metric.short}`))
const monthDay = (value: string) => {
  const [, m, d] = value.split('-')
  return `${Number(m)}/${Number(d)}`
}
const period = computed(() => {
  const from = ranking.value?.from
  const to = ranking.value?.to
  if (!from || !to) return ''
  // 近一年跨年：带上年份，免得写成「9/29–9/28」
  if (from.slice(0, 4) !== to.slice(0, 4)) return `${from.slice(0, 4)}/${monthDay(from)}–${to.slice(0, 4)}/${monthDay(to)}`
  return `${monthDay(from)}–${monthDay(to)}`
})
const scopeName = computed(() =>
  [scope.value === 'GLOBAL' ? '全站' : '全校', filters.value.year ? `${filters.value.year} 级` : '',
    filters.value.platform ? platformName(String(filters.value.platform)) : '']
    .filter(Boolean)
    .join(' · ')
)
const scopeLine = computed(() => `${scopeName.value} · ${period.value || rangeLabel.value} · ${range.value === 'TOTAL' ? '累计通过' : metric.short}`)

/** 筛选片：年级与平台；班级不作为排行筛选条件。 */
const fields = computed<FilterField[]>(() => {
  const years = [...activeYears.value]
  years.sort((a, b) => b - a)
  const list: FilterField[] = [
    {
      key: 'year',
      label: '年级',
      defaultValue: null,
      options: [...years.map((year) => ({ label: `${year} 级`, value: year })), { label: '全部年级', value: null }]
    },
    {
      key: 'platform',
      label: '平台',
      defaultValue: null,
      options: [
        { label: '全部平台', value: null },
        ...capabilities.value.filter((c) => c.syncSupported).map((c) => ({ label: c.displayName, value: c.platform }))
      ]
    }
  ]
  return list
})

const rows = computed(() => ranking.value?.rows ?? [])
const podium = computed(() => rows.value.filter((row) => row.position <= 3))
const rest = computed(() => rows.value.filter((row) => row.position > 3))
const maxScore = computed(() => rows.value[0]?.score ?? rows.value[0]?.solved ?? 0)
const canMore = computed(() => !!ranking.value && rows.value.length < ranking.value.participantCount && limit.value < MAX_LIMIT)
const myRank = computed(() => ranking.value?.myRank ?? null)
const filtersChanged = computed(() => fields.value.some((field) => filters.value[field.key] !== field.defaultValue))

/** 没上榜时按原因给一个动作 */
const hasClaimable = computed(() => accounts.accounts.some((a) => a.bindingKind === 'CLAIMABLE' && !a.ownershipNote))
const reasonAction = computed<{ label: string; run: () => void } | null>(() => {
  switch (ranking.value?.myRankReason) {
    case 'NO_SCHOOL':
      return { label: '去选择学校', run: () => void router.push('/select-school') }
    case 'HIDDEN':
      return null
    case 'PRIVATE_PROFILE':
      return { label: '去隐私设置', run: () => void router.push('/settings?section=privacy') }
    case 'NO_VERIFIED':
      return hasClaimable.value
        ? { label: '去验证归属', run: () => void router.push('/accounts') }
        : { label: '去绑定账号', run: () => void router.push('/accounts?add=') }
    case 'YEAR':
      return myYear
        ? { label: `看 ${myYear} 级`, run: () => onFilters({ ...filters.value, year: myYear }) }
        : { label: '看全部年级', run: () => onFilters({ ...filters.value, year: null }) }
    case 'PLATFORM':
      return { label: '看全部平台', run: () => onFilters({ ...filters.value, platform: null }) }
    case 'NO_DATA':
      if (range.value !== 'TOTAL') return { label: '看累计榜', run: () => (range.value = 'TOTAL') }
      return hasClaimable.value ? { label: '去验证归属', run: () => void router.push('/accounts') } : null
    default:
      return null
  }
})

function resetFilters() {
  onFilters({ year: null, platform: null, metric: 'FIRST_AC' })
}

/**
 * 手机上「我」那一行（领奖台或榜单里）和「我的位置」都不在屏幕里时，底部固定显示「我的名次」。
 * 顶栏 56、底栏约 70 不算在屏幕里。
 */
const anchorVisible = ref(true)
let observer: IntersectionObserver | null = null
const seen = new Set<Element>()
function observeAnchors() {
  observer?.disconnect()
  seen.clear()
  anchorVisible.value = true
  if (!isMobile.value || !myRank.value || typeof IntersectionObserver === 'undefined') return
  observer = new IntersectionObserver(
    (entries) => {
      for (const entry of entries) {
        if (entry.isIntersecting) seen.add(entry.target)
        else seen.delete(entry.target)
      }
      anchorVisible.value = seen.size > 0
    },
    { rootMargin: '-56px 0px -80px 0px' }
  )
  document.querySelectorAll('[data-me-anchor]').forEach((el) => observer!.observe(el))
}
watch([ranking, isMobile], async () => {
  await nextTick()
  observeAnchors()
})
onBeforeUnmount(() => observer?.disconnect())
const showMyBar = computed(() => isMobile.value && !!myRank.value && !anchorVisible.value)

function scrollToMe() {
  const row = document.querySelector('[data-test=ranking-row][data-me-anchor], [data-test=podium] [data-me-anchor]')
  const target = row ?? document.querySelector('[data-test=my-position]')
  target?.scrollIntoView({ block: 'center', behavior: 'smooth' })
}
</script>

<template>
  <div data-test="page" :class="{ 'pb-16 md:pb-0': myRank }">
    <PageHeader title="刷题排行">
      <!-- 手机上范围写在「我的位置」里，页头不再重复 -->
      <template v-if="!isMobile" #description>
        <strong class="text-ink">{{ scopeName }}</strong> · {{ rangeLabel }}<template v-if="period">（<span class="num">{{ period }}</span>）</template><!--
        括号后面直接接「·」；没有日期（累计榜）时前面空一格
        -->{{ period ? '' : ' ' }}· {{ range === 'TOTAL' ? '通过题数' : metric.label }}<template v-if="ranking"> · <span class="num">{{ ranking.participantCount }}</span> 人参与</template>
      </template>
      <template #actions>
        <InfoTip title="排行怎么算" label="统计口径" text="统计口径">
          <p v-if="ranking?.description" class="m-0">{{ ranking.description }}。</p>
          <p class="m-0">{{ ranking?.note ?? '总榜包含未加入学校的用户，校内榜只看已通过的成员；账号需验证归属。' }}</p>
        </InfoTip>
      </template>
      <template #mobile-actions>
        <InfoTip title="排行怎么算" label="统计口径" topbar>
          <p v-if="ranking?.description" class="m-0">{{ ranking.description }}。</p>
          <p class="m-0">{{ ranking?.note ?? '总榜包含未加入学校的用户，校内榜只看已通过的成员；账号需验证归属。' }}</p>
        </InfoTip>
      </template>
    </PageHeader>

    <div class="mb-4 flex flex-col gap-3 md:(mb-5 flex-row flex-wrap items-center)">
      <SegmentedControl v-if="scopes.length > 1" v-model="scope" :options="scopes" label="榜单范围" :block="isMobile" />
      <SegmentedControl v-model="range" :options="RANGES" label="榜单时间范围" :block="isMobile" />
      <span class="hidden h-6 w-px bg-line md:block" aria-hidden="true" />
      <FilterBar :model-value="filters" :fields="fields" sheet-title="筛选排行" apply-text="查看结果" class="min-w-0" @update:model-value="onFilters" />
    </div>

    <!-- 第一次加载 -->
    <div v-if="!ranking && loading" class="grid grid-cols-1 gap-4 md:gap-5 xl:grid-cols-[minmax(0,2fr)_minmax(0,1fr)]" aria-busy="true">
      <div class="flex flex-col gap-4">
        <div class="grid grid-cols-3 items-end gap-4 border border-line rounded-card border-solid bg-surface p-6">
          <Skeleton v-for="n in 3" :key="n" class="h-32" />
        </div>
        <div class="flex flex-col gap-3 border border-line rounded-card border-solid bg-surface p-5">
          <Skeleton v-for="n in 6" :key="n" class="h-8" />
        </div>
      </div>
      <Skeleton class="h-56" />
    </div>

    <div v-else-if="!ranking && error" class="flex flex-col items-start gap-2 border border-line rounded-card border-solid bg-surface p-5">
      <p class="m-0 text-sm text-danger-ink">{{ error }}</p>
      <el-button @click="load()">重试</el-button>
    </div>

    <div
      v-else-if="ranking"
      class="grid grid-cols-1 items-start gap-4 transition-opacity md:gap-5 xl:grid-cols-[minmax(0,2fr)_minmax(0,1fr)]"
      :class="{ 'opacity-60': loading }"
      :aria-busy="loading ? 'true' : undefined"
    >
      <div class="min-w-0 flex flex-col gap-4">
        <MyPosition
          v-if="isMobile && !auth.isAdmin"
          compact
          :ranking="ranking"
          :unit="unit"
          :score-label="scoreLabel"
          :scope-line="scopeLine"
          :action="reasonAction"
          @action="reasonAction?.run()"
        />

        <template v-if="rows.length">
          <RankingPodium :rows="podium" :unit="unit" :me-id="auth.me?.id" :scope="scope" />
          <RankingList
            v-if="rest.length"
            :rows="rest"
            :max-score="maxScore"
            :unit="unit"
            :score-label="scoreLabel"
            :me-id="auth.me?.id"
            :scope="scope"
            :shown="rows.length"
            :total="ranking.participantCount"
            :can-more="canMore"
            :loading-more="loadingMore"
            @more="more"
          />
        </template>
        <EmptyState
          v-else
          icon="i-lucide-trophy"
          title="当前范围里还没有人上榜"
          :description="scope === 'GLOBAL' ? '只统计验证过归属的账号；可以试试累计榜或切换平台' : '只统计验证过归属的账号；可以放宽年级或平台再看'"
          :action="filtersChanged || range !== 'WEEK' ? '恢复默认筛选' : undefined"
          @action="range = 'WEEK'; resetFilters()"
        />
      </div>

      <aside class="min-w-0 flex flex-col gap-4 xl:(sticky top-6)">
        <MyPosition
          v-if="!isMobile && !auth.isAdmin"
          :ranking="ranking"
          :unit="unit"
          :score-label="scoreLabel"
          :scope-line="scopeLine"
          :action="reasonAction"
          @action="reasonAction?.run()"
        />
        <RankingCoverage :coverage="ranking.coverage" :timed="range !== 'TOTAL'" />
      </aside>
    </div>

    <button v-if="showMyBar && myRank" type="button" class="my-bar" data-test="my-rank-bar" @click="scrollToMe">
      <span class="num text-[17px] font-semibold">#{{ myRank.rank }}</span>
      <span class="min-w-0 flex-1 truncate text-left text-sm">我 · {{ myRank.displayName }}</span>
      <span class="text-[13px]"><span class="num text-base font-semibold">{{ myRank.score ?? myRank.solved ?? 0 }}</span> {{ unit }}</span>
    </button>
  </div>
</template>

<style scoped>
/* 手机底部的「我的名次」：浮在底栏上面，颜色和页面反着来，一眼能看到 */
.my-bar {
  position: fixed;
  right: 12px;
  bottom: calc(64px + env(safe-area-inset-bottom) + 12px);
  left: 12px;
  z-index: 15;
  display: flex;
  align-items: center;
  gap: 12px;
  height: 52px;
  padding: 0 14px;
  border: 0;
  border-radius: 14px;
  background: var(--text-strong);
  box-shadow: 0 10px 24px rgba(18, 22, 41, 0.25);
  color: var(--bg-elevated);
  font: inherit;
  cursor: pointer;
}

.my-bar:focus-visible {
  outline: 2px solid var(--brand-500);
  outline-offset: 2px;
}
</style>
