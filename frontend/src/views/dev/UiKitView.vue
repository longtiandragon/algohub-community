<script setup lang="ts">
import { computed, ref } from 'vue'
import EmptyState from '@/components/ui/EmptyState.vue'
import FilterBar from '@/components/ui/FilterBar.vue'
import InfoTip from '@/components/ui/InfoTip.vue'
import PageHeader from '@/components/ui/PageHeader.vue'
import ResponsiveDialog from '@/components/ui/ResponsiveDialog.vue'
import ResponsiveList from '@/components/ui/ResponsiveList.vue'
import SectionCard from '@/components/ui/SectionCard.vue'
import SegmentedControl from '@/components/ui/SegmentedControl.vue'
import Skeleton from '@/components/ui/Skeleton.vue'
import StatTile from '@/components/ui/StatTile.vue'
import StatusBadge from '@/components/ui/StatusBadge.vue'
import type { FilterField, FilterValues, ListColumn } from '@/components/ui/types'
import { useBreakpoint } from '@/composables/useBreakpoint'
import { useThemeStore } from '@/stores/theme'

// 组件样板页（只在开发环境有）：对照设计画布「设计规范」第 05 节，把共用组件的各种状态摆在一起看。
type Range = 'week' | 'month' | 'total'
const range = ref<Range>('week')
const rangeOptions: { label: string; value: Range }[] = [
  { label: '近 7 天', value: 'week' },
  { label: '近 30 天', value: 'month' },
  { label: '累计', value: 'total' }
]
const scope = ref('grade')
const scopeOptions = [
  { label: '同届', value: 'grade' },
  { label: '同班', value: 'class' },
  { label: '全校', value: 'school' }
]
const week = [2, 0, 3, 1, 3, 2, 1]

const { md, lg, xl } = useBreakpoint()
const breakpointName = computed(() =>
  xl.value ? '桌面 ≥ 1280' : lg.value ? '笔记本 1024–1279' : md.value ? '平板 768–1023' : '手机 < 768'
)

// 下面是样板用的固定示例，只为把组件的各种状态摆出来
const filterFields: FilterField[] = [
  {
    key: 'year',
    label: '年级',
    defaultValue: 2026,
    options: [
      { label: '2026 级', value: 2026 },
      { label: '2025 级', value: 2025 },
      { label: '2024 级', value: 2024 },
      { label: '全部年级', value: null }
    ]
  },
  {
    key: 'className',
    label: '班级',
    display: 'select',
    defaultValue: null,
    options: [
      { label: '全部班级', value: null },
      { label: '计应 1 班', value: '计应 1 班' },
      { label: '计应 2 班', value: '计应 2 班' },
      { label: '软件 1 班', value: '软件 1 班' }
    ]
  },
  {
    key: 'platform',
    label: '平台',
    defaultValue: null,
    options: [
      { label: '全部平台', value: null },
      { label: 'Codeforces', value: 'CODEFORCES' },
      { label: 'AtCoder', value: 'ATCODER' },
      { label: '牛客', value: 'NOWCODER' }
    ]
  },
  {
    key: 'metric',
    label: '比什么',
    display: 'cards',
    defaultValue: 'FIRST_AC',
    chipText: (option) => `指标：${option.label}`,
    options: [
      { label: '首次通过题数', value: 'FIRST_AC', description: '每道题只算第一次通过', badge: '推荐' },
      { label: '每日去重通过', value: 'UNIQUE_AC', description: '同一天同一题只算一次' },
      { label: '通过提交次数', value: 'AC_SUBMISSIONS', description: '每次通过都算' }
    ]
  }
]
const filters = ref<FilterValues>({ year: 2026, className: null, platform: null, metric: 'FIRST_AC' })

interface SyncRow {
  id: number
  platform: string
  handle: string
  state: 'ok' | 'failed' | 'claim'
  time: string
}
const syncColumns: ListColumn[] = [
  { key: 'platform', label: '平台', minWidth: 120 },
  { key: 'handle', label: '账号', minWidth: 140 },
  { key: 'state', label: '状态', minWidth: 120 },
  { key: 'time', label: '最近成功', width: 120, align: 'right' }
]
const syncRows: SyncRow[] = [
  { id: 1, platform: 'Codeforces', handle: 'chen_ym', state: 'ok', time: '12 分钟前' },
  { id: 2, platform: '洛谷', handle: 'chenym2026', state: 'ok', time: '25 分钟前' },
  { id: 3, platform: '力扣', handle: 'chen-ym', state: 'failed', time: '1 天前' },
  { id: 4, platform: 'AtCoder', handle: 'chenym', state: 'claim', time: '1 小时前' }
]
const stateText = { ok: '同步正常', failed: '同步失败', claim: '待认领' } as const
const stateTone = { ok: 'success', failed: 'danger', claim: 'warning' } as const
const listLoading = ref(false)
const dialogOpen = ref(false)
const email = ref('')
const code = ref('')

const themeStore = useThemeStore()
const theme = computed(() => themeStore.theme)
function toggleTheme() {
  themeStore.setMode(themeStore.theme === 'dark' ? 'light' : 'dark')
}
</script>

<template>
  <div class="min-h-screen bg-page">
    <main class="mx-auto max-w-[1200px] px-4 py-6 md:px-6 lg:(px-8 py-8)">
      <PageHeader title="组件样板" description="设计规范第 05 节的共用组件和各种状态；只在开发环境有这一页。">
        <template #meta>
          <StatusBadge variant="label" tone="brand">components/ui</StatusBadge>
          <StatusBadge variant="label" tone="neutral">{{ theme === 'dark' ? '深色' : '浅色' }}</StatusBadge>
          <StatusBadge variant="label" tone="info" data-test="breakpoint">{{ breakpointName }}</StatusBadge>
        </template>
        <template #actions>
          <el-button @click="toggleTheme">{{ theme === 'dark' ? '切到浅色' : '切到深色' }}</el-button>
        </template>
      </PageHeader>

      <div class="grid grid-cols-1 gap-4 md:grid-cols-2 xl:grid-cols-3">
        <SectionCard title="按钮" description="Element Plus 按钮，颜色已指向新的变量">
          <div class="flex flex-wrap gap-2.5">
            <el-button type="primary">主要操作</el-button>
            <el-button>次要操作</el-button>
            <el-button text type="primary">文字按钮</el-button>
            <el-button type="danger" plain>注销账号</el-button>
          </div>
          <div class="mt-3 flex items-center gap-2.5">
            <el-button disabled><span class="num">58</span>&nbsp;秒后重发</el-button>
            <span class="text-xs text-muted">不可用时写清原因或剩余时间</span>
          </div>
        </SectionCard>

        <SectionCard title="输入框">
          <div class="flex flex-col gap-3">
            <label class="flex flex-col gap-1.5 text-[13px] text-ink">
              邮箱
              <el-input v-model="email" placeholder="你的邮箱" />
            </label>
            <label class="flex flex-col gap-1.5 text-[13px] text-ink">
              验证码
              <span class="flex gap-2">
                <el-input v-model="code" placeholder="6 位验证码" />
                <el-button>获取验证码</el-button>
              </span>
            </label>
            <span class="text-xs text-muted">手机上输入框用 16px 字，聚焦时不会放大页面</span>
          </div>
        </SectionCard>

        <SectionCard title="分段切换">
          <div class="flex flex-col gap-3">
            <SegmentedControl v-model="range" :options="rangeOptions" label="统计区间" />
            <SegmentedControl v-model="scope" :options="scopeOptions" label="比较范围" block />
            <span class="text-xs text-muted">方向键切换；block 占满一行，手机上常用</span>
          </div>
        </SectionCard>

        <SectionCard title="状态">
          <div class="flex flex-col gap-3">
            <div class="flex flex-wrap gap-x-3.5 gap-y-2">
              <StatusBadge tone="success">同步正常</StatusBadge>
              <StatusBadge tone="brand">同步中</StatusBadge>
              <StatusBadge tone="warning">需要处理</StatusBadge>
              <StatusBadge tone="danger">同步失败</StatusBadge>
              <StatusBadge tone="neutral">还没同步</StatusBadge>
            </div>
            <div class="flex flex-wrap gap-2">
              <StatusBadge variant="label" tone="success">正式绑定</StatusBadge>
              <StatusBadge variant="label" tone="warning">待认领</StatusBadge>
              <StatusBadge variant="label" tone="neutral">个人关注</StatusBadge>
              <StatusBadge variant="label" tone="info">适合新手</StatusBadge>
              <StatusBadge variant="label" tone="danger">登录态失效</StatusBadge>
            </div>
          </div>
        </SectionCard>

        <StatTile label="本周新增通过" :value="12" unit="题" tip="新增通过怎么算？" foot="近 7 天 · 9/21–9/27">
          <template #tip>
            只算首次通过时间能确认的题；同一平台同一题多次通过算一题；只有总数的平台不计入。
          </template>
          <template #chart>
            <div class="flex h-full items-end gap-1.5">
              <span
                v-for="(count, day) in week"
                :key="day"
                class="flex-1 rounded-[3px]"
                :class="day === week.length - 1 ? 'bg-brand-400' : count ? 'bg-brand' : 'bg-line'"
                :style="{ height: `${Math.max(count, 0.3) * 10}px` }"
              />
            </div>
          </template>
        </StatTile>

        <div class="grid grid-cols-2 gap-4">
          <StatTile label="同届排名" loading>
            <template #chart><span /></template>
          </StatTile>
          <StatTile label="连续天数" error="连续天数暂时取不到" />
        </div>

        <SectionCard title="口径说明">
          <p class="m-0 text-[13.5px] leading-[22px] text-body">
            页面上最多保留一行说明，其余点
            <InfoTip title="新增通过怎么算？">
              只算首次通过时间能确认的题；同一平台同一题多次通过算一题；只有总数的平台不计入。
            </InfoTip>
            查看。
          </p>
          <p class="mb-0 mt-2 text-xs text-muted">电脑上悬停或点击弹出，手机上点击后从底部弹出</p>
        </SectionCard>

        <SectionCard title="空状态">
          <EmptyState
            icon="i-lucide-link"
            title="还没有绑定刷题账号"
            description="绑定后会自动同步你的通过记录"
            action="添加账号"
            to="/accounts"
          />
        </SectionCard>

        <SectionCard title="加载占位" description="和真实内容同形状的灰块，每块单独加载">
          <div class="flex flex-col gap-3 rounded-card border border-solid border-line p-4" aria-busy="true">
            <Skeleton class="h-3 w-2/5" />
            <Skeleton class="h-7 w-[30%] rounded-lg" />
            <div class="flex h-[30px] items-end gap-1.5">
              <Skeleton class="h-[14px] flex-1 rounded-[3px]" />
              <Skeleton class="h-6 flex-1 rounded-[3px]" />
              <Skeleton class="h-[18px] flex-1 rounded-[3px]" />
              <Skeleton class="h-[30px] flex-1 rounded-[3px]" />
              <Skeleton class="h-2.5 flex-1 rounded-[3px]" />
            </div>
          </div>
        </SectionCard>

        <SectionCard title="筛选条" description="电脑上一行筛选片；手机上点「筛选」从底部弹出" class="md:col-span-2 xl:col-span-3">
          <FilterBar v-model="filters" :fields="filterFields" sheet-title="筛选排行" apply-text="查看结果 · 60 人" />
          <p class="mb-0 mt-3 break-all text-xs text-muted">当前：{{ JSON.stringify(filters) }}</p>
        </SectionCard>

        <SectionCard
          title="响应式列表"
          description="电脑上是表格，手机上是卡片；单元格插槽两边共用"
          class="md:col-span-2 xl:col-span-3"
        >
          <template #actions>
            <el-button size="small" @click="listLoading = !listLoading">{{ listLoading ? '显示数据' : '看加载中' }}</el-button>
          </template>
          <ResponsiveList :items="syncRows" :columns="syncColumns" row-key="id" :loading="listLoading">
            <template #cell-handle="{ row }"><span class="mono">{{ row.handle }}</span></template>
            <template #cell-state="{ row }">
              <StatusBadge :tone="stateTone[row.state]">{{ stateText[row.state] }}</StatusBadge>
            </template>
          </ResponsiveList>
        </SectionCard>

        <SectionCard title="对话框" description="电脑上居中对话框，手机上底部弹层">
          <el-button @click="dialogOpen = true">打开对话框</el-button>
          <ResponsiveDialog v-model="dialogOpen" title="解绑 AtCoder 账号？">
            <p class="m-0 text-[14px] leading-[22px] text-body">
              解绑后这个账号的记录和登录态会一并删除，以后可以重新绑定。
            </p>
            <template #footer>
              <el-button @click="dialogOpen = false">取消</el-button>
              <el-button type="danger" @click="dialogOpen = false">解绑</el-button>
            </template>
          </ResponsiveDialog>
        </SectionCard>

        <SectionCard title="卡片" description="标题 + 右侧链接或操作；边框分层，不加阴影">
          <template #actions>
            <RouterLink to="/ranking">完整排行 →</RouterLink>
          </template>
          <p class="m-0 text-[13.5px] leading-[22px] text-body">卡片内边距电脑 20、手机 16，卡片之间 16。</p>
        </SectionCard>
      </div>
    </main>
  </div>
</template>
