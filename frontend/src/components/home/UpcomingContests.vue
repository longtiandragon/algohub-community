<script setup lang="ts">
import { computed, onBeforeUnmount, onMounted, ref } from 'vue'
import { get } from '@/api/client'
import type { Contest } from '@/api/types'
import PlatformLogo from '@/components/PlatformLogo.vue'
import SectionCard from '@/components/ui/SectionCard.vue'
import Skeleton from '@/components/ui/Skeleton.vue'
import StatusBadge from '@/components/ui/StatusBadge.vue'
import { contestDay, contestLength, contestTime, startsIn } from '@/utils/contest'
import { beginnerFriendly } from '@/utils/contestLevel'
import { platformName } from '@/utils/platformBrand'
import { httpUrl } from '@/utils/url'

/** 近期比赛（还没结束的前几场）：时间、平台、时长、适不适合新手、还有多久开始。单独加载、单独报错。 */
const props = withDefaults(defineProps<{ limit?: number }>(), { limit: 3 })

const contests = ref<Contest[]>([])
const loading = ref(true)
const error = ref('')
const now = ref(Date.now())
let clock: ReturnType<typeof setInterval> | undefined

async function load() {
  loading.value = true
  error.value = ''
  try {
    contests.value = (await get<{ contests: Contest[] }>('/contests')).contests
  } catch (e) {
    error.value = e instanceof Error ? e.message : '比赛读取失败'
  } finally {
    loading.value = false
  }
}
onMounted(() => {
  void load()
  clock = setInterval(() => (now.value = Date.now()), 60_000)
})
onBeforeUnmount(() => clearInterval(clock))

const upcoming = computed(() =>
  contests.value
    .filter((contest) => Date.parse(contest.endAt ?? contest.startAt) > now.value || Date.parse(contest.startAt) > now.value)
    .sort((a, b) => Date.parse(a.startAt) - Date.parse(b.startAt))
    .slice(0, props.limit)
)
</script>

<template>
  <SectionCard title="近期比赛">
    <template #actions><RouterLink to="/contests">比赛日历 →</RouterLink></template>
    <div v-if="loading" class="flex flex-col gap-4" aria-busy="true">
      <div v-for="n in limit" :key="n" class="flex items-center gap-3">
        <Skeleton class="h-9 w-11" />
        <div class="flex flex-1 flex-col gap-2"><Skeleton class="h-3.5 w-4/5" /><Skeleton class="h-3 w-1/2" /></div>
      </div>
    </div>
    <div v-else-if="error" class="flex flex-col items-start gap-2">
      <p class="m-0 text-[13px] text-danger-ink">{{ error }}</p>
      <el-button size="small" @click="load">重试</el-button>
    </div>
    <p v-else-if="!upcoming.length" class="m-0 text-[13px] text-muted">近期没有比赛。</p>
    <ul v-else class="m-0 list-none p-0">
      <li v-for="contest in upcoming" :key="contest.id" class="contest-row">
        <span class="w-11 flex shrink-0 flex-col items-center md:w-[52px]">
          <span class="text-[11.5px] text-muted md:text-xs">{{ contestDay(contest.startAt, now) }}</span>
          <span class="num text-base text-ink font-semibold md:text-[17px]">{{ contestTime(contest.startAt) }}</span>
        </span>
        <span class="min-w-0 flex flex-1 flex-col gap-1">
          <a v-if="httpUrl(contest.url)" :href="httpUrl(contest.url)" target="_blank" rel="noopener noreferrer" class="contest-title">{{ contest.title }}</a>
          <span v-else class="contest-title">{{ contest.title }}</span>
          <span class="flex flex-wrap items-center gap-x-2 gap-y-1">
            <PlatformLogo :platform="contest.platform" :size="16" />
            <span class="hidden text-xs text-muted md:inline">{{ platformName(contest.platform) }}<template v-if="contest.durationSec"> · {{ contestLength(contest.durationSec) }}</template></span>
            <StatusBadge v-if="beginnerFriendly(contest)" variant="label" tone="info">适合新手</StatusBadge>
            <span class="text-xs text-brand-600 md:hidden">{{ startsIn(contest.startAt, contest.endAt, now) }}</span>
          </span>
        </span>
        <span class="hidden shrink-0 text-[12.5px] text-brand-600 font-semibold md:inline">{{ startsIn(contest.startAt, contest.endAt, now) }}</span>
      </li>
    </ul>
  </SectionCard>
</template>

<style scoped>
.contest-row {
  display: flex;
  align-items: center;
  gap: 12px;
  padding: 12px 0;
  border-top: 1px solid var(--border-soft);
}

.contest-row:first-child {
  padding-top: 0;
  border-top: 0;
}

.contest-title {
  overflow: hidden;
  color: var(--text-strong);
  font-size: 14px;
  white-space: nowrap;
  text-overflow: ellipsis;
}

a.contest-title:hover {
  color: var(--brand-600);
}

@media (min-width: 768px) {
  .contest-row {
    gap: 14px;
  }
}
</style>
