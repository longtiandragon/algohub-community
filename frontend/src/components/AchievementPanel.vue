<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { get, notifyError } from '@/api/client'
import { platformName } from '@/utils/platformBrand'

interface Achievement {
  code: string
  category: string
  name: string
  description: string
  unlocked: boolean
  progress: number
  target: number
  progressText: string
}

interface CategoryCoverage {
  category: string
  solved: number
}

interface DifficultyPeak {
  platform: string
  maxDifficulty: number | null
  ratedSolved: number
}

interface AchievementResponse {
  unlockedCount: number
  totalCount: number
  achievements: Achievement[]
  nextTarget: Achievement | null
  categoryCoverage: CategoryCoverage[]
  difficultyPeaks: DifficultyPeak[]
  note: string
}

interface AchievementGroup {
  category: string
  items: Achievement[]
}

/** 分组展示顺序；后端若新增分类，按返回顺序追加在末尾，不会被丢掉。 */
const CATEGORY_ORDER = ['题量', '坚持', '难度', '广度', '比赛']

const PLATFORM_LABELS: Record<string, string> = {
  CODEFORCES: 'Codeforces',
  ATCODER: 'AtCoder',
  LUOGU: '洛谷',
  NOWCODER: '牛客',
  VJUDGE: 'VJudge',
  LEETCODE: 'LeetCode'
}

/** compact：放在侧栏时只显示下一个目标，全部成就与难度高点点开再看 */
const props = defineProps<{ compact?: boolean; userId?: number }>()
const showAll = ref(false)
const expanded = computed(() => !props.compact || showAll.value)

const data = ref<AchievementResponse | null>(null)
const loading = ref(false)

function platformLabel(platform: string): string {
  return PLATFORM_LABELS[platform] ?? platformName(platform)
}

/** 进度百分比统一夹到 0–100，避免后端进度超过目标时进度条溢出。 */
function percentOf(item: Achievement): number {
  if (item.target <= 0) return item.unlocked ? 100 : 0
  return Math.min(100, Math.max(0, Math.round((item.progress / item.target) * 100)))
}

const groups = computed<AchievementGroup[]>(() => {
  const list = data.value?.achievements ?? []
  const buckets = new Map<string, Achievement[]>()
  for (const item of list) {
    const bucket = buckets.get(item.category)
    if (bucket) {
      bucket.push(item)
    } else {
      buckets.set(item.category, [item])
    }
  }
  const ordered: AchievementGroup[] = []
  for (const category of CATEGORY_ORDER) {
    const items = buckets.get(category)
    if (items) {
      ordered.push({ category, items })
      buckets.delete(category)
    }
  }
  for (const [category, items] of buckets) {
    ordered.push({ category, items })
  }
  return ordered
})

/** 有难度记录的平台才画「最高难度」行，其余只在末尾点名提示。 */
const ratedPeaks = computed(() =>
  (data.value?.difficultyPeaks ?? []).filter((p) => p.maxDifficulty !== null)
)

const unratedPeakNames = computed(() =>
  (data.value?.difficultyPeaks ?? [])
    .filter((p) => p.maxDifficulty === null)
    .map((p) => platformLabel(p.platform))
)

const nextTargetPercent = computed(() => {
  const next = data.value?.nextTarget
  return next ? percentOf(next) : 0
})

async function load() {
  loading.value = true
  try {
    data.value = await get<AchievementResponse>(props.userId ? `/activity/achievements/${props.userId}` : '/activity/achievements')
  } catch (e) {
    notifyError(e)
  } finally {
    loading.value = false
  }
}

onMounted(() => {
  void load()
})
</script>

<template>
  <div class="card" v-loading="loading">
    <div class="card-head">
      <span class="card-title">成就与里程碑</span>
      <div class="head-actions">
        <span v-if="data" class="badge-soft">
          已解锁 {{ data.unlockedCount }} / {{ data.totalCount }}
        </span>
        <el-button size="small" :loading="loading" @click="load">刷新</el-button>
      </div>
    </div>

    <div v-if="data?.nextTarget" class="next-target">
      <div class="next-head">
        <span class="next-flag">下一个目标</span>
        <span class="next-name">{{ data.nextTarget.name }}</span>
      </div>
      <p class="next-desc muted">{{ data.nextTarget.description }}</p>
      <el-progress :percentage="nextTargetPercent" :stroke-width="8" :show-text="false" />
      <div class="next-progress">{{ data.nextTarget.progressText }}</div>
    </div>

    <el-button
      v-if="compact && groups.length"
      link
      type="primary"
      class="toggle-all"
      @click="showAll = !showAll"
    >
      {{ showAll ? '收起' : `查看全部 ${data?.totalCount ?? ''} 项成就` }}
    </el-button>

    <template v-if="expanded">
    <div v-for="group in groups" :key="group.category" class="ach-group">
      <div class="group-title">{{ group.category }}</div>
      <!-- 已解锁的只列成小标签；还在进行中的保留进度条，那才是接下来要看的 -->
      <div v-if="group.items.some((i) => i.unlocked)" class="ach-chips">
        <el-tooltip
          v-for="item in group.items.filter((i) => i.unlocked)"
          :key="item.code"
          :content="item.description"
          placement="top"
        >
          <span class="ach-chip" tabindex="0"><span aria-hidden="true">✓</span> {{ item.name }}</span>
        </el-tooltip>
      </div>
      <div v-if="group.items.some((i) => !i.unlocked)" class="ach-grid">
        <el-tooltip
          v-for="item in group.items.filter((i) => !i.unlocked)"
          :key="item.code"
          :content="item.description"
          placement="top"
        >
          <div class="ach-tile" :class="item.unlocked ? 'is-unlocked' : 'is-locked'">
            <div class="tile-head">
              <span class="tile-mark" aria-hidden="true">{{ item.unlocked ? '✓' : '🔒' }}</span>
              <span class="tile-name">{{ item.name }}</span>
            </div>
            <div class="tile-state">{{ item.unlocked ? '已解锁' : item.progressText }}</div>
            <el-progress
              v-if="!item.unlocked"
              class="tile-bar"
              :percentage="percentOf(item)"
              :stroke-width="4"
              :show-text="false"
            />
          </div>
        </el-tooltip>
      </div>
    </div>

    <el-empty v-if="!loading && !groups.length" description="暂无成就数据" :image-size="70" />

    <p v-if="data?.note" class="muted note-text">{{ data.note }}</p>

    <div v-if="data?.difficultyPeaks.length" class="peak-block">
      <div class="group-title">难度高点</div>
      <div v-for="peak in ratedPeaks" :key="peak.platform" class="peak-line">
        <span class="peak-platform">{{ platformLabel(peak.platform) }}</span>
        <span class="peak-value">最高难度 {{ peak.maxDifficulty }}</span>
        <span class="muted">（带难度的通过题 {{ peak.ratedSolved }} 道）</span>
      </div>
      <p v-if="unratedPeakNames.length" class="muted peak-note">
        {{ unratedPeakNames.join('、') }}：无带难度记录。
      </p>
      <p class="muted peak-note">难度只在同一平台内比较，跨平台的数值不具有可比性。</p>
    </div>
    </template>
  </div>
</template>

<style scoped>
.head-actions {
  display: flex;
  align-items: center;
  gap: 8px;
  flex-wrap: wrap;
}

.next-target {
  border: 1px solid var(--brand-400);
  background: var(--brand-soft);
  border-radius: var(--radius);
  padding: 14px;
  margin-bottom: 16px;
}

.next-head {
  display: flex;
  align-items: center;
  gap: 8px;
  flex-wrap: wrap;
}

.next-flag {
  display: inline-flex;
  align-items: center;
  padding: 1px 8px;
  border-radius: 999px;
  font-size: 12px;
  background: var(--bg-elevated);
  color: var(--brand-500);
  border: 1px solid var(--brand-400);
}

.next-name {
  font-weight: 640;
  font-size: 15px;
  color: var(--text-strong);
  word-break: break-word;
}

.next-desc {
  margin: 6px 0 10px;
}

.next-progress {
  margin-top: 6px;
  font-size: 12.5px;
  color: var(--text-strong);
}

.ach-group {
  margin-bottom: 14px;
}

.group-title {
  font-size: 12.5px;
  font-weight: 600;
  color: var(--text-muted);
  margin-bottom: 8px;
}

.ach-grid {
  display: grid;
  grid-template-columns: repeat(auto-fill, minmax(200px, 1fr));
  gap: 8px;
}

.ach-chips {
  display: flex;
  flex-wrap: wrap;
  gap: 6px;
  margin-bottom: 8px;
}

.ach-chip {
  display: inline-flex;
  align-items: center;
  gap: 4px;
  padding: 2px 10px;
  border-radius: 999px;
  font-size: 12.5px;
  color: var(--brand-500);
  background: var(--brand-soft);
  border: 1px solid color-mix(in srgb, var(--brand-400) 40%, transparent);
  cursor: default;
}

.ach-tile {
  border: 1px solid var(--border);
  border-radius: var(--radius-sm);
  padding: 10px 12px;
  background: var(--bg-elevated);
  min-width: 0;
}

.ach-tile.is-unlocked {
  border-color: var(--brand-400);
  background: var(--brand-soft);
}

/* 未解锁用去饱和 + 锁形标记同时表达，不只靠颜色区分 */
.ach-tile.is-locked {
  background: var(--bg-subtle);
  filter: grayscale(1);
  opacity: 0.85;
}

.tile-head {
  display: flex;
  align-items: baseline;
  gap: 6px;
  min-width: 0;
}

.tile-mark {
  font-size: 12px;
  flex: 0 0 auto;
}

.ach-tile.is-unlocked .tile-mark {
  color: var(--brand-500);
  font-weight: 700;
}

.tile-name {
  font-size: 13px;
  font-weight: 600;
  color: var(--text-strong);
  word-break: break-word;
  min-width: 0;
}

.ach-tile.is-locked .tile-name {
  color: var(--text-muted);
  font-weight: 500;
}

.tile-state {
  margin-top: 4px;
  font-size: 12px;
  color: var(--text-muted);
}

.tile-bar {
  margin-top: 6px;
}

.note-text {
  margin: 12px 0 0;
}

.peak-block {
  margin-top: 14px;
  padding-top: 12px;
  border-top: 1px solid var(--border);
}

.peak-line {
  display: flex;
  flex-wrap: wrap;
  align-items: baseline;
  gap: 4px 8px;
  font-size: 13px;
  color: var(--text);
  padding: 2px 0;
}

.peak-platform {
  color: var(--text-strong);
  font-weight: 600;
}

.peak-value {
  white-space: nowrap;
}

.peak-note {
  margin: 6px 0 0;
}

@media (max-width: 560px) {
  .ach-grid {
    grid-template-columns: 1fr;
  }
}

.toggle-all {
  margin-top: 4px;
}
</style>
