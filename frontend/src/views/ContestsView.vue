<script setup lang="ts">
import { computed, onBeforeUnmount, onMounted, ref, watch } from 'vue'
import { ElMessage } from 'element-plus'
import http, { del, get, notifyError, post } from '@/api/client'
import ContestDayList from '@/components/contest/ContestDayList.vue'
import FavoriteContests from '@/components/contest/FavoriteContests.vue'
import MiniCalendar from '@/components/contest/MiniCalendar.vue'
import NextContestCard from '@/components/contest/NextContestCard.vue'
import { phaseOf, type ContestRow } from '@/components/contest/types'
import PlatformLogo from '@/components/PlatformLogo.vue'
import ChipSelect from '@/components/ui/ChipSelect.vue'
import EmptyState from '@/components/ui/EmptyState.vue'
import InfoTip from '@/components/ui/InfoTip.vue'
import PageHeader from '@/components/ui/PageHeader.vue'
import Skeleton from '@/components/ui/Skeleton.vue'
import { useBreakpoint } from '@/composables/useBreakpoint'
import { beginnerFriendly } from '@/utils/contestLevel'
import { platformName } from '@/utils/platformBrand'
import { beijingTime, relativeTime } from '@/utils/statistics'

/**
 * 比赛日历：接下来有什么比赛适合我？
 * 顶部是「下一场」卡片和倒计时；筛选片（全部、只看收藏、适合新手、各平台、类别）；按天分组的列表，每场可以收藏。
 * 电脑右边是接下来 4 周的小月历、我的收藏和「加入手机或电脑日历」（导出收藏为 .ics）；手机上导出在顶栏。
 * 平台、类别、只看收藏由接口筛，适合新手在前端按规则筛（utils/contestLevel.ts）。
 */
interface ContestsResponse {
  contests: ContestRow[]
  categories: string[]
  updatedAt: string | null
  favoritedCount: number
}

const { isMobile, lg: wide } = useBreakpoint()
const contests = ref<ContestRow[]>([])
const favorites = ref<ContestRow[]>([])
const categories = ref<string[]>([])
const updatedAt = ref<string | null>(null)
const loading = ref(false)
const loaded = ref(false)
const platform = ref('')
const category = ref('')
const favoritedOnly = ref(false)
const beginnerOnly = ref(false)
const favoritedCount = ref(0)
const exporting = ref(false)
const now = ref(Date.now())
let timer: number | undefined

async function load() {
  loading.value = true
  try {
    const data = await get<ContestsResponse>('/contests', {
      platform: platform.value || undefined,
      category: category.value || undefined,
      favoritedOnly: favoritedOnly.value || undefined
    })
    contests.value = data.contests
    categories.value = data.categories
    updatedAt.value = data.updatedAt
    favoritedCount.value = data.favoritedCount ?? 0
    loaded.value = true
  } catch (e) {
    notifyError(e)
  } finally {
    loading.value = false
  }
}
/** 右栏的收藏：不受筛选影响 */
async function loadFavorites() {
  try {
    const data = await get<ContestsResponse>('/contests', { favoritedOnly: true })
    favorites.value = data.contests
    favoritedCount.value = data.favoritedCount ?? favoritedCount.value
  } catch {
    favorites.value = []
  }
}

onMounted(() => {
  void load()
  void loadFavorites()
  timer = window.setInterval(() => (now.value = Date.now()), 1000)
})
onBeforeUnmount(() => {
  if (timer) window.clearInterval(timer)
})
watch([platform, category, favoritedOnly], load)

/** 收藏只改这一行的标记，不重新拉整页，免得列表跳动；右栏的收藏重新读 */
async function toggleFavorite(target: ContestRow) {
  const next = !target.favorited
  try {
    if (next) await post(`/contests/${target.id}/favorite`)
    else await del(`/contests/${target.id}/favorite`)
    for (const row of contests.value) if (row.id === target.id) row.favorited = next
    target.favorited = next
    favoritedCount.value += next ? 1 : -1
    void loadFavorites()
    if (favoritedOnly.value && !next) void load()
  } catch (e) {
    notifyError(e)
  }
}

/**
 * 访问令牌只在内存里，所以不能用 <a href> 直接下载，
 * 必须走带 Authorization 头的请求再存成文件。
 */
async function exportCalendar() {
  if (!favoritedCount.value) {
    ElMessage.info('先收藏几场比赛，再加入日历')
    return
  }
  exporting.value = true
  try {
    const response = await http.get<Blob>('/contests/calendar.ics', { responseType: 'blob' })
    const objectUrl = URL.createObjectURL(response.data)
    const anchor = document.createElement('a')
    anchor.href = objectUrl
    anchor.download = 'algohub-contests.ics'
    document.body.appendChild(anchor)
    anchor.click()
    anchor.remove()
    setTimeout(() => URL.revokeObjectURL(objectUrl), 1000)
    ElMessage.success(`已导出 ${favoritedCount.value} 场收藏的比赛`)
  } catch (e) {
    notifyError(e)
  } finally {
    exporting.value = false
  }
}

/** 平台筛选片：出现过的平台都留着（筛了一个平台后别的平台不会消失） */
const seenPlatforms = ref<string[]>([])
watch(contests, (list) => {
  const merged = new Set([...seenPlatforms.value, ...list.map((c) => c.platform)])
  if (merged.size !== seenPlatforms.value.length) seenPlatforms.value = [...merged]
})
const shortName = (value: string) => platformName(value).replace(/（.*）$/, '')
const categoryOptions = computed(() => [{ label: '全部类别', value: '' }, ...categories.value.map((c) => ({ label: c, value: c }))])
const allActive = computed(() => !platform.value && !category.value && !favoritedOnly.value && !beginnerOnly.value)
function showAll() {
  platform.value = ''
  category.value = ''
  favoritedOnly.value = false
  beginnerOnly.value = false
}

const rows = computed(() => (beginnerOnly.value ? contests.value.filter((c) => beginnerFriendly(c)) : contests.value))
/** 下一场：列表里正在进行或最早开始的那场 */
const next = computed(() =>
  rows.value
    .filter((c) => {
      const phase = phaseOf(c, now.value)
      return phase === 'upcoming' || phase === 'live'
    })
    .sort((a, b) => a.startAt.localeCompare(b.startAt))[0] ?? null
)
const upcomingFavorites = computed(() =>
  favorites.value.filter((c) => phaseOf(c, now.value) !== 'ended').sort((a, b) => a.startAt.localeCompare(b.startAt))
)

function scrollToDay(date: string) {
  document.getElementById(`contest-day-${date}`)?.scrollIntoView({ behavior: 'smooth', block: 'start' })
}
</script>

<template>
  <div class="page" data-test="page">
    <PageHeader title="比赛日历">
      <template v-if="!isMobile" #description>各平台近期的比赛，时间都是北京时间。收藏后可以加入手机或电脑日历。</template>
      <template #meta>
        <span class="flex items-center gap-1 text-[12.5px] text-muted" data-test="contests-updated">
          <template v-if="updatedAt"><span :title="beijingTime(updatedAt)">比赛数据 {{ relativeTime(updatedAt) }}更新</span></template>
          <template v-else>比赛数据还没同步过</template>
          <InfoTip title="比赛数据从哪来">各平台公开的比赛列表，由后台定时刷新；源站改期、取消后，这里下次刷新才会变。</InfoTip>
        </span>
      </template>
      <template v-if="!isMobile && !wide" #actions>
        <el-button :loading="exporting" :disabled="!favoritedCount" @click="exportCalendar">
          <span v-if="!exporting" class="i-lucide-calendar-plus mr-1.5" aria-hidden="true" />加入日历（{{ favoritedCount }}）
        </el-button>
      </template>
      <template #mobile-actions>
        <button type="button" class="topbar-icon" aria-label="把收藏的比赛加入日历" :disabled="exporting" data-test="export-ics-mobile" @click="exportCalendar">
          <span class="i-lucide-calendar-plus text-[21px]" aria-hidden="true" />
        </button>
      </template>
    </PageHeader>

    <div class="grid grid-cols-1 items-start gap-4 lg:grid-cols-[minmax(0,1fr)_320px] xl:grid-cols-[minmax(0,1fr)_340px]">
      <div class="min-w-0 flex flex-col gap-4">
        <NextContestCard v-if="next" :contest="next" :now="now" @favorite="toggleFavorite" />
        <Skeleton v-else-if="!loaded" class="h-[176px] rounded-2xl" />

        <div class="chips" :class="{ 'is-scroll': isMobile }" data-test="contest-filters">
          <button type="button" class="filter-chip" :class="{ 'is-active': allActive }" :aria-pressed="allActive" @click="showAll">全部</button>
          <button type="button" class="filter-chip" :class="{ 'is-active': favoritedOnly }" :aria-pressed="favoritedOnly" @click="favoritedOnly = !favoritedOnly">
            只看收藏<span v-if="favoritedCount" class="num">&nbsp;{{ favoritedCount }}</span>
          </button>
          <button type="button" class="filter-chip" :class="{ 'is-active': beginnerOnly }" :aria-pressed="beginnerOnly" @click="beginnerOnly = !beginnerOnly">适合新手</button>
          <button
            v-for="p in seenPlatforms"
            :key="p"
            type="button"
            class="filter-chip with-logo"
            :class="{ 'is-active': platform === p }"
            :aria-pressed="platform === p"
            @click="platform = platform === p ? '' : p"
          >
            <PlatformLogo :platform="p" :size="16" />{{ shortName(p) }}
          </button>
          <ChipSelect v-if="categories.length" v-model="category" :options="categoryOptions" label="类别" default-value="" />
        </div>

        <div v-if="!loaded" class="flex flex-col gap-3">
          <Skeleton v-for="n in 3" :key="n" class="h-[88px] rounded-card" />
        </div>
        <EmptyState
          v-else-if="!rows.length && allActive"
          icon="i-lucide-calendar-x"
          title="暂时没有比赛数据"
          description="各平台的比赛列表还没同步过，管理员可以在后台刷新一次。"
        />
        <EmptyState
          v-else-if="!rows.length"
          icon="i-lucide-filter-x"
          title="没有符合筛选的比赛"
          :description="favoritedOnly ? '收藏的比赛都已经结束了，或者还没有收藏。' : '换个平台或类别看看。'"
          action="看全部比赛"
          @action="showAll"
        />
        <ContestDayList v-else :rows="rows" :now="now" :class="{ 'opacity-60': loading }" @favorite="toggleFavorite" />
      </div>

      <aside v-if="wide" class="min-w-0 flex flex-col gap-4">
        <MiniCalendar :rows="rows" :now="now" @select="scrollToDay" />
        <FavoriteContests :favorites="upcomingFavorites" :count="favoritedCount" :now="now" :exporting="exporting" @favorite="toggleFavorite" @export="exportCalendar" />
      </aside>
    </div>
  </div>
</template>

<style scoped>
.chips {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  gap: 8px;
}

/* 手机上筛选片横向滚动，贴到页面左右边缘 */
.chips.is-scroll {
  flex-wrap: nowrap;
  margin: 0 -16px;
  padding: 0 16px;
  overflow-x: auto;
  scrollbar-width: none;
}

.chips.is-scroll::-webkit-scrollbar {
  display: none;
}

.chips .filter-chip {
  gap: 6px;
}

/* 带平台图标的筛选片，左边留得少一点（画布） */
.chips .filter-chip.with-logo {
  padding-left: 8px;
}
</style>
