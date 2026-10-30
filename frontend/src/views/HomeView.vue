<script setup lang="ts">
import { computed, onBeforeUnmount, onMounted, ref } from 'vue'
import { get } from '@/api/client'
import type { HeatmapData, Ranking } from '@/api/types'
import { useBreakpoint } from '@/composables/useBreakpoint'
import { useSyncAllAction } from '@/composables/useSyncAllAction'
import { useAccounts } from '@/stores/accounts'
import { useAuthStore } from '@/stores/auth'
import { rankContext } from '@/utils/ranking'
import { campusDate, relativeTime, shiftDate } from '@/utils/statistics'
import CampusFeed from '@/components/home/CampusFeed.vue'
import AnnouncementBoard from '@/components/home/AnnouncementBoard.vue'
import CohortRankCard from '@/components/home/CohortRankCard.vue'
import OnboardingChecklist from '@/components/home/OnboardingChecklist.vue'
import StarterPlatforms from '@/components/home/StarterPlatforms.vue'
import UpcomingContests from '@/components/home/UpcomingContests.vue'
import WeekBars from '@/components/home/WeekBars.vue'
import TopBarActions from '@/components/layout/TopBarActions.vue'
import PlatformStatusCards from '@/components/PlatformStatusCards.vue'
import SectionCard from '@/components/ui/SectionCard.vue'
import StatTile from '@/components/ui/StatTile.vue'

/**
 * 数据总览：这周练得怎么样？我在同届第几？有没有要处理的事？
 * 问候 → 四个数据块（近 7 天新增通过、活跃天数、同届排名、平台账号）→ 每天通过 | 同届排行 → 平台账号 → 近期比赛 | 校内动态。
 * 新同学（还没绑账号、或者绑了还没同步出数据）不显示数据块，换成「3 步建好你的刷题档案」清单：
 * 没绑账号时旁边是常用平台 6 宫格，绑了没同步时下面是账号的同步状态。
 * 每一块单独加载、单独报错；统计每分钟悄悄刷新一次，账号状态来自账号 store 的统一轮询。
 */
interface Streak {
  currentStreak: number
  longestStreak: number
}

const auth = useAuthStore()
const accountStore = useAccounts()
const syncAll = useSyncAllAction()
const { md, lg } = useBreakpoint()

const today = ref(campusDate())
const dates = computed(() => Array.from({ length: 7 }, (_, index) => shiftDate(today.value, index - 6)))
/** 2026-09-21 → 9/21 */
const shortDate = (date: string) => {
  const [, month, day] = date.split('-').map(Number)
  return `${month}/${day}`
}
const range = computed(() => `${shortDate(dates.value[0])} – ${shortDate(dates.value[6])}`)
const cohort = computed(() => auth.approvedMembership?.enrollmentYear ?? null)

// ---------- 数据：每块自己的加载状态 ----------
const heatmap = ref<HeatmapData | null>(null)
const heatmapLoading = ref(true)
const heatmapError = ref('')
const streak = ref<Streak | null>(null)
const streakError = ref(false)
const ranking = ref<Ranking | null>(null)
const rankingLoading = ref(true)
const rankingError = ref('')
const requests = { heatmap: 0, streak: 0, ranking: 0 }

/** silent：定时刷新时读不到就留着原来的数据，不打扰 */
async function loadHeatmap(silent = false) {
  const request = ++requests.heatmap
  if (!silent) heatmapLoading.value = true
  try {
    const data = await get<HeatmapData>('/stats/heatmap', { from: dates.value[0], to: dates.value[6] })
    if (request !== requests.heatmap) return
    heatmap.value = data
    heatmapError.value = ''
  } catch {
    if (request === requests.heatmap && (!silent || !heatmap.value)) heatmapError.value = '近期统计暂时无法加载'
  } finally {
    if (request === requests.heatmap) heatmapLoading.value = false
  }
}

async function loadStreak() {
  const request = ++requests.streak
  try {
    const data = await get<Streak>('/activity/streak')
    if (request !== requests.streak) return
    streak.value = data
    streakError.value = false
  } catch {
    if (request === requests.streak && !streak.value) streakError.value = true
  }
}

async function loadRanking(silent = false) {
  const request = ++requests.ranking
  if (!silent) rankingLoading.value = true
  try {
    const data = await get<Ranking>('/stats/ranking', { type: 'WEEK', metric: 'FIRST_AC', year: cohort.value ?? undefined, approvedOnly: true })
    if (request !== requests.ranking) return
    ranking.value = data
    rankingError.value = ''
  } catch {
    if (request === requests.ranking && (!silent || !ranking.value)) rankingError.value = '个人排名暂时无法加载'
  } finally {
    if (request === requests.ranking) rankingLoading.value = false
  }
}

function loadAll(silent = false) {
  today.value = campusDate()
  void loadHeatmap(silent)
  void loadStreak()
  void loadRanking(silent)
}

let timer: ReturnType<typeof setInterval> | undefined
onMounted(() => {
  loadAll()
  timer = setInterval(() => {
    if (!document.hidden) loadAll(true)
  }, 60_000)
})
onBeforeUnmount(() => {
  clearInterval(timer)
  requests.heatmap++
  requests.streak++
  requests.ranking++
})

// ---------- 数字 ----------
const accounts = computed(() => (accountStore.loaded ? accountStore.accounts : null))
const firstAc = computed(() => heatmap.value?.metrics.FIRST_AC ?? {})
/** 有能确认首次通过时间的数据：有历史完整的账号成功同步过，或者这 7 天里已经有记录 */
const hasFirstAc = computed(
  () => !!heatmap.value && ((accounts.value ?? []).some((a) => a.historyComplete && a.lastSuccessAt) || Object.keys(firstAc.value).length > 0)
)
const dayValues = computed(() => dates.value.map((date) => ({ date, value: firstAc.value[date] ?? 0 })))
const newSolved = computed(() => (hasFirstAc.value ? dayValues.value.reduce((sum, day) => sum + day.value, 0) : null))
const miniMax = computed(() => Math.max(1, ...dayValues.value.map((day) => day.value)))

const activeSet = computed(() => {
  const set = new Set<string>()
  for (const metric of ['SUBMISSIONS', 'AC_SUBMISSIONS', 'UNIQUE_AC', 'FIRST_AC']) {
    for (const [date, count] of Object.entries(heatmap.value?.metrics[metric] ?? {})) if (count > 0) set.add(date)
  }
  return set
})
const activeDays = computed(() => {
  if (!heatmap.value) return null
  return activeSet.value.size || ((accounts.value ?? []).some((a) => a.lastSuccessAt && a.historyComplete) ? 0 : null)
})
const WEEKDAYS = ['日', '一', '二', '三', '四', '五', '六']
const weekdayOf = (date: string) => WEEKDAYS[new Date(`${date}T00:00:00Z`).getUTCDay()]
const dots = computed(() => dates.value.map((date) => ({ date, active: activeSet.value.has(date), weekday: weekdayOf(date) })))
/** 没有正在连续的记录时写这一句（有连续时模板里单独写，数字加粗） */
const streakNote = computed(() => {
  if (streakError.value) return '连续天数暂时读不到'
  if (!streak.value) return range.value
  return streak.value.longestStreak > 0 ? `最长连续 ${streak.value.longestStreak} 天` : '还没有连续通过的记录'
})

const rank = computed(() => rankContext(ranking.value))
// 平板和手机上数据块窄，标题不写「· 近 7 天」（排行卡片的标题里有）
const rankLabel = computed(() => `${cohort.value ? `${cohort.value} 级排名` : '校内排名'}${lg.value ? ' · 近 7 天' : ''}`)
const rankProgress = computed(() => {
  const context = rank.value
  if (!context?.ahead) return 1
  const target = context.ahead.score ?? 0
  return target > 0 ? Math.min(1, (context.me.score ?? 0) / target) : 1
})

// ---------- 问候 ----------
const hour = Number(new Intl.DateTimeFormat('en-US', { timeZone: 'Asia/Shanghai', hour: 'numeric', hourCycle: 'h23' }).format(new Date()))
const greeting = hour >= 5 && hour < 11 ? '早上好' : hour >= 11 && hour < 13 ? '中午好' : hour >= 13 && hour < 18 ? '下午好' : '晚上好'
const dateLine = computed(() => {
  const [, month, date] = today.value.split('-').map(Number)
  return `${month}月${date}日 · 周${weekdayOf(today.value)}`
})
const subLine = computed(() => {
  const membership = auth.approvedMembership
  // 新同学的问候下面把学校也写上
  const school = onboardingStep.value ? membership?.schoolName ?? null : null
  return [school, membership?.enrollmentYear ? `${membership.enrollmentYear} 级` : null, membership?.className ?? null].filter(Boolean).join(' · ')
})

// ---------- 同步状态（手机、平板；电脑在侧栏） ----------
const syncBusy = computed(() => accountStore.syncing || accountStore.syncingAll)
const syncLine = computed(() => {
  if (syncBusy.value) return '正在同步…'
  if (!accountStore.loaded) return '正在读取同步状态…'
  if (!accountStore.accounts.length) return '还没有绑定平台账号'
  return accountStore.lastSuccessAt ? `${relativeTime(accountStore.lastSuccessAt)}已更新` : '还没有同步成功过'
})

// ---------- 新同学 ----------
/** 引导到第几步：2 还没绑账号，3 绑了还没同步出数据；null 表示不是新同学（账号状态还没读到时也按 null） */
const onboardingStep = computed<2 | 3 | null>(() => {
  if (!accountStore.loaded) return null
  if (!accountStore.accounts.length) return 2
  return accountStore.accounts.some((account) => account.lastSuccessAt) ? null : 3
})
const firstSyncFailed = computed(() => accountStore.accounts.some((account) => account.syncStatus === 'FAILED'))
</script>

<template>
  <div class="flex flex-col gap-4 md:gap-6" data-test="page">
    <TopBarActions v-if="accountStore.accounts.length">
      <button
        type="button"
        class="topbar-icon"
        :disabled="syncBusy || !accountStore.accounts.length"
        :aria-label="syncBusy ? '正在同步' : '同步全部账号'"
        @click="syncAll"
      >
        <span class="i-lucide-refresh-cw text-xl" :class="{ 'animate-spin motion-reduce:animate-none': syncBusy }" aria-hidden="true" />
      </button>
    </TopBarActions>

    <!-- 问候 -->
    <header class="flex items-end justify-between gap-6 md:pb-2 md:pt-1">
      <div class="min-w-0 flex flex-col gap-1.5 md:gap-2">
        <span class="hidden text-[13.5px] text-muted md:inline">{{ dateLine }}</span>
        <h1 class="m-0 text-[22px] text-ink [overflow-wrap:anywhere] font-bold leading-[1.35] md:(text-[30px] leading-[1.3])">{{ onboardingStep ? '欢迎加入' : greeting }}，{{ auth.me?.displayName ?? '同学' }}</h1>
        <span class="text-sm text-muted md:(text-[14.5px] text-body)">
          <template v-if="subLine">{{ subLine }}</template>
          <template v-if="activeDays !== null && !onboardingStep">
            <template v-if="subLine"> · </template>近 7 天练了 <strong class="num text-ink">{{ activeDays }}</strong> 天<template v-if="activeDays >= 5">，保持住</template>
          </template>
        </span>
        <p v-if="!md && accountStore.accounts.length" class="m-0 mt-1 flex flex-wrap items-center gap-1.5 text-[13px] text-muted" role="status">
          <span
            class="size-[7px] shrink-0 rounded-full"
            :class="syncBusy ? 'bg-brand-400' : accountStore.lastSuccessAt ? 'bg-success' : 'bg-faint'"
            aria-hidden="true"
          />
          {{ syncLine }}
          <template v-if="accountStore.accounts.length">
            ·
            <button type="button" class="sync-link" :disabled="syncBusy" @click="syncAll">立即同步</button>
          </template>
        </p>
      </div>
      <!-- 平板：右边是同步状态（电脑在侧栏）；电脑：打开个人档案 -->
      <button
        v-if="md && !lg && accountStore.accounts.length"
        type="button"
        class="sync-pill"
        :disabled="syncBusy || !accountStore.accounts.length"
        :aria-label="`${syncLine}，同步全部账号`"
        @click="syncAll"
      >
        <span
          class="size-2 shrink-0 rounded-full"
          :class="syncBusy ? 'bg-brand-400' : accountStore.lastSuccessAt ? 'bg-success' : 'bg-faint'"
          aria-hidden="true"
        />
        {{ syncLine }}
        <span class="i-lucide-refresh-cw text-[15px] text-brand" :class="{ 'animate-spin motion-reduce:animate-none': syncBusy }" aria-hidden="true" />
      </button>
      <RouterLink v-if="lg" to="/profile" class="profile-link">
        打开个人档案<span class="i-lucide-arrow-up-right text-base" aria-hidden="true" />
      </RouterLink>
    </header>

    <!-- 有文字公告时一条公告条；常用资源在平台账号前 -->
    <AnnouncementBoard variant="notice" />

    <!-- 新同学：清单 + 常用平台（还没绑账号）或账号同步状态（绑了还没同步出数据）+ 比赛和动态，不显示数据块 -->
    <template v-if="onboardingStep">
      <div class="grid grid-cols-1 gap-4" :class="{ 'lg:grid-cols-[7fr_5fr]': onboardingStep === 2 }">
        <OnboardingChecklist
          :step="onboardingStep"
          :accounts="accountStore.accounts.length"
          :syncing="accountStore.syncing"
          :failed="firstSyncFailed"
        />
        <StarterPlatforms v-if="onboardingStep === 2" />
      </div>
      <AnnouncementBoard variant="resources" />
      <PlatformStatusCards v-if="onboardingStep === 3" />
      <div class="grid grid-cols-1 gap-4 md:grid-cols-2">
        <UpcomingContests :limit="md ? 3 : 2" />
        <CampusFeed title="看看同学们在练什么" :limit="md ? 5 : 3" />
      </div>
    </template>

    <template v-else>
      <!-- 四个数据块 -->
      <div class="grid grid-cols-2 gap-3 md:grid-cols-4 lg:gap-4" data-test="home-stats">
        <StatTile
          label="近 7 天新增通过"
          :value="newSolved"
          :unit="newSolved !== null ? '题' : undefined"
          tip="新增通过怎么算"
          :loading="heatmapLoading"
          :error="heatmapError"
          @retry="loadHeatmap()"
        >
          <template #tip>只算首次通过时间已经确认的题目（来自同步到的提交记录），不含平台总题数的快照增量；同一道题只算第一次。</template>
          <template #chart>
            <span class="flex h-full items-end gap-1 md:gap-1.5" aria-hidden="true">
              <span
                v-for="day in dayValues"
                :key="day.date"
                class="flex-1 rounded-[3px]"
                :class="day.value ? 'bg-brand' : 'bg-line'"
                :style="{ height: day.value ? `${Math.max(12, (day.value / miniMax) * 100)}%` : '3px' }"
              />
            </span>
          </template>
          <template #foot>
            <span :class="{ num: newSolved !== null }">{{ newSolved === null ? '暂无可确认首次通过时间的数据' : range }}</span>
          </template>
        </StatTile>

        <StatTile
          label="活跃天数"
          :value="activeDays"
          :unit="activeDays !== null ? '/ 7 天' : undefined"
          tip="活跃天数怎么算"
          :loading="heatmapLoading"
          :error="heatmapError"
          @retry="loadHeatmap()"
        >
          <template #tip>这 7 天里有提交或通过记录的天数（北京时间）。只看已经同步到的记录，平台不给完整历史时可能偏少。</template>
          <template #chart>
            <span class="flex h-full items-center justify-between" aria-hidden="true">
              <span v-for="dot in dots" :key="dot.date" class="flex flex-col items-center gap-[3px]">
                <span class="activity-dot" :class="{ 'is-active': dot.active }" />
                <span class="hidden text-[10.5px] text-muted leading-none md:inline">{{ dot.weekday }}</span>
              </span>
            </span>
          </template>
          <template #foot>
            <span v-if="streak && !streakError && streak.currentStreak > 0">
              已连续 <strong class="num text-warning-ink">{{ streak.currentStreak }}</strong> 天 · 最长 <span class="num">{{ streak.longestStreak }}</span> 天
            </span>
            <span v-else>{{ streakNote }}</span>
          </template>
        </StatTile>

        <StatTile
          :label="rankLabel"
          :value="rank ? `#${rank.me.rank}` : null"
          :unit="rank ? `/ ${ranking!.participantCount} 人` : undefined"
          accent
          to="/ranking"
          :loading="rankingLoading"
          :error="rankingError"
          @retry="loadRanking()"
        >
          <template v-if="rank" #chart>
            <span class="flex h-full flex-col justify-end gap-1.5" aria-hidden="true">
              <span class="h-1.5 overflow-hidden rounded-full bg-line-soft">
                <span class="block h-full rounded-full bg-brand" :style="{ width: `${Math.round(rankProgress * 100)}%` }" />
              </span>
              <span v-if="md" class="num flex justify-between text-[11px] text-muted leading-none">
                <span>{{ rank.me.score ?? 0 }} 题</span>
                <span v-if="rank.ahead">第 {{ rank.ahead.rank }} 名 {{ rank.ahead.score ?? 0 }} 题</span>
              </span>
            </span>
          </template>
          <template #foot>
            <span v-if="rank?.gap" class="text-brand-600">{{ lg ? '再通过' : '差' }} {{ rank.gap }} 题追平第 {{ rank.ahead!.rank }} 名{{ lg ? ' →' : '' }}</span>
            <span v-else-if="rank" class="text-brand-600">查看完整排行 →</span>
            <span v-else>{{ ranking?.myRankNote || '这段时间还没有你的排名' }}</span>
          </template>
        </StatTile>

        <StatTile
          label="平台账号"
          :value="accountStore.loaded ? accountStore.accounts.length : null"
          unit="个"
          to="/accounts"
          :loading="!accountStore.loaded && !accountStore.error"
          :error="!accountStore.loaded ? accountStore.error : null"
          @retry="accountStore.refresh()"
        >
          <template #chart>
            <span class="flex h-full flex-col justify-end gap-1 text-xs leading-[15px] md:text-[12.5px]">
              <span v-if="md" class="flex items-center gap-1.5 text-body">
                <span class="size-[7px] rounded-full bg-success" aria-hidden="true" />{{ accountStore.healthyCount }} 个正常同步
              </span>
              <span v-if="accountStore.pendingCount" class="flex items-center gap-1.5 text-warning-ink">
                <span class="size-[7px] rounded-full bg-warning" aria-hidden="true" />{{ accountStore.pendingCount }} 个需要处理
              </span>
              <span v-else-if="!md" class="flex items-center gap-1.5 text-body">
                <span class="size-[7px] rounded-full bg-success" aria-hidden="true" />都正常
              </span>
            </span>
          </template>
          <template #foot>
            <span :class="accountStore.pendingCount ? 'text-warning-ink' : 'text-brand-600'">{{ accountStore.pendingCount ? '去处理 →' : '管理账号 →' }}</span>
          </template>
        </StatTile>
      </div>
      <p v-if="!cohort" class="m-0 -mt-1 text-[13px] text-muted md:-mt-2">
        还没登记入学年级，排名先按全校算。<RouterLink to="/settings?section=school">去设置里补上</RouterLink>，就能和同届同学比。
      </p>

      <!-- 每天通过 | 同届排行。手机和平板上排行在前：按宽度换文档顺序（不用 CSS order），读屏和键盘的顺序和看到的一致 -->
      <div class="grid grid-cols-1 gap-4 md:grid-cols-2 lg:grid-cols-[7fr_5fr]">
        <CohortRankCard v-if="!lg" :ranking="ranking" :cohort="cohort" :loading="rankingLoading" :error="rankingError" @retry="loadRanking()" />
        <SectionCard title="近 7 天每天通过">
          <template #actions><RouterLink to="/profile">看更长的趋势 →</RouterLink></template>
          <WeekBars :days="dayValues" :loading="heatmapLoading" :error="heatmapError" :empty="!hasFirstAc" @retry="loadHeatmap()" />
        </SectionCard>
        <CohortRankCard v-if="lg" :ranking="ranking" :cohort="cohort" :loading="rankingLoading" :error="rankingError" @retry="loadRanking()" />
      </div>

      <AnnouncementBoard variant="resources" />
      <PlatformStatusCards />

      <div class="grid grid-cols-1 gap-4 md:grid-cols-2">
        <UpcomingContests :limit="md ? 3 : 2" />
        <CampusFeed :limit="md ? 5 : 3" />
      </div>
    </template>


  </div>
</template>

<style scoped>
.profile-link {
  display: inline-flex;
  flex-shrink: 0;
  align-items: center;
  gap: 6px;
  height: 40px;
  padding: 0 16px;
  border: 1px solid var(--border-strong);
  border-radius: 10px;
  background: var(--bg-elevated);
  color: var(--text-strong);
  font-size: 14px;
}

.profile-link:hover {
  background: var(--bg-subtle);
  color: var(--text-strong);
  text-decoration: none;
}

.activity-dot {
  width: 13px;
  height: 13px;
  border: 1.5px solid var(--border-strong);
  border-radius: 50%;
}

.activity-dot.is-active {
  border-color: var(--success);
  background: var(--success);
}

.sync-link {
  padding: 0;
  border: 0;
  background: none;
  color: var(--brand-600);
  font: inherit;
  font-weight: 600;
  cursor: pointer;
}

.sync-link:disabled {
  color: var(--text-muted);
  cursor: progress;
}

.sync-pill {
  display: inline-flex;
  flex-shrink: 0;
  align-items: center;
  gap: 8px;
  height: 36px;
  padding: 0 12px;
  border: 1px solid var(--border);
  border-radius: 999px;
  background: var(--bg-elevated);
  color: var(--text);
  font: inherit;
  font-size: 13px;
  cursor: pointer;
}

.sync-pill:hover:not(:disabled) {
  border-color: var(--border-strong);
}

.sync-pill:disabled {
  cursor: progress;
}

@media (min-width: 768px) {
  .activity-dot {
    width: 16px;
    height: 16px;
  }
}
</style>
