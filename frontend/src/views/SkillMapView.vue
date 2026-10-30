<script setup lang="ts">
import { computed, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { get, notifyError } from '@/api/client'
import type { PlatformCapability } from '@/api/types'
import SkillCategoryDetail from '@/components/practice/SkillCategoryDetail.vue'
import SkillRadar from '@/components/practice/SkillRadar.vue'
import BottomSheet from '@/components/ui/BottomSheet.vue'
import ChipSelect from '@/components/ui/ChipSelect.vue'
import EmptyState from '@/components/ui/EmptyState.vue'
import InfoTip from '@/components/ui/InfoTip.vue'
import PageHeader from '@/components/ui/PageHeader.vue'
import Skeleton from '@/components/ui/Skeleton.vue'
import StatTile from '@/components/ui/StatTile.vue'
import { useBreakpoint } from '@/composables/useBreakpoint'
import { categoryName } from '@/utils/problemLabel'

interface CategoryRow {
  category: string
  catalogCount: number
  solvedCount: number
  solvedInCatalog: number
  solvedOffCatalog: number
  coverage: number | null
  maxSolvedDifficulty: number | null
  lastSolvedAt: string | null
  recentSolved: number
}

interface SkillMap {
  platform: string
  platformName: string
  categories: CategoryRow[]
  solvedTotal: number
  taggedSolved: number
  untaggedSolved?: number
  note: string
}

const route = useRoute()
const router = useRouter()
const { isMobile } = useBreakpoint()

const capabilities = ref<PlatformCapability[]>([])
const map = ref<SkillMap | null>(null)
const loading = ref(false)
const loadError = ref(false)
/** 打开详情的方向；null 是没打开 */
const openCategory = ref<string | null>(null)

/** 平台写在网址上；不写时让后端挑「带标签的通过题最多」的平台 */
const platform = computed(() => (typeof route.query.platform === 'string' ? route.query.platform : ''))
const currentPlatform = computed(() => map.value?.platform ?? platform.value)

/** 只有有标签来源的平台才画得出地图，其余平台放进来只会得到一张空图 */
const platformOptions = computed(() => {
  const usable = capabilities.value.filter((c) => c.tagSource && c.tagSource !== 'NONE').map((c) => ({ value: c.platform, label: c.displayName }))
  const options = usable.length ? usable : [{ value: 'CODEFORCES', label: 'Codeforces' }]
  const current = map.value?.platform
  if (current && !options.some((o) => o.value === current)) options.unshift({ value: current, label: map.value?.platformName ?? current })
  return options
})
function choosePlatform(value: string) {
  openCategory.value = null
  void router.replace({ query: { ...route.query, platform: value } })
}

const categories = computed(() => map.value?.categories ?? [])
const practiced = computed(() => [...categories.value].filter((c) => c.solvedCount > 0).sort((a, b) => b.solvedCount - a.solvedCount))
const untouched = computed(() => categories.value.filter((c) => c.solvedCount === 0))
const maxSolved = computed(() => Math.max(1, ...practiced.value.map((c) => c.solvedCount)))
const recentSum = computed(() => categories.value.reduce((sum, c) => sum + (c.recentSolved ?? 0), 0))
/** 所选平台的题库同步过没有：没同步时每个方向都没有覆盖率，只在方向区上方说一次 */
const catalogSynced = computed(() => practiced.value.some((row) => row.coverage !== null))

/**
 * 雷达图什么时候值得画：不是「方向够多」，而是「数值不能太悬殊」——至少三个方向达到最大值的 40%。
 * 24 / 10 / 6 / 3 / 1 这种画出来就是一根针，不画；最多画 8 个方向。
 */
const radarRows = computed(() => practiced.value.slice(0, 8).map((c) => ({ name: categoryName(c.category), value: c.solvedCount })))
const radarUsable = computed(() => {
  const values = radarRows.value.map((r) => r.value)
  return values.length >= 3 && values.filter((v) => v >= maxSolved.value * 0.4).length >= 3
})
const topName = computed(() => (practiced.value[0] ? categoryName(practiced.value[0].category) : ''))

let requestId = 0
async function load() {
  const id = ++requestId
  loading.value = true
  try {
    const data = await get<SkillMap>('/skill-map', { platform: platform.value || undefined })
    if (id !== requestId) return
    map.value = data
    loadError.value = false
  } catch (e) {
    if (id !== requestId) return
    if (!map.value) loadError.value = true
    notifyError(e)
  } finally {
    if (id === requestId) loading.value = false
  }
}
watch(platform, () => void load(), { immediate: true })
void get<PlatformCapability[]>('/platforms/capabilities')
  .then((data) => (capabilities.value = data))
  .catch(() => (capabilities.value = []))

function shortDate(value: string | null) {
  if (!value) return null
  const date = new Date(value)
  if (Number.isNaN(date.getTime())) return null
  const [y, m, d] = new Intl.DateTimeFormat('sv-SE', { timeZone: 'Asia/Shanghai', year: 'numeric', month: '2-digit', day: '2-digit' }).format(date).split('-').map(Number)
  return y === new Date().getFullYear() ? `${m}/${d}` : `${y}/${m}/${d}`
}

const TIPS = ['分布只回答「题库里这个方向有多少题、其中我通过了多少」，不是能力评估，也不折算成等级或分数。', '一道题带几个标签就在几个方向里各算一次，所以各方向加起来会多于通过题数。']
</script>

<template>
  <div class="flex flex-col gap-4 md:gap-[18px]" data-test="page">
    <PageHeader title="算法地图" class="!mb-0">
      <template #description>
        把你通过的题按算法方向摊开看：哪些方向练得多，哪些还没碰过。只是分布，不是能力评分。
        <span class="hidden md:inline">
          <InfoTip title="算法地图怎么算" label="算法地图怎么算" class="ml-0.5 align-[-3px]">
            <p v-for="(tip, i) in TIPS" :key="i" class="m-0" :class="{ 'mt-1.5': i }">{{ tip }}</p>
          </InfoTip>
        </span>
      </template>
      <template #actions>
        <div data-test="platform">
          <ChipSelect :model-value="currentPlatform" :options="platformOptions" label="平台" :default-value="currentPlatform" @update:model-value="choosePlatform" />
        </div>
      </template>
      <template #mobile-actions>
        <InfoTip title="算法地图怎么算" label="算法地图怎么算" topbar>
          <p v-for="(tip, i) in TIPS" :key="i" class="m-0" :class="{ 'mt-1.5': i }">{{ tip }}</p>
        </InfoTip>
      </template>
    </PageHeader>

    <div v-if="isMobile" class="flex" data-test="platform-mobile">
      <ChipSelect :model-value="currentPlatform" :options="platformOptions" label="平台" :default-value="currentPlatform" @update:model-value="choosePlatform" />
    </div>

    <EmptyState v-if="loadError" icon="i-lucide-cloud-off" title="算法地图没加载出来" description="检查网络后再试一次。" action="重试" @action="load" />

    <template v-else-if="!map">
      <div class="grid grid-cols-2 gap-3 lg:grid-cols-4">
        <Skeleton v-for="i in 4" :key="i" class="h-[92px] rounded-card" />
      </div>
      <Skeleton class="h-[360px] rounded-card" />
    </template>

    <EmptyState
      v-else-if="!categories.length"
      icon="i-lucide-map"
      :title="`${map.platformName} 画不出算法地图`"
      :description="map.note"
    />

    <template v-else>
      <div class="grid grid-cols-2 gap-3 transition-opacity lg:(grid-cols-4 gap-3.5)" :class="{ 'opacity-60': loading }" data-test="kpis">
        <StatTile label="该平台已通过" :value="map.solvedTotal" unit="题" />
        <StatTile label="能归入算法方向" :value="map.taggedSolved" unit="题" tip="能归入算法方向">
          <template #tip>其余 {{ map.untaggedSolved ?? 0 }} 题在题库里没有标签，不进入分类统计。</template>
        </StatTile>
        <StatTile label="练过的方向" :value="practiced.length" :unit="`/ ${categories.length} 个`" />
        <StatTile label="近 30 天新增" :value="recentSum" unit="题次" tip="近 30 天新增">
          <template #tip>按方向累加：一道题带几个标签就算几次，所以会多于实际通过的题数。</template>
        </StatTile>
      </div>

      <div class="grid grid-cols-1 items-start gap-4 transition-opacity" :class="[{ 'opacity-60': loading }, radarUsable ? 'xl:grid-cols-[420px_minmax(0,1fr)]' : '']">
        <section v-if="radarUsable" class="flex flex-col gap-2.5 border xl:(sticky top-6) border-line rounded-card border-solid bg-surface p-4 md:(px-5 py-[18px])" aria-labelledby="radar-title" data-test="radar">
          <h2 id="radar-title" class="m-0 text-[15px] text-ink font-semibold">练习分布</h2>
          <SkillRadar :rows="radarRows" />
          <p class="m-0 text-[12.5px] text-muted leading-[1.6]">最外圈是你练得最多的方向（{{ topName }} {{ maxSolved }} 题），只表示方向之间的比例。</p>
        </section>

        <section class="flex flex-col gap-3 border border-line rounded-card border-solid bg-surface p-4 md:(px-5 py-[18px])" aria-labelledby="cats-title" data-test="categories">
          <div class="flex items-center justify-between gap-3">
            <h2 id="cats-title" class="m-0 text-[15px] text-ink font-semibold">按算法方向</h2>
            <span class="text-[12.5px] text-muted">点一个方向，看里面的题</span>
          </div>
          <p v-if="!catalogSynced" class="m-0 rounded-control bg-surface-2 px-3 py-2 text-[13px] text-body">这个平台的题库还没同步：下面只统计你做过的题，没有题库覆盖率。</p>
          <div class="grid grid-cols-1 gap-2.5 md:grid-cols-2" :class="radarUsable ? 'xl:grid-cols-3' : 'lg:grid-cols-3 xl:grid-cols-4'">
            <button v-for="row in practiced" :key="row.category" type="button" class="cat-card" :data-category="row.category" @click="openCategory = row.category">
              <span class="flex items-baseline justify-between gap-2">
                <span class="min-w-0 truncate text-[14.5px] text-ink font-semibold">{{ categoryName(row.category) }}</span>
                <span class="num text-xl text-ink font-semibold">{{ row.solvedCount }}</span>
              </span>
              <span class="h-1.5 overflow-hidden rounded-[3px] bg-surface-2" aria-hidden="true">
                <span class="block h-full rounded-[3px] bg-brand" :style="{ width: `${Math.min(100, (row.solvedCount / maxSolved) * 100)}%` }" />
              </span>
              <span class="text-xs text-muted leading-[1.6]">
                <template v-if="row.coverage !== null">题库 {{ row.catalogCount }} 题 · 覆盖 {{ Math.round(row.coverage) }}%</template>
                <template v-else-if="catalogSynced">题库里还没有这个方向的题</template>
                <template v-if="row.solvedOffCatalog > 0"><br />另有 {{ row.solvedOffCatalog }} 题不在题库里</template>
              </span>
              <span class="flex flex-wrap items-center gap-x-2 gap-y-1 text-xs text-muted">
                <span v-if="row.maxSolvedDifficulty != null">最高 {{ row.maxSolvedDifficulty }}</span>
                <span v-if="shortDate(row.lastSolvedAt)">最近 {{ shortDate(row.lastSolvedAt) }}</span>
                <span v-if="row.recentSolved > 0" class="h-5 inline-flex items-center rounded-md bg-success-soft px-1.5 text-[11.5px] text-success-ink font-semibold">30 天 +{{ row.recentSolved }}</span>
              </span>
            </button>
          </div>
          <div v-if="untouched.length" class="flex flex-wrap items-center gap-2 border-t border-line-soft border-t-solid pt-3" data-test="untouched">
            <span class="mr-1 text-[13px] text-body">还没碰过的方向</span>
            <button v-for="row in untouched" :key="row.category" type="button" class="untouched-chip" :data-category="row.category" @click="openCategory = row.category">
              {{ categoryName(row.category) }}<span v-if="row.catalogCount" class="num">&nbsp;{{ row.catalogCount }}</span>
            </button>
          </div>
          <p class="m-0 text-xs text-muted leading-[1.6]">{{ map.note }}</p>
        </section>
      </div>
    </template>

    <BottomSheet v-if="isMobile" :model-value="openCategory !== null" :title="openCategory ? categoryName(openCategory) : ''" full @update:model-value="!$event && (openCategory = null)">
      <SkillCategoryDetail v-if="openCategory" :platform="currentPlatform" :category="openCategory" />
    </BottomSheet>
    <el-drawer v-else :model-value="openCategory !== null" :title="openCategory ? categoryName(openCategory) : ''" size="520px" @update:model-value="!$event && (openCategory = null)">
      <SkillCategoryDetail v-if="openCategory" :platform="currentPlatform" :category="openCategory" />
    </el-drawer>
  </div>
</template>

<style scoped>
.cat-card {
  display: flex;
  flex-direction: column;
  gap: 8px;
  padding: 14px;
  border: 1px solid var(--border);
  border-radius: 12px;
  background: var(--bg-elevated);
  color: inherit;
  font: inherit;
  text-align: left;
  cursor: pointer;
  transition: border-color 0.15s ease, background 0.15s ease;
}

.cat-card:hover {
  border-color: var(--brand-400);
  background: var(--surface-hover);
}

.untouched-chip {
  display: inline-flex;
  align-items: center;
  height: 28px;
  padding: 0 10px;
  border: 1px dashed var(--border-strong);
  border-radius: 999px;
  background: transparent;
  color: var(--text-muted);
  font: inherit;
  font-size: 12.5px;
  cursor: pointer;
}

.untouched-chip:hover {
  border-color: var(--brand-400);
  color: var(--brand-600);
}

.cat-card:focus-visible,
.untouched-chip:focus-visible {
  outline: 2px solid var(--brand-500);
  outline-offset: 2px;
}
</style>
