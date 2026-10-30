<script setup lang="ts">
import { computed, onMounted, reactive, ref } from 'vue'
import { categoryName, problemLabel } from '@/utils/problemLabel'
import { ElMessage } from 'element-plus'
import { del, get, notifyError, post } from '@/api/client'
import type { PlatformCapability } from '@/api/types'
import PlatformLogo from '@/components/PlatformLogo.vue'
import DifficultyTag from '@/components/DifficultyTag.vue'
import GoalRing from '@/components/activity/GoalRing.vue'
import InfoTip from '@/components/ui/InfoTip.vue'
import Skeleton from '@/components/ui/Skeleton.vue'
import { httpUrl } from '@/utils/url'

/**
 * 本周目标：自己定这周新通过几题（可以只算某个算法方向或某个平台），进度由通过记录实时算，用圆环显示。
 * 可以修改、取消；「本周通过了哪些题」展开这周的题和前几周的完成情况。
 * flat：放在手机的底部弹层里时不要卡片边框。保存、取消后发出 changed，页面上别处的进度跟着刷新。
 */
withDefaults(defineProps<{ flat?: boolean }>(), { flat: false })
const emit = defineEmits<{ changed: [] }>()

interface WeekProblem {
  id: number
  platform: string
  problemKey: string
  title: string | null
  url: string | null
  difficultyLabel: string | null
  difficultyValue: number | null
  acAt: string
  firstAcConfidence: string
}

interface HistoryWeek {
  weekStart: string
  target: number
  solved: number
  category: string | null
  platform: string | null
  reached: boolean
}

interface WeeklyGoal {
  weekStart: string
  weekEnd: string
  daysLeft: number
  hasGoal: boolean
  target: number | null
  category: string | null
  platform: string | null
  solved: number
  remaining?: number
  reached?: boolean
  percent?: number
  addedWithoutTime: number
  recentProblems: WeekProblem[]
  history: HistoryWeek[]
  note: string
}

const goal = ref<WeeklyGoal | null>(null)
const loading = ref(false)
const editing = ref(false)
const platforms = ref<PlatformCapability[]>([])
const categories = ref<string[]>([])
const expanded = ref(false)

const form = reactive<{ target: number; category: string; platform: string }>({
  target: 5,
  category: '',
  platform: ''
})

const percent = computed(() => Math.min(100, Math.round(goal.value?.percent ?? 0)))
const scopeText = computed(() => {
  const parts: string[] = []
  if (goal.value?.category) parts.push(goal.value.category)
  if (goal.value?.platform) parts.push(platformLabel(goal.value.platform))
  return parts.length ? parts.join(' · ') : '全部平台与方向'
})

async function load() {
  loading.value = true
  try {
    goal.value = await get<WeeklyGoal>('/goals/weekly')
    if (goal.value.hasGoal) {
      form.target = goal.value.target ?? 5
      form.category = goal.value.category ?? ''
      form.platform = goal.value.platform ?? ''
    }
  } catch (e) {
    notifyError(e)
  } finally {
    loading.value = false
  }
}

async function loadOptions() {
  try {
    platforms.value = await get<PlatformCapability[]>('/platforms/capabilities')
  } catch {
    platforms.value = []
  }
  try {
    // 算法方向的取值与算法地图同一套口径
    const map = await get<{ categories: { category: string }[] }>('/skill-map')
    categories.value = (map.categories ?? []).map((c) => c.category)
  } catch {
    categories.value = []
  }
}

async function save() {
  try {
    goal.value = await post<WeeklyGoal>('/goals/weekly', {
      target: form.target,
      category: form.category || null,
      platform: form.platform || null
    })
    editing.value = false
    ElMessage.success('本周目标已保存')
    emit('changed')
  } catch (e) {
    notifyError(e)
  }
}

async function clear() {
  try {
    goal.value = await del<WeeklyGoal>('/goals/weekly')
    editing.value = false
    ElMessage.success('已取消本周目标，通过记录不受影响')
    emit('changed')
  } catch (e) {
    notifyError(e)
  }
}

onMounted(() => {
  loadOptions()
  load()
})

function platformLabel(value: string) {
  return platforms.value.find((p) => p.platform === value)?.displayName ?? value
}

function problemText(item: WeekProblem) {
  return problemLabel(item)
}

function shortDate(value: string) {
  const parts = value.split('-')
  return parts.length === 3 ? `${Number(parts[1])}/${Number(parts[2])}` : value
}

function beijingDay(value: string) {
  const date = new Date(value)
  if (Number.isNaN(date.getTime())) return '—'
  return date.toLocaleDateString('zh-CN', { timeZone: 'Asia/Shanghai', month: 'numeric', day: 'numeric' })
}
</script>

<template>
  <section class="goal-card" :class="{ flat }" data-test="weekly-goal">
    <div class="flex flex-wrap items-center justify-between gap-x-2 gap-y-1">
      <h2 class="m-0 flex items-center gap-1 text-[15px] text-ink font-semibold">
        本周专项计划
        <InfoTip v-if="goal?.note" title="本周目标怎么算">{{ goal.note }}</InfoTip>
      </h2>
      <span v-if="goal" class="text-xs text-muted">{{ shortDate(goal.weekStart) }} – {{ shortDate(goal.weekEnd) }} · 还剩 {{ goal.daysLeft }} 天</span>
    </div>
    <p class="m-0 text-xs text-muted">仅限本周，可按平台或分类练习。<RouterLink to="/reminders">设置持续目标与提醒 →</RouterLink></p>

    <div v-if="!goal" class="flex items-center gap-4">
      <Skeleton class="size-16 rounded-full" />
      <div class="flex flex-1 flex-col gap-2">
        <Skeleton class="h-6 w-1/2" />
        <Skeleton class="h-3.5 w-3/4" />
      </div>
    </div>

    <template v-else-if="!editing">
      <div v-if="goal.hasGoal" class="flex items-center gap-4" data-test="goal-progress">
        <div class="relative flex shrink-0 items-center justify-center">
          <GoalRing :solved="goal.solved" :target="goal.target" :size="64" :stroke="7" />
          <span class="num absolute text-[13px] text-ink font-semibold">{{ percent }}%</span>
        </div>
        <div class="min-w-0 flex flex-1 flex-col gap-1">
          <span class="flex items-baseline gap-1">
            <span class="num text-[26px] text-ink font-semibold leading-none">{{ goal.solved }}</span>
            <span class="num text-sm text-muted">/ {{ goal.target }} 题</span>
          </span>
          <span class="text-[13px]" :class="goal.reached ? 'text-success-ink font-semibold' : 'text-body'">
            {{ goal.reached ? '已完成本周目标' : `还差 ${goal.remaining} 题` }}
          </span>
          <span class="flex flex-wrap items-center gap-1 text-xs text-muted">
            统计范围：<PlatformLogo v-if="goal.platform" :platform="goal.platform" :size="14" />{{ scopeText }}
          </span>
        </div>
      </div>
      <div v-else class="flex flex-col items-start gap-2.5">
        <p class="m-0 text-[13.5px] text-body leading-relaxed">
          这周还没定目标。本周你已经新通过 <strong class="num text-ink">{{ goal.solved }}</strong> 题，给自己定一个数，也可以只盯住某个算法方向。
        </p>
        <el-button type="primary" @click="editing = true">定个目标</el-button>
      </div>

      <div v-if="goal.hasGoal || goal.recentProblems.length || goal.history.length" class="flex flex-wrap items-center gap-x-4 gap-y-1">
        <el-button v-if="goal.hasGoal" link type="primary" @click="editing = true">修改</el-button>
        <el-button v-if="goal.hasGoal" link type="danger" @click="clear">取消目标</el-button>
        <el-button v-if="goal.recentProblems.length || goal.history.length" link type="primary" :aria-expanded="expanded" @click="expanded = !expanded">
          {{ expanded ? '收起' : '本周通过了哪些题' }}
        </el-button>
      </div>

      <template v-if="expanded">
        <ul v-if="goal.recentProblems.length" class="m-0 flex list-none flex-col p-0">
          <li v-for="item in goal.recentProblems" :key="item.id" class="week-item">
            <a v-if="httpUrl(item.url)" :href="httpUrl(item.url)" target="_blank" rel="noopener noreferrer" class="min-w-0 truncate text-[13px] text-ink hover:text-brand-600">{{ problemText(item) }}</a>
            <span v-else class="min-w-0 truncate text-[13px] text-ink">{{ problemText(item) }}</span>
            <span class="flex shrink-0 items-center gap-2 text-xs text-muted">
              <DifficultyTag :platform="item.platform" :value="item.difficultyValue" :label="item.difficultyLabel" />
              {{ beijingDay(item.acAt) }}
            </span>
          </li>
        </ul>
        <p v-else class="m-0 text-[13px] text-muted">本周还没有带通过时间的新记录。</p>
        <div v-if="goal.history.length" class="flex flex-wrap items-center gap-1.5">
          <span class="text-xs text-muted">前几周</span>
          <span v-for="h in goal.history" :key="h.weekStart" class="history-chip num" :class="{ 'is-reached': h.reached }">
            {{ shortDate(h.weekStart) }} {{ h.solved }}/{{ h.target }}
          </span>
        </div>
      </template>
    </template>

    <el-form v-else label-position="top" class="goal-form" @submit.prevent="save">
      <el-form-item label="本周新通过几题">
        <el-input-number v-model="form.target" :min="1" :max="500" />
      </el-form-item>
      <el-form-item label="只算某个算法方向（可不选）">
        <el-select v-model="form.category" clearable placeholder="全部算法方向" class="w-full" :empty-values="['']">
          <el-option v-for="c in categories" :key="c" :label="categoryName(c)" :value="c" />
        </el-select>
      </el-form-item>
      <el-form-item label="只算某个平台（可不选）">
        <el-select v-model="form.platform" clearable placeholder="全部平台" class="w-full" :empty-values="['']">
          <el-option v-for="p in platforms" :key="p.platform" :label="p.displayName" :value="p.platform" />
        </el-select>
      </el-form-item>
      <div class="flex gap-2">
        <el-button type="primary" native-type="submit">保存</el-button>
        <el-button @click="editing = false">取消</el-button>
      </div>
    </el-form>
  </section>
</template>

<style scoped>
.goal-card {
  display: flex;
  flex-direction: column;
  gap: 12px;
  padding: 18px 20px;
  border: 1px solid var(--border);
  border-radius: var(--radius);
  background: var(--bg-elevated);
}

.goal-card.flat {
  padding: 0;
  border: 0;
  background: transparent;
}

.week-item {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 10px;
  min-height: 36px;
  border-top: 1px solid var(--border-soft);
}

.history-chip {
  padding: 1px 8px;
  border: 1px solid var(--border);
  border-radius: 999px;
  color: var(--text-muted);
  font-size: 12px;
}

.history-chip.is-reached {
  border-color: color-mix(in srgb, var(--success) 50%, transparent);
  color: var(--success-ink);
}

.goal-form :deep(.el-form-item) {
  margin-bottom: 12px;
}
</style>
