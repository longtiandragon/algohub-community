<script setup lang="ts">
import { computed, onMounted, ref, useId } from 'vue'
import { ElMessage } from 'element-plus'
import { get, notifyError, post } from '@/api/client'
import { type StudyGoal, type StudyGoals } from '@/api/reminders'
import EmptyState from '@/components/ui/EmptyState.vue'
import InfoTip from '@/components/ui/InfoTip.vue'
import PageHeader from '@/components/ui/PageHeader.vue'
import SectionCard from '@/components/ui/SectionCard.vue'
import Skeleton from '@/components/ui/Skeleton.vue'
import StatTile from '@/components/ui/StatTile.vue'
import StatusBadge from '@/components/ui/StatusBadge.vue'
import { useBreakpoint } from '@/composables/useBreakpoint'
import { campusDate, shiftDate } from '@/utils/statistics'

/** 学习目标、进度与提醒设置；站内信统一在消息页。 */
type Period = 'weekly' | 'monthly'
interface ReminderStatus {
  enabled: boolean
  mailConfigured: boolean
}

const SCHEDULE = [
  { icon: 'i-lucide-file-bar-chart', title: '每周学习报告', when: '每周一 09:00', detail: '上周新通过几题，本周、本月进度' },
  { icon: 'i-lucide-calendar-check', title: '周四学习提醒', when: '每周四 09:00', detail: '本周进度，离目标还差几题' },
  { icon: 'i-lucide-calendar-range', title: '月中学习提醒', when: '每月 15 日 09:00', detail: '这个月过半，看看进度' }
]

const { isMobile, xl } = useBreakpoint()
const goals = ref<StudyGoals | null>(null)
const status = ref<ReminderStatus | null>(null)
/** 表单里的计划，空着就是不设；保存前和 goals 里的比，判断改没改 */
const weekly = ref<number | undefined>()
const monthly = ref<number | undefined>()
const emailEnabled = ref(true)
const loading = ref(false)
const saving = ref(false)
const error = ref('')
const weeklyId = useId()
const monthlyId = useId()

type Block = 'progress' | 'plan' | 'schedule'
const columns = computed<Block[][]>(() => xl.value ? [['progress', 'plan'], ['schedule']] : [['progress', 'plan', 'schedule']])

function apply(data: StudyGoals) {
  goals.value = data
  weekly.value = data.weekly.personal ?? undefined
  monthly.value = data.monthly.personal ?? undefined
  emailEnabled.value = data.emailEnabled
}

async function load() {
  loading.value = true
  error.value = ''
  try {
    const [data, state] = await Promise.all([get<StudyGoals>('/reminders/goals'), get<ReminderStatus>('/reminders/status')])
    apply(data)
    status.value = state
  } catch (e) {
    error.value = '暂时读不到目标与提醒设置'
    notifyError(e)
  } finally {
    loading.value = false
  }
}
onMounted(load)

/* ------------------------------ 进度 ------------------------------ */
const today = campusDate()
/** 2026-09-28 → 9/28 */
const monthDay = (date: string) => date.split('-').slice(1).map(Number).join('/')
const daysBetween = (from: string, to: string) => Math.round((Date.parse(`${to}T00:00:00Z`) - Date.parse(`${from}T00:00:00Z`)) / 86400000)
/** 这个月的最后一天：2026-09-01 → 2026-09-30 */
function monthEnd(start: string) {
  const [year, month] = start.split('-').map(Number)
  return new Date(Date.UTC(year, month, 0)).toISOString().slice(0, 10)
}

/** 学校要求：管理员给本人定的，或入学年级的每月默认；没有就是空串 */
function requirement(goal: StudyGoal) {
  if (goal.source === 'ADMIN') return `管理员要求 ${goal.required} 题`
  if (goal.source === 'GRADE') return `${goals.value?.enrollmentYear ?? ''} 级默认 ${goal.required} 题`
  return ''
}

const tiles = computed(() => {
  const data = goals.value
  if (!data) return []
  return (['weekly', 'monthly'] as const).map((key) => {
    const goal = data[key]
    const start = key === 'weekly' ? data.weekStart : data.monthStart
    const end = key === 'weekly' ? shiftDate(start, 6) : monthEnd(start)
    const reached = goal.target > 0 && goal.solved >= goal.target
    let state = '还没有目标'
    if (reached) state = goal.solved > goal.target ? `已达成 · 多做了 ${goal.solved - goal.target} 题` : '已达成'
    else if (goal.target) state = `还差 ${goal.target - goal.solved} 题`
    return {
      key,
      label: key === 'weekly' ? `本周进度 · ${monthDay(start)} – ${monthDay(end)}` : `本月进度 · ${Number(start.split('-')[1])} 月`,
      goal,
      reached,
      state,
      percent: goal.target ? Math.min(100, Math.round((goal.solved / goal.target) * 100)) : 0,
      daysLeft: Math.max(0, daysBetween(today, end) + 1),
      basis: [requirement(goal), goal.personal != null ? `我的计划 ${goal.personal} 题` : '我的计划未设'].filter(Boolean).join(' · ')
    }
  })
})

/** 进度卡上的「设个目标」：把对应的输入框滚到眼前并聚焦 */
function startPlan(key: Period) {
  const input = document.getElementById(key === 'weekly' ? weeklyId : monthlyId)
  input?.scrollIntoView({ block: 'center', behavior: 'smooth' })
  input?.focus({ preventScroll: true })
}

/* ------------------------------ 计划 ------------------------------ */
const dirty = computed(() => {
  const data = goals.value
  if (!data) return false
  return (weekly.value ?? null) !== data.weekly.personal || (monthly.value ?? null) !== data.monthly.personal || emailEnabled.value !== data.emailEnabled
})

async function save() {
  if (saving.value) return
  saving.value = true
  try {
    apply(await post<StudyGoals>('/reminders/goals', { weekly: weekly.value ?? null, monthly: monthly.value ?? null, emailEnabled: emailEnabled.value }))
    ElMessage.success('计划已保存')
  } catch (e) {
    notifyError(e)
  } finally {
    saving.value = false
  }
}

/** 提醒发送没开、或邮件服务没配好时，标题下给一句（正式环境两样都开着，不显示） */
const notice = computed(() => {
  if (!status.value) return ''
  if (!status.value.enabled) return '自动提醒未开启，目标和站内消息照常可用'
  if (!status.value.mailConfigured) return '邮件服务还没配好，提醒先放在站内消息里'
  return ''
})

</script>

<template>
  <div class="page" data-test="page">
    <PageHeader title="目标与提醒">
      <template v-if="!isMobile" #description>给自己定每周、每月的题数，平台按时提醒进度，每周一发上周周报。</template>
      <template v-if="notice" #meta>
        <StatusBadge data-test="reminders-status">{{ notice }}</StatusBadge>
      </template>
      <template #actions>
        <RouterLink to="/messages" class="text-sm text-brand-600">查看消息 →</RouterLink>
        <el-button text :loading="loading" @click="load">
          <span v-if="!loading" class="i-lucide-refresh-cw mr-1.5" aria-hidden="true" />刷新
        </el-button>
      </template>
      <template #mobile-actions>
        <button type="button" class="topbar-icon" :disabled="loading" :aria-label="loading ? '正在刷新' : '刷新'" @click="load">
          <span class="i-lucide-refresh-cw text-xl" :class="{ 'animate-spin motion-reduce:animate-none': loading }" aria-hidden="true" />
        </button>
      </template>
    </PageHeader>

    <SectionCard v-if="error && !goals">
      <EmptyState icon="i-lucide-cloud-off" :title="error" description="检查一下网络，再试一次。" action="重试" :bordered="false" @action="load" />
    </SectionCard>

    <!-- 宽屏左侧进度与计划，右侧提醒时间；窄屏依次排列。 -->
    <div v-else class="grid grid-cols-1 items-start gap-4 xl:grid-cols-[minmax(0,1fr)_360px]">
      <div v-for="(column, index) in columns" :key="index" class="min-w-0 flex flex-col gap-4">
        <template v-for="block in column" :key="block">
          <!-- 本周 / 本月进度 -->
          <div v-if="block === 'progress'" class="grid grid-cols-1 gap-3 md:(grid-cols-2 gap-4)">
            <template v-if="goals">
              <StatTile
                v-for="tile in tiles"
                :key="tile.key"
                :label="tile.label"
                :value="tile.goal.solved"
                :unit="tile.goal.target ? `/ ${tile.goal.target} 题` : '题'"
                :data-test="`progress-${tile.key}`"
              >
                <template #chart>
                  <span class="flex h-full flex-col justify-end gap-1.5">
                    <span class="h-1.5 overflow-hidden rounded-full bg-line-soft" aria-hidden="true">
                      <span class="block h-full rounded-full" :class="tile.reached ? 'bg-success' : 'bg-brand'" :style="{ width: `${tile.percent}%` }" />
                    </span>
                    <span class="flex justify-between gap-2 text-xs leading-none">
                      <span :class="tile.reached ? 'text-success-ink font-semibold' : tile.goal.target ? 'text-body' : 'text-muted'">{{ tile.state }}</span>
                      <span class="num shrink-0 text-muted">还剩 {{ tile.daysLeft }} 天</span>
                    </span>
                  </span>
                </template>
                <template #foot>
                  <button v-if="!tile.goal.target" type="button" class="link-button" @click="startPlan(tile.key)">
                    设个{{ tile.key === 'weekly' ? '每周' : '每月' }}目标 →
                  </button>
                  <span v-else>{{ tile.basis }}</span>
                </template>
              </StatTile>
            </template>
            <template v-else>
              <StatTile label="本周进度" loading />
              <StatTile label="本月进度" loading />
            </template>
          </div>

          <!-- 我的计划：每一行左边是说明，右边是输入框或开关 -->
          <SectionCard v-else-if="block === 'plan'" title="我的计划" description="每周、每月自动延续。提醒按它和学校要求中较大的那个算，留空就是不设。" data-test="reminders-plan">
            <div v-if="!goals" class="flex flex-col gap-3">
              <Skeleton v-for="i in 3" :key="i" class="h-10 w-full" />
            </div>
            <form v-else @submit.prevent="save">
              <ul class="m-0 list-none p-0">
                <li class="plan-row">
                  <span class="min-w-0 flex flex-1 flex-col">
                    <label :for="weeklyId" class="text-sm text-ink">每周计划</label>
                    <span class="text-[12.5px] text-muted leading-5">{{ requirement(goals.weekly) || '学校没有每周要求' }}</span>
                  </span>
                  <el-input-number
                    :id="weeklyId"
                    v-model="weekly"
                    :min="1"
                    :max="10000"
                    :precision="0"
                    :controls="false"
                    align="right"
                    placeholder="不设"
                    aria-label="每周计划题数"
                    class="plan-input"
                  >
                    <template v-if="weekly != null" #suffix><span class="plan-unit">题</span></template>
                  </el-input-number>
                </li>
                <li class="plan-row">
                  <span class="min-w-0 flex flex-1 flex-col">
                    <label :for="monthlyId" class="text-sm text-ink">每月计划</label>
                    <span class="text-[12.5px] text-muted leading-5">{{ requirement(goals.monthly) || '学校没有每月要求' }}</span>
                  </span>
                  <el-input-number
                    :id="monthlyId"
                    v-model="monthly"
                    :min="1"
                    :max="10000"
                    :precision="0"
                    :controls="false"
                    align="right"
                    placeholder="不设"
                    aria-label="每月计划题数"
                    class="plan-input"
                  >
                    <template v-if="monthly != null" #suffix><span class="plan-unit">题</span></template>
                  </el-input-number>
                </li>
                <li class="plan-row">
                  <span class="min-w-0 flex flex-1 flex-col">
                    <span class="text-sm text-ink">邮件提醒</span>
                    <span v-if="goals.email" class="break-all text-[12.5px] text-muted leading-5">同时发到 {{ goals.email }}；关掉后只在站内消息里看</span>
                    <span v-else class="text-[12.5px] text-muted leading-5">
                      还没有验证过的登录邮箱，提醒只发站内消息。<RouterLink to="/settings?section=login-email">去绑定</RouterLink>
                    </span>
                  </span>
                  <el-switch v-model="emailEnabled" aria-label="同时接收邮件提醒" />
                </li>
              </ul>
              <div class="mt-1 flex items-center justify-end gap-3 border-t border-t-solid border-line-soft pt-3.5">
                <span v-if="dirty" class="text-[12.5px] text-muted">有修改还没保存</span>
                <el-button type="primary" native-type="submit" :loading="saving" :disabled="!dirty">保存计划</el-button>
              </div>
            </form>
          </SectionCard>

          <!-- 什么时候提醒 -->
          <SectionCard v-else title="提醒时间">
            <template #actions>
              <InfoTip title="进度怎么算" label="进度怎么算" text="怎么算">
                <p class="m-0">只算已同步、有准确首次通过时间的题；同一平台的同一道题只算一次，没有准确时间的记录不计入。</p>
                <p class="m-0 mt-1.5">周一开始新的一周，每月 1 日开始新的月份，都按北京时间。</p>
                <p class="m-0 mt-1.5">「校内动态」里的本周专项计划单独计算，不影响这里的提醒。</p>
              </InfoTip>
            </template>
            <ul class="m-0 flex list-none flex-col gap-3.5 p-0">
              <li v-for="item in SCHEDULE" :key="item.title" class="flex items-start gap-3">
                <span class="size-9 flex shrink-0 items-center justify-center rounded-[10px] bg-brand-soft text-brand" aria-hidden="true">
                  <span :class="item.icon" class="text-lg" />
                </span>
                <span class="min-w-0 flex flex-col">
                  <span class="text-sm text-ink font-semibold leading-[22px]">{{ item.title }}</span>
                  <span class="text-[12.5px] text-muted leading-5"><span class="num">{{ item.when }}</span> · {{ item.detail }}</span>
                </span>
              </li>
            </ul>
            <p class="mb-0 mt-4 text-xs text-muted leading-[18px]">时间都按北京时间。达到目标后，提醒里会写明已完成。</p>
          </SectionCard>
        </template>
      </div>
    </div>
  </div>
</template>

<style scoped>
.plan-row {
  display: flex;
  align-items: center;
  gap: 16px;
  padding: 12px 0;
}

.plan-row + .plan-row {
  border-top: 1px solid var(--border-soft);
}

.plan-row:first-child {
  padding-top: 0;
}

.plan-input {
  flex-shrink: 0;
  width: 112px;
}

/* 输入框里数字后面的「题」：和数字隔开一点，颜色淡一档 */
.plan-input .plan-unit {
  margin-left: 4px;
  color: var(--text-muted);
}

/* 文字样子的按钮（「设个目标」「标为已读」）：品牌色，不加底色 */
.link-button {
  padding: 0;
  border: 0;
  background: transparent;
  color: var(--brand-600);
  font: inherit;
  cursor: pointer;
}

.link-button:hover {
  text-decoration: underline;
}

.link-button:focus-visible {
  outline: 2px solid var(--brand-500);
  outline-offset: 2px;
  border-radius: 4px;
}

</style>
