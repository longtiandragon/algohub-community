<script setup lang="ts">
import { computed, nextTick, onBeforeUnmount, onMounted, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ApiError, get } from '@/api/client'
import type { HeatmapData } from '@/api/types'
import { heatmapWindow } from '@/utils/dailyMetric'
import { useBreakpoint } from '@/composables/useBreakpoint'
import AchievementPanel from '@/components/AchievementPanel.vue'
import RatingHistoryCard from '@/components/RatingHistoryCard.vue'
import PageHeader from '@/components/ui/PageHeader.vue'
import PageTabs from '@/components/ui/PageTabs.vue'
import FriendActions from '@/components/social/FriendActions.vue'
import ProfileCard from '@/components/profile/ProfileCard.vue'
import DailyRecords from '@/components/profile/DailyRecords.vue'
import MonthCalendar from '@/components/profile/MonthCalendar.vue'
import ProgressCards from '@/components/profile/ProgressCards.vue'
import PlatformComposition from '@/components/profile/PlatformComposition.vue'
import PlatformPie from '@/components/profile/PlatformPie.vue'
import RecentAccepted from '@/components/profile/RecentAccepted.vue'
import DistributionCards from '@/components/profile/DistributionCards.vue'
import SubmissionHistory from '@/components/profile/SubmissionHistory.vue'
import NowcoderTrackerPanel from '@/components/profile/NowcoderTrackerPanel.vue'

interface Person { id: number; displayName: string; avatarUrl: string | null; bio: string | null; enrollmentYear: number | null; className: string | null; schoolName: string | null }
interface Stats {
  totalSolvedAcrossPlatforms: number; totalPassed: number
  platformSolved: { platform: string; solved: number }[]
  platformTotals: { platform: string; solved: number }[]
  snapshotOverview: { platform: string; handle?: string; rawExtra?: string | null }[]
  difficultyDistribution: { platform: string; difficulty: string; source: string; solved: number }[]
  tagDistribution: { category: string; solved: number }[]
  tagCoverage: { withTags: number; total: number }
  recentAccepted: { platform: string; title: string | null; url: string | null; problemKey: string | null; difficultyLabel: string | null; submittedAt: string | null }[]
}
const route = useRoute()
const router = useRouter()
const { isMobile } = useBreakpoint()
const userId = computed(() => Number(route.params.userId))
const person = ref<Person | null>(null)
const stats = ref<Stats | null>(null)
const heatmap = ref<HeatmapData | null>(null)
const streak = ref<{ longestStreak: number } | null>(null)
const loading = ref(false)
const heatmapLoading = ref(false)
const error = ref('')
const heatmapError = ref('')
const selectedPlatform = ref('')
const selectedHistoryDate = ref('')
const historySection = ref<HTMLElement>()
let request = 0
let heatRequest = 0
const tabs = [
  { key: 'overview', label: '概览' }, { key: 'distribution', label: '分布' },
  { key: 'rating', label: '比赛与 Rating', short: 'Rating' }, { key: 'records', label: '做题记录' }
] as const
type Tab = (typeof tabs)[number]['key']
const tab = computed<Tab>(() => tabs.find(t => t.key === route.query.tab)?.key ?? 'overview')
const tabLink = (key: Tab) => ({ query: { ...route.query, tab: key === 'overview' ? undefined : key } })
const tabLinks = computed(() => tabs.map(t => ({ ...t, to: tabLink(t.key) })))
const platforms = computed(() => [...new Set(stats.value?.snapshotOverview.map(a => a.platform) ?? [])])
const profileAccounts = computed(() => stats.value?.snapshotOverview.map(a => ({ platform: a.platform, handle: a.handle })) ?? [])
const trackerAccount = computed(() => stats.value?.snapshotOverview.find(a => a.platform === 'NOWCODER'))
const membership = computed(() => [person.value?.enrollmentYear ? `${person.value.enrollmentYear} 级` : '', person.value?.className, person.value?.schoolName].filter(Boolean).join(' · ') || '还没有加入学校')
async function showDay(date: string) {
  selectedHistoryDate.value = date
  await router.replace(tabLink('records'))
  await nextTick()
  historySection.value?.scrollIntoView({ behavior: 'smooth', block: 'start' })
}
async function loadHeatmap() {
  const current = ++heatRequest
  heatmapLoading.value = true
  heatmapError.value = ''
  try {
    const range = heatmapWindow()
    const data = await get<HeatmapData>('/stats/heatmap', { userId: userId.value, from: `${range.firstMonth}-01`, to: range.to, platform: selectedPlatform.value })
    if (current === heatRequest) heatmap.value = data
  } catch {
    if (current === heatRequest) heatmapError.value = '每日记录加载失败，请重试'
  } finally {
    if (current === heatRequest) heatmapLoading.value = false
  }
}
async function load() {
  const current = ++request
  person.value = null; stats.value = null; heatmap.value = null; error.value = ''; loading.value = true
  try {
    if (!Number.isSafeInteger(userId.value) || userId.value <= 0) throw new Error('invalid id')
    const [p, s] = await Promise.all([
      get<Person>(`/me/profile/${userId.value}`),
      get<Stats>(`/stats/profile/${userId.value}`)
    ])
    if (current === request) {
      person.value = p; stats.value = s; void loadHeatmap()
      void get<{ longestStreak: number }>(`/activity/streak/${userId.value}`).then(st => { if (current === request) streak.value = st }).catch(() => { if (current === request) streak.value = null })
    }
  } catch (e) {
    if (current === request) error.value = e instanceof ApiError && e.kind === 'NOT_FOUND'
      ? '该档案不存在或暂未向你开放。对方可能设置了仅同校可见或仅自己可见。'
      : '暂时无法加载该档案，请稍后重试。'
  } finally { if (current === request) loading.value = false }
}
watch(() => route.params.userId, load, { immediate: true })
watch(selectedPlatform, () => { if (person.value) void loadHeatmap() })
const themeVersion = ref(0)
let observer: MutationObserver | null = null
const chartTheme = computed(() => {
  void themeVersion.value
  const css = (name: string) => getComputedStyle(document.documentElement).getPropertyValue(name).trim()
  return { text: css('--text'), muted: css('--text-muted'), border: css('--border'), elevated: css('--bg-elevated'), subtle: css('--bg-subtle'), brand: css('--brand-500'), brandAlt: css('--brand-400'), success: css('--success'), warning: css('--warning'), danger: css('--danger'), info: css('--info') }
})
onMounted(() => { observer = new MutationObserver(() => { themeVersion.value++ }); observer.observe(document.documentElement, { attributes: true, attributeFilter: ['data-theme'] }) })
onBeforeUnmount(() => observer?.disconnect())
</script>

<template>
  <div class="page" data-test="public-profile">
    <PageHeader title="同学档案" description="档案内容按隐私设置和你的管理权限展示。" class="mb-4">
      <template #actions><RouterLink to="/ranking" class="text-brand-600">返回刷题排行</RouterLink></template>
    </PageHeader>
    <p v-if="loading" role="status" class="text-muted">正在加载档案…</p>
    <div v-else-if="error" role="status" class="rounded-card bg-surface p-6"><p>{{ error }}</p><el-button @click="load">重试</el-button></div>
    <template v-else-if="person && stats">
      <ProfileCard :person="{ displayName: person.displayName, avatarUrl: person.avatarUrl, membership, bio: person.bio }"
        :accounts="profileAccounts" :solved="stats.totalSolvedAcrossPlatforms" :total-passed="stats.totalPassed"
        :longest-streak="streak?.longestStreak ?? null" read-only class="mb-4">
        <template #actions><FriendActions :key="userId" :user-id="userId" /></template>
      </ProfileCard>
      <PageTabs :tabs="tabLinks" :active="tab" label="档案分区" class="mb-4" />
      <div v-if="tab === 'overview'" class="flex flex-col gap-4" role="region" aria-label="概览">
        <MonthCalendar v-if="isMobile" v-model:platform="selectedPlatform" :user-id="userId" metric="FIRST_AC" :data="heatmap" :loading="heatmapLoading" :error="heatmapError" @open="showDay" @retry="loadHeatmap" />
        <DailyRecords v-else v-model:platform="selectedPlatform" metric="FIRST_AC" :data="heatmap" :platforms="platforms" :selected-date="selectedHistoryDate" :loading="heatmapLoading" :error="heatmapError" @select="showDay" @retry="loadHeatmap" />
        <ProgressCards :data="heatmap" :loading="loading" />
        <div class="grid grid-cols-1 items-start gap-4 lg:grid-cols-2">
          <PlatformComposition :solved="stats.platformSolved" :totals="stats.platformTotals" :total-only="[]" />
          <RecentAccepted :rows="stats.recentAccepted" :all-to="tabLink('records')" />
        </div>
        <PlatformPie :rows="stats.platformTotals" :text-color="chartTheme.text" />
        <AchievementPanel :key="userId" :user-id="userId" compact />
      </div>
      <DistributionCards v-else-if="tab === 'distribution'" :difficulty="stats.difficultyDistribution" :tags="stats.tagDistribution" :coverage="stats.tagCoverage" :theme="chartTheme" :loading="loading" />
      <RatingHistoryCard v-else-if="tab === 'rating'" :key="userId" :user-id="userId" :theme="chartTheme" />
      <section v-else ref="historySection" class="flex flex-col gap-4">
        <SubmissionHistory :key="userId" v-model:selected-date="selectedHistoryDate" :selected-platform="selectedPlatform" :user-id="userId" :public-platforms="platforms" />
        <NowcoderTrackerPanel v-if="trackerAccount" :key="userId" :user-id="userId" :handle="trackerAccount.handle ?? ''" :raw-extra="trackerAccount.rawExtra ?? null" />
      </section>
    </template>
  </div>
</template>
