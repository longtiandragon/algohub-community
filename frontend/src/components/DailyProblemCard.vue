<script setup lang="ts">
import DifficultyTag from '@/components/DifficultyTag.vue'
import { problemCode } from '@/utils/problemLabel'
import PlatformLogo from '@/components/PlatformLogo.vue'
import InfoTip from '@/components/ui/InfoTip.vue'
import SegmentedControl from '@/components/ui/SegmentedControl.vue'
import Skeleton from '@/components/ui/Skeleton.vue'
import { computed, onMounted, ref, watch } from 'vue'
import { get, notifyError } from '@/api/client'

/**
 * 今日一题：按日期从已同步题库确定性选题，同一天全校相同。
 * 电脑上是一张卡片（平台切换、题号题名、难度、标签、自己过没过、校内几人通过）；
 * compact 时是手机上的一行链接卡片（平台图标、「今日一题 · 校内 5 人已通过」、题号题名、难度），点开去做题。
 */
withDefaults(defineProps<{ compact?: boolean }>(), { compact: false })

interface DailyTag {
  tagRaw: string
  category: string
}

interface DailyProblem {
  id: number
  platform: string
  problemKey: string
  title: string | null
  url: string | null
  difficultyLabel: string | null
  difficultyValue: number | null
  difficultySource: string | null
  solvedCount: number | null
  tags: DailyTag[]
}

interface DailyProblemResponse {
  date: string
  platform: string
  problem: DailyProblem | null
  solvedByMe?: boolean
  campusSolvedCount?: number
  difficultyRange?: { min: number; max: number }
  note: string
}

/** 只有这两个平台有公开题库可以确定性选题，其余平台不放进选择器。 */
const PLATFORM_OPTIONS = [
  { value: 'CODEFORCES', label: 'Codeforces' },
  { value: 'ATCODER', label: 'AtCoder' }
]

const MAX_TAGS = 5

const platform = ref('CODEFORCES')
const data = ref<DailyProblemResponse | null>(null)
const loading = ref(false)

const problem = computed(() => data.value?.problem ?? null)
const label = computed(() => (problem.value ? problemCode(problem.value) : null))
const safeUrl = computed(() => (problem.value?.url && /^https?:\/\//i.test(problem.value.url) ? problem.value.url : undefined))

const visibleTags = computed(() => problem.value?.tags.slice(0, MAX_TAGS) ?? [])
const hiddenTagCount = computed(() => Math.max(0, (problem.value?.tags.length ?? 0) - MAX_TAGS))
const campusText = computed(() => (data.value?.campusSolvedCount !== undefined ? `校内 ${data.value.campusSolvedCount} 人已通过` : ''))

/** 通过人数上万时用「万」表示，避免长数字在窄屏把整行挤换行。 */
function formatSolved(value: number): string {
  return value >= 10000 ? `${(value / 10000).toFixed(1)} 万` : String(value)
}

async function load() {
  loading.value = true
  try {
    data.value = await get<DailyProblemResponse>('/activity/daily-problem', { platform: platform.value })
  } catch (e) {
    notifyError(e)
  } finally {
    loading.value = false
  }
}

watch(platform, load)
onMounted(load)
</script>

<template>
  <!-- 手机：一行链接卡片 -->
  <component
    :is="safeUrl ? 'a' : 'div'"
    v-if="compact"
    :href="safeUrl"
    :target="safeUrl ? '_blank' : undefined"
    :rel="safeUrl ? 'noopener noreferrer' : undefined"
    class="compact-card"
    data-test="daily-problem"
  >
    <template v-if="problem && label">
      <PlatformLogo :platform="problem.platform" :size="34" />
      <span class="min-w-0 flex flex-1 flex-col gap-0.5">
        <span class="text-xs text-muted">今日一题{{ campusText ? ` · ${campusText}` : '' }}</span>
        <span class="flex min-w-0 items-center gap-1 text-[15px] text-ink font-semibold">
          <span class="truncate"><span class="mono text-[13.5px] text-brand-600">{{ label.code }}</span> {{ label.name }}</span>
          <span v-if="data?.solvedByMe" class="i-lucide-circle-check shrink-0 text-base text-success-ink" role="img" aria-label="你已通过" title="你已通过" />
        </span>
      </span>
      <DifficultyTag :platform="problem.platform" :value="problem.difficultyValue" :label="problem.difficultyLabel" class="shrink-0" />
      <span v-if="safeUrl" class="i-lucide-chevron-right shrink-0 text-lg text-faint" aria-hidden="true" />
    </template>
    <template v-else-if="loading">
      <Skeleton class="size-[34px] rounded-lg" />
      <span class="flex flex-1 flex-col gap-1.5"><Skeleton class="h-3 w-1/3" /><Skeleton class="h-4 w-2/3" /></span>
    </template>
    <span v-else class="text-[13px] text-muted">今日一题：{{ data?.note ?? '暂时取不到' }}</span>
  </component>

  <!-- 电脑：卡片 -->
  <section v-else class="daily-card" data-test="daily-problem">
    <div class="flex flex-wrap items-center justify-between gap-2">
      <h2 class="m-0 flex items-center gap-1 text-[15px] text-ink font-semibold">
        今日一题
        <InfoTip v-if="data?.note" title="今日一题怎么选">{{ data.note }}</InfoTip>
      </h2>
      <SegmentedControl v-model="platform" :options="PLATFORM_OPTIONS" label="从哪个平台选" class="small" />
    </div>

    <template v-if="problem && label">
      <div class="flex items-start gap-3">
        <PlatformLogo :platform="problem.platform" :size="28" />
        <div class="min-w-0 flex flex-1 flex-col gap-1.5">
          <a v-if="safeUrl" :href="safeUrl" target="_blank" rel="noopener noreferrer" class="text-base text-ink font-semibold leading-snug hover:text-brand-600">
            <span class="mono text-sm text-brand-600">{{ label.code }}</span> {{ label.name }}
          </a>
          <span v-else class="text-base text-ink font-semibold leading-snug"><span class="mono text-sm text-brand-600">{{ label.code }}</span> {{ label.name }}</span>
          <span class="flex flex-wrap items-center gap-1.5">
            <DifficultyTag :platform="problem.platform" :value="problem.difficultyValue" :label="problem.difficultyLabel" />
            <span v-if="problem.solvedCount !== null" class="text-xs text-muted">平台 {{ formatSolved(problem.solvedCount) }} 人通过</span>
          </span>
        </div>
      </div>
      <div v-if="visibleTags.length" class="flex flex-wrap items-center gap-1.5">
        <span v-for="tag in visibleTags" :key="tag.tagRaw" class="tag-chip" :title="`统一分类：${tag.category}`">{{ tag.tagRaw }}</span>
        <span v-if="hiddenTagCount" class="text-xs text-muted">等 {{ hiddenTagCount }} 个标签</span>
      </div>
      <div class="flex flex-wrap items-center gap-2 border-t border-t-solid border-line-soft pt-3 text-[13px]">
        <span v-if="data?.solvedByMe" class="flex items-center gap-1 text-success-ink font-semibold"><span class="i-lucide-circle-check" aria-hidden="true" />你已通过</span>
        <span v-else class="text-body">还没通过，去挑战一下吧。</span>
        <span v-if="campusText" class="text-muted">· {{ campusText }}</span>
        <span v-if="data?.difficultyRange" class="text-muted">· 选题难度 {{ data.difficultyRange.min }}–{{ data.difficultyRange.max }}</span>
      </div>
    </template>
    <div v-else-if="loading" class="flex flex-col gap-2">
      <Skeleton class="h-5 w-3/4" />
      <Skeleton class="h-4 w-1/3" />
    </div>
    <p v-else class="m-0 text-[13px] text-muted">{{ data?.note ?? '暂时取不到今日一题，稍后再试。' }}</p>
  </section>
</template>

<style scoped>
.daily-card {
  display: flex;
  flex-direction: column;
  gap: 12px;
  padding: 18px 20px;
  border: 1px solid var(--border);
  border-radius: var(--radius);
  background: var(--bg-elevated);
}

.compact-card {
  display: flex;
  align-items: center;
  gap: 12px;
  padding: 14px 16px;
  border: 1px solid var(--border);
  border-radius: var(--radius);
  background: var(--bg-elevated);
  color: inherit;
  text-decoration: none;
}

.compact-card:focus-visible {
  outline: 2px solid var(--brand-500);
  outline-offset: 2px;
}

.tag-chip {
  padding: 1px 8px;
  border-radius: 999px;
  background: var(--brand-soft);
  color: var(--brand-600);
  font-size: 12px;
  cursor: default;
}

/* 卡片标题旁的分段按钮小一号 */
.small :deep(.segmented-item) {
  height: 28px;
  padding: 0 10px;
  font-size: 12.5px;
}
</style>
