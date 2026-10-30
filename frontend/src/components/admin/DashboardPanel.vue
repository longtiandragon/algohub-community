<script setup lang="ts">
import { computed, onBeforeUnmount, onMounted, ref, watch } from 'vue'
import { useRouter } from 'vue-router'
import type { EChartsOption } from 'echarts'
import { loadDashboard, type AdminPermission, type Dashboard } from '@/api/admin'
import EChart from '@/components/EChart.vue'
import PlatformLogo from '@/components/PlatformLogo.vue'
import SectionCard from '@/components/ui/SectionCard.vue'
import SegmentedControl from '@/components/ui/SegmentedControl.vue'
import Skeleton from '@/components/ui/Skeleton.vue'
import StatTile from '@/components/ui/StatTile.vue'
import { platformName } from '@/utils/platformBrand'

/**
 * 数据概览：四个大数（总用户、今日活跃、今日访问、今日新增）、四个待办小数（点了跳到对应分组，没权限的不能点）、
 * 访问趋势（7 / 30 / 90 天）、今天常用页面、平台账号分布、各学校成员。
 * 「今天」都按北京时间；活跃和访问不算管理员自己。刷新由管理后台页头的「刷新」触发（refreshKey 变了就重读）。
 */
const props = defineProps<{
  permissions: AdminPermission[]
  refreshKey: number
}>()
const emit = defineEmits<{ open: [target: { tab: string; failedOnly?: boolean }] }>()

const router = useRouter()
const data = ref<Dashboard | null>(null)
const loading = ref(true)
const error = ref('')
const days = ref(30)
const dayOptions = [
  { label: '7 天', value: 7 },
  { label: '30 天', value: 30 },
  { label: '90 天', value: 90 }
]

async function load() {
  loading.value = true
  error.value = ''
  try {
    data.value = await loadDashboard(days.value)
  } catch (e) {
    error.value = e instanceof Error && e.message ? e.message : '数据概览加载失败，请稍后再试'
  } finally {
    loading.value = false
  }
}
onMounted(load)
watch([days, () => props.refreshKey], load)

const has = (permission: AdminPermission) => props.permissions.includes(permission)
const percent = (part: number, whole: number) => (whole ? `${Math.round((part / whole) * 100)}%` : '—')

/** 待办小数：有数字要处理的标琥珀色（同步失败标红）；没有对应权限的只看不能点 */
const todos = computed(() => {
  const d = data.value
  return [
    { key: 'members', label: '待审批入校', value: d?.pending.members, tone: d?.pending.members ? 'warning' : '', hint: '学生与认证', open: has('SCHOOLS') ? { tab: 'members' } : null },
    { key: 'appeals', label: '待处理申诉', value: d?.pending.appeals, tone: d?.pending.appeals ? 'warning' : '', hint: '归属申诉', open: has('SYNC') ? { tab: 'appeals' } : null },
    { key: 'failed', label: '同步失败', value: d?.pending.failedSync, tone: d?.pending.failedSync ? 'danger' : '', hint: '同步状态', open: has('SYNC') ? { tab: 'sync', failedOnly: true } : null },
    {
      key: 'verified',
      label: '有已验证账号的同学',
      value: d?.users.withVerifiedAccount,
      tone: '',
      hint: d ? `占学生 ${percent(d.users.withVerifiedAccount, d.users.students)}` : '',
      open: has('USERS') ? { tab: 'users' } : null
    }
  ]
})

/* ------------------------------ 趋势图 ------------------------------ */
/** 图表颜色从设计系统的 CSS 变量读，切深浅色时重新取值（和个人档案的图一样） */
const themeVersion = ref(0)
let themeObserver: MutationObserver | null = null
onMounted(() => {
  themeObserver = new MutationObserver(() => (themeVersion.value += 1))
  themeObserver.observe(document.documentElement, { attributes: true, attributeFilter: ['data-theme'] })
})
onBeforeUnmount(() => themeObserver?.disconnect())
const cssVar = (name: string) => getComputedStyle(document.documentElement).getPropertyValue(name).trim()
const theme = computed(() => {
  void themeVersion.value
  return {
    text: cssVar('--text'),
    muted: cssVar('--text-muted'),
    border: cssVar('--border'),
    borderSoft: cssVar('--border-soft'),
    elevated: cssVar('--bg-elevated'),
    brand: cssVar('--brand-500'),
    brandLight: cssVar('--brand-400'),
    info: cssVar('--info'),
    success: cssVar('--success')
  }
})

const WEEKDAYS = ['日', '一', '二', '三', '四', '五', '六']
const shortDay = (day: string) => `${Number(day.slice(5, 7))}/${Number(day.slice(8, 10))}`
const longDay = (day: string) => `${Number(day.slice(5, 7))}月${Number(day.slice(8, 10))}日 周${WEEKDAYS[new Date(`${day}T00:00:00Z`).getUTCDay()]}`

const trend = computed(() => data.value?.trend ?? [])
const trendEmpty = computed(() => trend.value.every((p) => !p.visits && !p.newUsers))
const option = computed<EChartsOption>(() => {
  const t = theme.value
  const points = trend.value
  const axisText = { color: t.muted, fontSize: 11 }
  return {
    aria: { enabled: true },
    color: [t.brandLight, t.info, t.brand, t.success],
    grid: { left: 4, right: 4, top: 40, bottom: 0, containLabel: true },
    legend: { top: 0, left: 0, itemWidth: 12, itemHeight: 8, itemGap: 14, textStyle: { color: t.muted, fontSize: 12 } },
    tooltip: {
      trigger: 'axis',
      confine: true,
      axisPointer: { type: 'shadow', shadowStyle: { color: 'rgba(127, 127, 127, 0.08)' } },
      backgroundColor: t.elevated,
      borderColor: t.border,
      textStyle: { color: t.text, fontSize: 12 },
      formatter: (params: unknown) => {
        const list = params as { dataIndex: number }[]
        const p = points[list[0]?.dataIndex ?? 0]
        if (!p) return ''
        return [
          `<b>${longDay(p.day)}</b>`,
          `访问 ${p.visits} 次 · 访客 ${p.visitors} 人`,
          `活跃同学 ${p.activeUsers} 人 · 新注册 ${p.newUsers} 人`
        ].join('<br>')
      }
    },
    xAxis: {
      type: 'category',
      data: points.map((p) => shortDay(p.day)),
      axisTick: { show: false },
      axisLine: { lineStyle: { color: t.border } },
      axisLabel: { ...axisText, hideOverlap: true }
    },
    yAxis: [
      { type: 'value', name: '次', minInterval: 1, nameTextStyle: axisText, axisLabel: axisText, splitLine: { lineStyle: { color: t.borderSoft } } },
      { type: 'value', name: '人', minInterval: 1, nameTextStyle: axisText, axisLabel: axisText, splitLine: { show: false } }
    ],
    series: [
      { name: '访问次数', type: 'bar', yAxisIndex: 0, data: points.map((p) => p.visits), barMaxWidth: 16, itemStyle: { borderRadius: [4, 4, 0, 0], opacity: 0.55 } },
      { name: '访客', type: 'line', yAxisIndex: 1, data: points.map((p) => p.visitors), smooth: true, showSymbol: false, lineStyle: { width: 2 } },
      { name: '活跃同学', type: 'line', yAxisIndex: 1, data: points.map((p) => p.activeUsers), smooth: true, showSymbol: false, lineStyle: { width: 2.5 } },
      { name: '新注册', type: 'line', yAxisIndex: 1, data: points.map((p) => p.newUsers), smooth: true, showSymbol: false, lineStyle: { width: 2, type: 'dashed' } }
    ]
  }
})

/* ------------------------------ 三张小表 ------------------------------ */
/** 路由名换成页面标题（「ranking」→「刷题排行」），认不出的原样显示 */
const titles = computed(
  () => new Map(router.getRoutes().filter((r) => r.name).map((r): [string, string] => [String(r.name), r.meta.title ?? String(r.name)]))
)
const pages = computed(() => {
  const list = data.value?.topPages ?? []
  const max = Math.max(1, ...list.map((p) => p.views))
  return list.map((p) => ({ ...p, title: titles.value.get(p.route) ?? p.route, width: `${Math.max(4, Math.round((p.views / max) * 100))}%` }))
})
const platforms = computed(() => {
  const list = [...(data.value?.platforms ?? [])].sort((a, b) => b.accounts - a.accounts)
  const max = Math.max(1, ...list.map((p) => p.accounts))
  return list.map((p) => ({
    ...p,
    width: `${Math.max(4, Math.round((p.accounts / max) * 100))}%`,
    verifiedWidth: p.accounts ? `${Math.round((p.verified / p.accounts) * 100)}%` : '0%'
  }))
})
const schools = computed(() => [...(data.value?.schools ?? [])].sort((a, b) => b.members - a.members))

const updatedAt = computed(() =>
  data.value ? new Date(data.value.generatedAt).toLocaleTimeString('zh-CN', { timeZone: 'Asia/Shanghai', hour: '2-digit', minute: '2-digit', hour12: false }) : ''
)
</script>

<template>
  <div class="flex flex-col gap-4" data-test="admin-dashboard">
    <div v-if="error && !data" class="rounded-card border border-solid border-line bg-surface px-5 py-8 text-center">
      <p class="m-0 text-sm text-danger-ink">{{ error }}</p>
      <el-button class="mt-3" @click="load">重试</el-button>
    </div>

    <template v-else>
      <!-- 四个大数 -->
      <div class="grid grid-cols-2 gap-3 xl:grid-cols-4 md:gap-4">
        <StatTile label="总用户" :value="data?.users.total" unit="人" :loading="loading && !data" tip="总用户">
          <template #tip>注册过的全部账号，含管理员；已禁用的也算在内。</template>
          <template #foot>学生 {{ data?.users.students }} · 管理员 {{ data?.users.admins }}<template v-if="data?.users.disabled"> · 已禁用 {{ data.users.disabled }}</template></template>
        </StatTile>
        <StatTile label="今日活跃" :value="data?.activity.activeToday" unit="人" :loading="loading && !data" tip="今日活跃">
          <template #tip>今天（北京时间 0 点起）打开过页面的登录同学，不算管理员。</template>
          <template #foot>近 7 天 {{ data?.activity.active7d }} · 近 30 天 {{ data?.activity.active30d }}</template>
        </StatTile>
        <StatTile label="今日访问" :value="data?.activity.visitsToday" unit="次" :loading="loading && !data" tip="今日访问">
          <template #tip>打开一次页面算一次。访客按登录账号去重；没登录的按当天的网络和浏览器区分，不设 Cookie、不长期跟踪。</template>
          <template #foot>访客 {{ data?.activity.visitorsToday }} 人<template v-if="data?.activity.guestVisitorsToday"> · 其中未登录 {{ data.activity.guestVisitorsToday }}</template></template>
        </StatTile>
        <StatTile label="今日新增" :value="data?.users.newToday" unit="人" :loading="loading && !data" :foot="data ? `近 7 天 ${data.users.new7d} 人` : undefined" />
      </div>

      <!-- 待办 -->
      <div class="grid grid-cols-2 gap-3 lg:grid-cols-4" data-test="admin-todos">
        <component
          :is="todo.open ? 'button' : 'div'"
          v-for="todo in todos"
          :key="todo.key"
          :type="todo.open ? 'button' : undefined"
          class="todo"
          :class="[todo.tone, { 'is-link': !!todo.open }]"
          :aria-label="todo.open ? `${todo.label} ${todo.value ?? '—'}，打开${todo.hint}` : undefined"
          @click="todo.open && emit('open', todo.open)"
        >
          <span class="min-w-0 flex flex-col gap-0.5">
            <span class="truncate text-[12.5px] text-muted">{{ todo.label }}</span>
            <Skeleton v-if="todo.value === undefined" class="mt-1 h-6 w-10" />
            <span v-else class="num text-[22px] font-semibold leading-7">{{ todo.value }}</span>
          </span>
          <span v-if="todo.open" class="i-lucide-chevron-right shrink-0 text-base text-faint" aria-hidden="true" />
          <span v-else-if="todo.hint && todo.key === 'verified'" class="shrink-0 text-xs text-muted">{{ todo.hint }}</span>
        </component>
      </div>

      <!-- 访问趋势 -->
      <SectionCard title="访问趋势" :description="`近 ${days} 天，按北京时间每天统计；不算管理员自己`" flush>
        <template #actions>
          <SegmentedControl v-model="days" :options="dayOptions" label="统计天数" class="small" />
        </template>
        <div class="px-2 pb-3 pt-1 md:(px-4 pb-4)">
          <Skeleton v-if="loading && !data" class="h-[230px] md:h-[260px]" />
          <template v-else>
            <EChart :option="option" />
            <p v-if="trendEmpty" class="m-0 mt-2 text-center text-[12.5px] text-muted">这段时间还没有访问记录，同学们打开页面后这里才会有数据。</p>
          </template>
        </div>
      </SectionCard>

      <div class="grid grid-cols-1 gap-4 md:grid-cols-2 xl:grid-cols-3">
        <SectionCard title="今天常用页面" description="打开次数，前 8 个页面" flush>
          <div class="px-4 pb-4 md:(px-5 pb-5)">
            <Skeleton v-if="loading && !data" class="h-[180px]" />
            <p v-else-if="!pages.length" class="m-0 py-6 text-center text-[13px] text-muted">今天还没有访问记录</p>
            <ol v-else class="m-0 flex list-none flex-col gap-2.5 p-0">
              <li v-for="page in pages" :key="page.route" class="flex flex-col gap-1">
                <span class="flex items-baseline justify-between gap-3 text-[13px]">
                  <span class="truncate text-ink">{{ page.title }}</span>
                  <span class="num shrink-0 text-muted">{{ page.views }}</span>
                </span>
                <span class="meter" aria-hidden="true"><span class="meter-fill" :style="{ width: page.width }" /></span>
              </li>
            </ol>
          </div>
        </SectionCard>

        <SectionCard title="平台账号" description="绑定的账号数，深色部分已验证归属" flush>
          <div class="px-4 pb-4 md:(px-5 pb-5)">
            <Skeleton v-if="loading && !data" class="h-[180px]" />
            <p v-else-if="!platforms.length" class="m-0 py-6 text-center text-[13px] text-muted">还没有同学绑定平台账号</p>
            <ul v-else class="m-0 flex list-none flex-col gap-2.5 p-0">
              <li v-for="p in platforms" :key="p.platform" class="flex items-center gap-2.5">
                <PlatformLogo :platform="p.platform" :size="20" :title="platformName(p.platform)" />
                <span class="min-w-0 flex flex-1 flex-col gap-1">
                  <span class="flex items-baseline justify-between gap-3 text-[13px]">
                    <span class="truncate text-ink">{{ platformName(p.platform) }}</span>
                    <span class="num shrink-0 text-muted">{{ p.verified }} / {{ p.accounts }}</span>
                  </span>
                  <span class="meter" :style="{ width: p.width }" aria-hidden="true"><span class="meter-fill" :style="{ width: p.verifiedWidth }" /></span>
                </span>
              </li>
            </ul>
          </div>
        </SectionCard>

        <SectionCard title="各学校成员" description="已通过审核的成员" class="md:col-span-2 xl:col-span-1" flush>
          <template v-if="has('SCHOOLS')" #actions>
            <button type="button" class="link-btn" @click="emit('open', { tab: 'schools' })">学校与邀请码 →</button>
          </template>
          <div class="px-4 pb-4 md:(px-5 pb-5)">
            <Skeleton v-if="loading && !data" class="h-[120px]" />
            <p v-else-if="!schools.length" class="m-0 py-6 text-center text-[13px] text-muted">还没有学校</p>
            <ul v-else class="m-0 flex list-none flex-col p-0">
              <li v-for="school in schools" :key="school.id" class="school-row">
                <span class="i-lucide-school shrink-0 text-base text-faint" aria-hidden="true" />
                <span class="min-w-0 flex-1 truncate text-[13.5px] text-ink">{{ school.name }}</span>
                <span v-if="school.pending" class="pending-pill">{{ school.pending }} 待审批</span>
                <span class="num shrink-0 text-[13.5px] text-body">{{ school.members }} 人</span>
              </li>
            </ul>
          </div>
        </SectionCard>
      </div>

      <p v-if="updatedAt" class="m-0 text-right text-xs text-muted">数据更新于 {{ updatedAt }}</p>
    </template>
  </div>
</template>

<style scoped>
.todo {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 8px;
  min-width: 0;
  padding: 12px 14px;
  border: 1px solid var(--border);
  border-radius: var(--radius);
  background: var(--bg-elevated);
  color: var(--text-strong);
  font: inherit;
  text-align: left;
}

.todo.is-link {
  cursor: pointer;
}

.todo.is-link:hover {
  border-color: var(--border-strong);
}

.todo:focus-visible {
  outline: 2px solid var(--brand-500);
  outline-offset: 2px;
}

.todo.warning .num {
  color: var(--warning-ink);
}

.todo.danger .num {
  color: var(--danger-ink);
}

/* 卡片标题旁的分段按钮小一号（和个人档案的累计通过一样） */
.small :deep(.segmented-item) {
  height: 28px;
  padding: 0 10px;
  font-size: 12.5px;
}

.meter {
  display: block;
  height: 6px;
  overflow: hidden;
  border-radius: 999px;
  background: var(--bg-subtle);
}

.meter-fill {
  display: block;
  height: 100%;
  border-radius: inherit;
  background: var(--brand-500);
}

.school-row {
  display: flex;
  align-items: center;
  gap: 10px;
  min-height: 42px;
}

.school-row + .school-row {
  border-top: 1px solid var(--border-soft);
}

.pending-pill {
  flex-shrink: 0;
  padding: 1px 8px;
  border-radius: 999px;
  background: var(--warning-tint);
  color: var(--warning-ink);
  font-size: 12px;
  line-height: 20px;
}

.link-btn {
  padding: 0;
  border: 0;
  background: none;
  color: var(--brand-600);
  font: inherit;
  cursor: pointer;
}

.link-btn:hover {
  text-decoration: underline;
}

.link-btn:focus-visible {
  outline: 2px solid var(--brand-500);
  outline-offset: 2px;
  border-radius: 4px;
}
</style>
