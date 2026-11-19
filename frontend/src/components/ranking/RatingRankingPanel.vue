<script setup lang="ts">
import { computed, onBeforeUnmount, ref, useId, watch } from 'vue'
import { get, notifyError } from '@/api/client'
import PlatformLogo from '@/components/PlatformLogo.vue'
import EmptyState from '@/components/ui/EmptyState.vue'
import SegmentedControl from '@/components/ui/SegmentedControl.vue'
import Skeleton from '@/components/ui/Skeleton.vue'
import { useAuthStore } from '@/stores/auth'

type Scope = 'GLOBAL' | 'SCHOOL'
type Platform = 'CODEFORCES' | 'ATCODER' | 'NOWCODER' | 'LEETCODE_CN' | 'LUOGU'
interface RatingRow {
  rank: number
  userId: number
  displayName: string
  realName?: string | null
  className?: string | null
  schoolName?: string | null
  currentRating: number
  ratingChange?: number | null
  maxRating?: number | null
  ratedContests?: number | null
}
interface RatingRanking {
  participantCount: number
  rows: RatingRow[]
}

const auth = useAuthStore()
const scopes: { value: Scope; label: string }[] = [
  { value: 'GLOBAL', label: '总榜' }, { value: 'SCHOOL', label: '校内榜' }
]
const platforms: { value: Platform; label: string }[] = [
  { value: 'CODEFORCES', label: 'Codeforces' },
  { value: 'ATCODER', label: 'AtCoder' },
  { value: 'NOWCODER', label: '牛客' },
  { value: 'LEETCODE_CN', label: 'LeetCode' },
  { value: 'LUOGU', label: '洛谷' }
]
const platformGroup = useId()
const preferenceKey = `algohub.rating-platform.${auth.me?.id ?? 'guest'}`
function initialPlatform(): Platform {
  try {
    const saved = localStorage.getItem(preferenceKey)
    return platforms.find(option => option.value === saved)?.value ?? 'CODEFORCES'
  } catch {
    return 'CODEFORCES'
  }
}

const platform = ref<Platform>(initialPlatform())
const scope = ref<Scope>(auth.approvedMembership ? 'SCHOOL' : 'GLOBAL')
const selectedLabel = computed(() => platforms.find(option => option.value === platform.value)!.label)
const heading = computed(() => `${selectedLabel.value} · ${scope.value === 'SCHOOL' ? '校内榜' : '总榜'}`)
const board = ref<RatingRanking | null>(null)
const loading = ref(false)
const failed = ref(false)
const PAGE_SIZE = 20
const MAX_LIMIT = 200
const limit = ref(PAGE_SIZE)
let request = 0
async function load() {
  const current = ++request
  loading.value = true
  failed.value = false
  try {
    const result = await get<RatingRanking>('/stats/ranking/rating', {
      scope: scope.value, platform: platform.value, limit: limit.value
    })
    if (current === request) board.value = result
  } catch (e) {
    if (current === request) {
      failed.value = true
      notifyError(e)
    }
  } finally {
    if (current === request) loading.value = false
  }
}
function showMore() {
  limit.value = Math.min(MAX_LIMIT, limit.value + PAGE_SIZE)
  void load()
}
watch(platform, value => {
  try { localStorage.setItem(preferenceKey, value) } catch { /* 当前浏览器禁用了本地存储 */ }
})
watch([platform, scope], () => {
  board.value = null
  limit.value = PAGE_SIZE
  void load()
}, { immediate: true })
onBeforeUnmount(() => { request++ })

const name = (row: RatingRow) => (scope.value === 'SCHOOL' && row.realName) || row.displayName
const affiliation = (row: RatingRow) => scope.value === 'SCHOOL' ? row.className : row.schoolName
const changeLabel = (change?: number | null) => change == null ? '—' : change > 0 ? `+${change}` : String(change)
</script>

<template>
  <section data-test="rating-board">
    <fieldset class="platforms" aria-label="Rating 平台">
      <legend class="sr-only">Rating 平台</legend>
      <label v-for="option in platforms" :key="option.value" class="platform" :class="{ active: platform === option.value }">
        <input v-model="platform" type="radio" class="sr-only" :name="platformGroup" :value="option.value">
        <PlatformLogo :platform="option.value" :size="22" title="" aria-hidden="true" />
        <span>{{ option.label }}</span>
      </label>
    </fieldset>

    <div class="board" :aria-busy="loading">
      <header class="board-head">
        <div>
          <h2>{{ heading }}</h2>
          <p><span v-if="board"><strong class="num">{{ board.participantCount }}</strong> 人上榜 · </span>按当前 Rating 排名，同分并列。</p>
        </div>
        <SegmentedControl v-if="auth.approvedMembership" v-model="scope" :options="scopes" label="Rating 榜范围" />
      </header>
      <div v-if="!board && loading" class="grid gap-4 p-5" role="status" aria-label="正在加载 Rating 榜">
        <Skeleton v-for="n in 4" :key="n" class="h-8" />
      </div>
      <table v-else-if="board?.rows.length" :aria-label="heading">
        <thead><tr>
          <th scope="col">名次</th><th scope="col">学生</th><th scope="col">当前 Rating</th>
          <th scope="col">最近一场涨跌</th><th scope="col">历史最高</th><th scope="col">计分场次</th>
        </tr></thead>
        <tbody><tr v-for="row in board.rows" :key="row.userId" :class="{ self: row.userId === auth.me?.id }">
          <td class="position num"><span class="rank" :class="{ leading: row.rank <= 3 }">{{ row.rank }}</span></td>
          <td class="identity">
            <RouterLink :to="`/users/${row.userId}`">{{ name(row) }}</RouterLink>
            <span v-if="row.userId === auth.me?.id" class="me">我</span>
            <small v-if="affiliation(row)">{{ affiliation(row) }}</small>
          </td>
          <td class="current num"><span class="mobile-label">当前 Rating</span>{{ row.currentRating }}</td>
          <td class="delta num" :class="{ gain: (row.ratingChange ?? 0) > 0, loss: (row.ratingChange ?? 0) < 0 }">
            <span class="mobile-label">最近一场涨跌</span>{{ changeLabel(row.ratingChange) }}
          </td>
          <td class="peak num"><span class="mobile-label">历史最高</span>{{ row.maxRating ?? '—' }}</td>
          <td class="games num"><span class="mobile-label">计分场次</span>{{ row.ratedContests ?? '—' }}</td>
        </tr></tbody>
      </table>
      <EmptyState v-else-if="board" title="暂无 Rating 记录" description="账号验证归属并同步到该平台的计分比赛后，即可参与排行。" :bordered="false" />
      <div v-if="failed" class="flex items-center justify-between gap-2 p-4" role="alert">
        <span>Rating 榜加载失败</span><el-button @click="load">重试</el-button>
      </div>
      <footer v-else-if="board && board.rows.length < board.participantCount" class="flex items-center justify-between gap-2 p-4 text-sm text-muted">
        已显示 {{ board.rows.length }} 人，共 {{ board.participantCount }} 人
        <el-button v-if="limit < MAX_LIMIT" :loading="loading" @click="showMore">再看 20 名</el-button>
      </footer>
    </div>
  </section>
</template>

<style scoped>
.platforms { display: grid; grid-template-columns: repeat(5, minmax(0, 1fr)); gap: 8px; min-width: 0; margin: 0 0 16px; padding: 0; border: 0; }
.platform { position: relative; display: flex; align-items: center; justify-content: center; gap: 9px; min-height: 48px; padding: 4px 8px; border: 1px solid var(--border); border-radius: var(--radius-sm); background: var(--bg-elevated); color: var(--text); font-size: 14px; cursor: pointer; }
.platform:hover { border-color: var(--border-strong); }
.platform.active { border-color: var(--brand-500); background: var(--brand-soft); color: var(--text-strong); font-weight: 700; }
.platform:has(input:focus-visible) { outline: 2px solid var(--brand-500); outline-offset: 2px; }
.board { overflow: hidden; border: 1px solid var(--border); border-radius: var(--radius); background: var(--bg-elevated); }
.board-head { display: flex; align-items: center; justify-content: space-between; gap: 12px; padding: 20px; }
.board-head h2 { margin: 0; font-size: 21px; color: var(--text-strong); }
.board-head p { margin: 4px 0 0; color: var(--text-muted); font-size: 13px; }
.board-head strong { color: var(--text-strong); }
table { width: 100%; border-collapse: collapse; }
th, td { padding: 13px 18px; text-align: right; }
th { background: var(--bg-subtle); color: var(--text-muted); font-size: 13px; font-weight: 500; }
td { border-top: 1px solid var(--border-soft); color: var(--text); font-size: 14px; }
th:nth-child(-n+2), td:nth-child(-n+2) { text-align: left; }
.position { color: var(--text-muted); font-weight: 600; }
.rank { display: inline-grid; min-width: 28px; min-height: 28px; place-content: center; border-radius: var(--radius-xs); }
.rank.leading { background: var(--bg-subtle); color: var(--text-strong); }
.identity { overflow-wrap: anywhere; }
.identity a { color: var(--text-strong); font-weight: 600; text-decoration: none; }
.identity a:hover { text-decoration: underline; }
.identity small, .mobile-label { color: var(--text-muted); font-size: 12px; font-weight: normal; }
.identity small { display: block; margin-top: 2px; }
.me { margin-left: 5px; color: var(--brand-600); font-size: 12px; }
.self { background: var(--brand-soft); }
.current { color: var(--text-strong); font-size: 18px; font-weight: 700; }
.gain { color: var(--success-ink); }
.loss { color: var(--danger-ink); }
.mobile-label { display: none; }
@media (max-width: 720px) {
  .platforms { grid-template-columns: minmax(max-content, 1.3fr) repeat(4, minmax(max-content, 1fr)); gap: 5px; }
  .platform { flex-direction: column; gap: 4px; min-height: 57px; padding: 5px 1px; font-size: 12px; }
  .board-head { flex-wrap: wrap; padding: 15px; }
  .board-head h2 { font-size: 18px; }
  thead { position: absolute; width: 1px; height: 1px; overflow: hidden; clip-path: inset(50%); }
  tbody, table { display: block; }
  tbody tr { display: grid; grid-template-columns: auto 1fr 1fr 1fr; grid-template-areas: "rank student student current" ". delta peak games"; column-gap: 8px; row-gap: 12px; align-items: center; padding: 14px; border-top: 1px solid var(--border-soft); }
  td { border: 0; padding: 0; }
  .position { grid-area: rank; min-width: 20px; }
  .identity { grid-area: student; }
  .current { grid-area: current; }
  .delta { grid-area: delta; text-align: left; }
  .peak { grid-area: peak; text-align: center; }
  .games { grid-area: games; }
  .mobile-label { display: block; margin-bottom: 3px; font-family: var(--font-sans); }
}
</style>
