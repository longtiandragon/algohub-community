<script setup lang="ts">
import { computed, nextTick, onBeforeUnmount, onMounted, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { heatmapWindow } from '@/utils/dailyMetric'
import AchievementPanel from '@/components/AchievementPanel.vue'
import MonthlyReport from '@/components/MonthlyReport.vue'
import { get, notifyError } from '@/api/client'
import type { HeatmapData } from '@/api/types'
import DailyRecords from '@/components/profile/DailyRecords.vue'
import DistributionCards from '@/components/profile/DistributionCards.vue'
import MonthCalendar from '@/components/profile/MonthCalendar.vue'
import NowcoderTrackerPanel from '@/components/profile/NowcoderTrackerPanel.vue'
import PlatformComposition from '@/components/profile/PlatformComposition.vue'
import PlatformPie from '@/components/profile/PlatformPie.vue'
import ProfileCard from '@/components/profile/ProfileCard.vue'
import ProgressCards from '@/components/profile/ProgressCards.vue'
import RecentAccepted from '@/components/profile/RecentAccepted.vue'
import SubmissionHistory from '@/components/profile/SubmissionHistory.vue'
import PageTabs from '@/components/ui/PageTabs.vue'
import { useBreakpoint } from '@/composables/useBreakpoint'
import LuoguExportPanel from '@/components/LuoguExportPanel.vue'
import RatingHistoryCard from '@/components/RatingHistoryCard.vue'

interface ProfileData {
  platformSolved: { platform: string; solved: number }[]
  totalSolvedAcrossPlatforms: number
  totalPassed: number
  platformTotals: { platform: string; solved: number }[]
  crossPlatformMapping: {
    uniqueSolvedAfterMapping: number
    mergedDuplicates: number
    mappedSolvedRecords: number
    note: string
  }
  snapshotOverview: {
    platform: string
    handle: string
    syncStatus: string
    lastSuccessAt: string | null
    authMode: string
    ownershipStatus: string
    solvedTotal: number | null
    submissionTotal: number | null
    capturedAt: string | null
    rawExtra: string | null
  }[]
  difficultyDistribution: { platform: string; difficulty: string; source: string; solved: number }[]
  tagDistribution: { category: string; solved: number }[]
  tagCoverage: { withTags: number; total: number }
  recentAccepted: {
    platform: string
    title: string | null
    url: string | null
    problemKey: string | null
    difficultyLabel: string | null
    submittedAt: string | null
  }[]
}

const profile = ref<ProfileData | null>(null)
/** 近一年的每日数据：每日记录、每周新增、累计通过共用这一份 */
const heatmap = ref<HeatmapData | null>(null)
const streak = ref<{ longestStreak: number } | null>(null)
const metric = ref('FIRST_AC')
const loading = ref(true)
const loadError = ref('')
const heatmapError = ref('')

/** 手机上每日记录是月历（MonthCalendar），电脑、平板上是近一年的格子 */
const { isMobile } = useBreakpoint()
const route = useRoute()
const selectedPlatform = ref(typeof route.query.platform === 'string' ? route.query.platform : '')
const router = useRouter()

/**
 * 五个标签，用 ?tab= 记住（概览不带参数）。各区块原样放进对应标签：
 * 概览（热力图、记录里的变化、平台构成、近期通过）、分布（难度、标签）、比赛与 Rating、做题记录、报告与导出。
 */
const TABS = [
  { key: 'overview', label: '概览' },
  { key: 'distribution', label: '分布' },
  { key: 'rating', label: '比赛与 Rating', short: 'Rating' },
  { key: 'records', label: '做题记录' },
  { key: 'reports', label: '报告与导出' }
] as const
type TabKey = (typeof TABS)[number]['key']
const tab = computed<TabKey>(() => TABS.find((t) => t.key === route.query.tab)?.key ?? 'overview')
const tabLink = (key: TabKey) => ({ query: { ...route.query, tab: key === 'overview' ? undefined : key } })
const tabLinks = computed(() => TABS.map((t) => ({ ...t, to: tabLink(t.key) })))
function goTab(key: TabKey) {
  void router.replace(tabLink(key))
}

const selectedHistoryDate = ref('')
const historySection = ref<HTMLElement>()
/** 点每日记录的某一天：跳到「做题记录」并筛到那天 */
async function showDay(date: string) {
  selectedHistoryDate.value = date
  await router.replace(tabLink('records'))
  await nextTick()
  historySection.value?.scrollIntoView({ behavior: 'smooth', block: 'start' })
}
watch(() => route.query.platform, (value) => { selectedPlatform.value = typeof value === 'string' ? value : '' })
const heatmapLoading = ref(false)
let heatmapRequest = 0
/** 近一年的每日数据（从第一列所在月份的 1 号读到今天，手机月历也用这份）；换平台时重读，读的时候保留旧的 */
async function loadHeatmap() {
  const request = ++heatmapRequest
  heatmapLoading.value = true
  heatmapError.value = ''
  try {
    const range = heatmapWindow()
    const data = await get<HeatmapData>('/stats/heatmap', { from: `${range.firstMonth}-01`, to: range.to, platform: selectedPlatform.value })
    if (request === heatmapRequest) heatmap.value = data
  } catch (e) {
    if (request === heatmapRequest) { heatmapError.value = '每日记录加载失败，请重试'; notifyError(e) }
  } finally {
    if (request === heatmapRequest) heatmapLoading.value = false
  }
}
async function loadStreak() {
  try {
    streak.value = await get<{ longestStreak: number }>('/activity/streak')
  } catch {
    streak.value = null
  }
}
watch(selectedPlatform, loadHeatmap)

async function load() {
  loading.value = true
  loadError.value = ''
  try {
    const [p] = await Promise.all([get<ProfileData>('/stats/profile'), loadHeatmap(), loadStreak()])
    profile.value = p

  } catch (e) {
    loadError.value = '个人档案加载失败，请重试；这不代表你没有记录'
    notifyError(e)
  } finally {
    loading.value = false
  }
}

/* 图表颜色一律从设计系统的 CSS 变量读取，深浅色主题切换时重新取值并重建 option。 */
const themeVersion = ref(0)
let themeObserver: MutationObserver | null = null

function cssVar(name: string): string {
  return getComputedStyle(document.documentElement).getPropertyValue(name).trim()
}

const chartTheme = computed(() => {
  void themeVersion.value
  return {
    text: cssVar('--text'),
    muted: cssVar('--text-muted'),
    border: cssVar('--border'),
    elevated: cssVar('--bg-elevated'),
    subtle: cssVar('--bg-subtle'),
    brand: cssVar('--brand-500'),
    brandAlt: cssVar('--brand-400'),
    success: cssVar('--success'),
    warning: cssVar('--warning'),
    danger: cssVar('--danger'),
    info: cssVar('--info')
  }
})

onMounted(() => {
  themeObserver = new MutationObserver(() => { themeVersion.value += 1 })
  themeObserver.observe(document.documentElement, { attributes: true, attributeFilter: ['data-theme'] })
  void load()
})

onBeforeUnmount(() => {
  themeObserver?.disconnect()
  themeObserver = null
})

/** 已绑定的平台（顶部胶囊、每日记录的平台筛选）、只有总数的平台 */
const handles = computed(() => (profile.value?.snapshotOverview ?? []).map((s) => ({ platform: s.platform, handle: s.handle })))
const boundPlatforms = computed(() => [...new Set((profile.value?.snapshotOverview ?? []).map((s) => s.platform))])
const detailPlatformCount = computed(() => profile.value?.platformSolved.length ?? 0)
/** 牛客账号：「做题记录」里列出它在 Tracker 通过的题 */
const trackerAccount = computed(() => profile.value?.snapshotOverview.find((s) => s.platform === 'NOWCODER') ?? null)
const totalOnlyAccounts = computed(() => (profile.value?.snapshotOverview ?? []).filter(s =>
  s.solvedTotal != null && !profile.value?.platformSolved.some(p => p.platform === s.platform && p.solved > 0)))

</script>

<template>
  <div class="page" data-test="page">
    <el-alert v-if="loadError" :title="loadError" type="error" :closable="false" class="mb-4"><el-button text @click="load">重试</el-button></el-alert>

    <ProfileCard
      :accounts="handles"
      :solved="detailPlatformCount ? profile?.totalSolvedAcrossPlatforms ?? null : null"
      :total-passed="profile?.totalPassed ?? null"
      :longest-streak="streak?.longestStreak ?? null"
      :loading="loading"
      class="mb-4"
      @reports="goTab('reports')"
    />

    <PageTabs :tabs="tabLinks" :active="tab" label="档案分区" class="profile-tabs" />

    <!-- 概览：每日记录 → 每周新增、累计 → 平台构成、最近通过 → 成就 -->
    <div v-if="tab === 'overview'" class="flex flex-col gap-4" role="region" aria-label="概览">
      <MonthCalendar
        v-if="isMobile"
        v-model:platform="selectedPlatform"
        :metric="metric"
        :data="heatmap"
        :loading="heatmapLoading"
        :error="heatmapError"
        @open="showDay"
        @retry="loadHeatmap"
      />
      <DailyRecords
        v-else
        v-model:platform="selectedPlatform"
        :metric="metric"
        :data="heatmap"
        :platforms="boundPlatforms"
        :selected-date="selectedHistoryDate"
        :loading="heatmapLoading"
        :error="heatmapError"
        @select="showDay"
        @retry="loadHeatmap"
      />
      <ProgressCards :data="heatmap" :loading="loading" />
      <div class="grid grid-cols-1 items-start gap-4 lg:grid-cols-2">
        <PlatformComposition :solved="profile?.platformSolved ?? []" :totals="profile?.platformTotals ?? []" :total-only="totalOnlyAccounts" />
        <RecentAccepted :rows="profile?.recentAccepted ?? []" :all-to="tabLink('records')" />
      </div>
      <PlatformPie :rows="profile?.platformTotals ?? []" :text-color="chartTheme.text" />
      <AchievementPanel compact class="achievements" />
    </div>

    <!-- 分布 -->
    <div v-else-if="tab === 'distribution'" role="region" aria-label="分布">
      <DistributionCards
        :difficulty="profile?.difficultyDistribution ?? []"
        :tags="profile?.tagDistribution ?? []"
        :coverage="profile?.tagCoverage ?? null"
        :theme="chartTheme"
        :loading="loading"
      />
    </div>

    <!-- 比赛与 Rating -->
    <div v-else-if="tab === 'rating'" role="region" aria-label="比赛与 Rating">
      <RatingHistoryCard :theme="chartTheme" />
    </div>

    <!-- 做题记录：点每日记录的某天会跳到这里并筛到那天 -->
    <div v-else-if="tab === 'records'" class="flex flex-col gap-4" role="region" aria-label="做题记录">
      <section ref="historySection"><SubmissionHistory v-model:selected-date="selectedHistoryDate" :selected-platform="selectedPlatform" /></section>
      <NowcoderTrackerPanel v-if="trackerAccount" :handle="trackerAccount.handle" :raw-extra="trackerAccount.rawExtra" />
    </div>

    <!-- 报告与导出 -->
    <div v-else-if="tab === 'reports'" class="flex flex-col gap-4" role="region" aria-label="报告与导出">
      <MonthlyReport />
      <div class="grid grid-cols-1 items-start gap-4 lg:grid-cols-2">
        <LuoguExportPanel />
        <LuoguExportPanel platform="CODEFORCES" />
      </div>
    </div>
  </div>
</template>

<style scoped>
.profile-tabs { margin-bottom: 16px; }
/* 成就面板自带卡片的下边距，概览里靠 gap 隔开，不要 */
.achievements { margin-bottom: 0; }
</style>
