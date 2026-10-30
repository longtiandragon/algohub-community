<script setup lang="ts">
import { computed, onMounted, ref, watch } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { del, get, notifyError } from '@/api/client'
import { hasPermission, loadAdminMe } from '@/api/adminUsers'
import { useAuthStore } from '@/stores/auth'
import { useBreakpoint } from '@/composables/useBreakpoint'
import ActivityDetail from '@/components/practice/ActivityDetail.vue'
import ActivityEditDialog from '@/components/practice/ActivityEditDialog.vue'
import { activityRange, untilText, type Activity } from '@/components/practice/types'
import BottomSheet from '@/components/ui/BottomSheet.vue'
import EmptyState from '@/components/ui/EmptyState.vue'
import InfoTip from '@/components/ui/InfoTip.vue'
import PageHeader from '@/components/ui/PageHeader.vue'
import Skeleton from '@/components/ui/Skeleton.vue'

const auth = useAuthStore()
const canManage = ref(false)
watch(() => auth.me?.id, async id => {
  canManage.value = false
  if (!auth.isAdmin) return
  try {
    const me = await loadAdminMe(true)
    if (auth.me?.id === id) canManage.value = me.full || hasPermission('PROBLEMS', me.permissions)
  } catch { /* The backend remains authoritative; unavailable permission data hides edit controls. */ }
}, { immediate: true })
const { isMobile } = useBreakpoint()

const items = ref<Activity[]>([])
const note = ref('')
const loaded = ref(false)
const loadError = ref(false)
/** 进行中的活动另取详情，卡片右边要放活动榜前 5 名 */
const runningDetail = ref<Record<number, Activity>>({})

const running = computed(() => items.value.filter((a) => a.state === 'RUNNING'))
const upcoming = computed(() => items.value.filter((a) => a.state === 'UPCOMING'))
const ended = computed(() => items.value.filter((a) => a.state === 'ENDED'))

async function load() {
  try {
    const data = await get<{ items: Activity[]; note: string }>('/activities')
    items.value = data.items ?? []
    note.value = data.note ?? ''
    loaded.value = true
    loadError.value = false
    const details = await Promise.all(running.value.map((a) => get<Activity>(`/activities/${a.id}`).catch(() => null)))
    runningDetail.value = Object.fromEntries(details.filter((d): d is Activity => !!d).map((d) => [d.id, d]))
  } catch (e) {
    if (!loaded.value) loadError.value = true
    notifyError(e)
  }
}
onMounted(load)

// ---------- 详情（电脑抽屉 / 手机整屏弹层） ----------
const detail = ref<Activity | null>(null)
const detailOpen = ref(false)
async function openDetail(activity: Activity) {
  detailOpen.value = true
  detail.value = runningDetail.value[activity.id] ?? null
  try {
    detail.value = await get<Activity>(`/activities/${activity.id}`)
  } catch (e) {
    notifyError(e)
  }
}

// ---------- 管理员 ----------
const editOpen = ref(false)
const editing = ref<Activity | null>(null)
function openEdit(activity: Activity | null) {
  if (!canManage.value) return
  editing.value = activity
  editOpen.value = true
}
async function remove(activity: Activity) {
  if (!canManage.value) return
  try {
    await ElMessageBox.confirm(`删除「${activity.title}」？题单和同学的通过记录都不受影响。`, '删除活动', {
      type: 'warning',
      confirmButtonText: '删除',
      cancelButtonText: '取消',
      confirmButtonClass: 'el-button--danger'
    })
  } catch {
    return
  }
  try {
    await del(`/admin/activities/${activity.id}`)
    ElMessage.success('活动已删除')
    await load()
  } catch (e) {
    notifyError(e)
  }
}
function onMore(activity: Activity, command: string) {
  if (command === 'edit') openEdit(activity)
  else void remove(activity)
}

// ---------- 显示 ----------
const done = (a: Activity) => a.progress?.duringCount ?? 0
const total = (a: Activity) => a.progress?.problemCount ?? a.problemCount ?? 0
const pct = (a: Activity) => (total(a) ? Math.min(100, Math.round((done(a) / total(a)) * 100)) : 0)
function myRank(a: Activity) {
  const board = runningDetail.value[a.id]?.leaderboard ?? []
  const index = board.findIndex((r) => r.userId === auth.me?.id)
  return index >= 0 ? index + 1 : null
}
/** 「今晚 23:59 结束」「明天 12:00 结束」「还剩 14 天」 */
function endLabel(a: Activity) {
  const t = untilText(a.endAt)
  return t.endsWith('天后') ? `还剩 ${t.replace(' 天后', '')} 天` : `${t} 结束`
}
/** 「3 天后开始」「明天 19:00 开始」 */
function startLabel(a: Activity) {
  const t = untilText(a.startAt)
  return t.endsWith('后') ? `${t}开始` : `${t} 开始`
}
const listLine = (a: Activity) => (a.listTitle ? `题单：${a.listTitle}（${a.problemCount ?? 0} 题）` : '还没关联题单')
const scopeText = (a: Activity) => (a.scope === 'PUBLIC' ? '全站' : auth.isAdmin ? (a.schoolName ?? '本校') : null)

const TIPS = [
  '进度按「题单里的题、通过时间落在活动起止之间」实时统计，不用报名。',
  '活动开始前就通过的题单独计，不算这次训练的成果；通过时间未知的题两边都不算。'
]
</script>

<template>
  <div class="flex flex-col gap-5" data-test="page">
    <PageHeader title="训练活动" class="!mb-0">
      <template #description>
        校内发布的限时训练；只算活动期间通过的题，不用报名
        <span class="hidden md:inline">
          <InfoTip title="进度怎么算" label="进度怎么算" class="ml-0.5 align-[-3px]">
            <p v-for="(tip, i) in TIPS" :key="i" class="m-0" :class="{ 'mt-1.5': i }">{{ tip }}</p>
          </InfoTip>
        </span>
      </template>
      <template v-if="canManage" #actions>
        <el-button type="primary" data-test="create-activity" @click="openEdit(null)">发布活动</el-button>
      </template>
      <template #mobile-actions>
        <InfoTip title="进度怎么算" label="进度怎么算" topbar>
          <p v-for="(tip, i) in TIPS" :key="i" class="m-0" :class="{ 'mt-1.5': i }">{{ tip }}</p>
        </InfoTip>
        <button v-if="canManage" type="button" class="topbar-add" aria-label="发布活动" @click="openEdit(null)">
          <span class="i-lucide-plus text-xl" aria-hidden="true" />
        </button>
      </template>
    </PageHeader>

    <EmptyState v-if="loadError" icon="i-lucide-cloud-off" title="训练活动没加载出来" description="检查网络后再试一次。" action="重试" @action="load" />
    <div v-else-if="!loaded" class="flex flex-col gap-4">
      <Skeleton class="h-[220px] rounded-[16px]" />
      <div class="grid grid-cols-1 gap-4 md:grid-cols-2">
        <Skeleton class="h-[140px] rounded-card" />
        <Skeleton class="h-[140px] rounded-card" />
      </div>
    </div>
    <EmptyState
      v-else-if="!items.length"
      icon="i-lucide-flag"
      title="还没有训练活动"
      :description="canManage ? '选一个题单、定好起止时间就能发布。' : '管理员发布后会出现在这里。'"
      :action="canManage ? '发布活动' : undefined"
      @action="openEdit(null)"
    />

    <template v-else>
      <!-- 进行中：大卡片，左边进度和继续做题，右边活动榜前 5 -->
      <section v-if="running.length" class="flex flex-col gap-3" aria-labelledby="g-running" data-test="running">
        <h2 id="g-running" class="m-0 flex items-center gap-2.5 text-base text-ink font-semibold"><span class="size-2 rounded-full bg-success" aria-hidden="true" />进行中</h2>
        <article v-for="a in running" :key="a.id" class="running-card" :data-activity="a.id">
          <div class="min-w-0 flex flex-col gap-3">
            <div class="flex flex-wrap items-center gap-2.5">
              <h3 class="m-0 text-lg text-ink font-bold [overflow-wrap:anywhere] md:text-xl">{{ a.title }}</h3>
              <span class="h-[22px] inline-flex items-center rounded-md bg-success-soft px-2 text-xs text-success-ink font-semibold">{{ endLabel(a) }}</span>
              <span v-if="!a.published" class="h-[22px] inline-flex items-center rounded-md bg-danger-soft px-2 text-xs text-danger-ink font-semibold">未发布</span>
              <span v-if="scopeText(a)" class="h-[22px] inline-flex items-center rounded-md bg-surface-2 px-2 text-xs text-body">{{ scopeText(a) }}</span>
              <el-dropdown v-if="canManage" trigger="click" class="ml-auto" @command="(c: string) => onMore(a, c)">
                <button type="button" class="more-btn" :aria-label="`${a.title} 的更多操作`"><span class="i-lucide-ellipsis text-lg" aria-hidden="true" /></button>
                <template #dropdown>
                  <el-dropdown-menu>
                    <el-dropdown-item command="edit">编辑活动</el-dropdown-item>
                    <el-dropdown-item command="delete" class="!text-danger-ink">删除活动</el-dropdown-item>
                  </el-dropdown-menu>
                </template>
              </el-dropdown>
            </div>
            <span class="num text-[13.5px] text-muted">{{ activityRange(a.startAt, a.endAt) }} · {{ listLine(a) }}</span>
            <p v-if="a.description" class="m-0 text-sm text-body leading-[1.7] [overflow-wrap:anywhere]">{{ a.description }}</p>
            <div v-if="total(a)" class="mt-1 flex flex-col gap-1.5">
              <span class="flex justify-between text-[13.5px] text-body">
                <span>活动期间完成</span>
                <span class="num"><strong class="text-base text-ink">{{ done(a) }}</strong> / {{ total(a) }}</span>
              </span>
              <span class="h-2.5 overflow-hidden rounded-[5px] bg-surface-2" role="progressbar" :aria-valuenow="done(a)" aria-valuemin="0" :aria-valuemax="total(a)" :aria-label="`活动期间完成 ${done(a)} / ${total(a)} 题`">
                <span class="block h-full rounded-[5px] bg-success" :style="{ width: `${pct(a)}%` }" />
              </span>
              <span v-if="a.progress.beforeCount || a.progress.unknownTimeCount" class="text-[12.5px] text-muted">
                <template v-if="a.progress.beforeCount">活动开始前已通过 {{ a.progress.beforeCount }} 题，不算在这次里</template>
                <template v-if="a.progress.beforeCount && a.progress.unknownTimeCount">；</template>
                <template v-if="a.progress.unknownTimeCount">{{ a.progress.unknownTimeCount }} 题通过时间未知，两边都不算</template>
              </span>
            </div>
            <div class="mt-1 flex flex-wrap gap-2">
              <RouterLink v-if="a.listId" :to="`/problem-lists/${a.listId}`" class="primary-link" data-test="continue">继续做题</RouterLink>
              <button type="button" class="ghost-btn" @click="openDetail(a)">查看题目与榜单</button>
            </div>
          </div>
          <div class="board-col flex flex-col gap-1" data-test="running-board">
            <span class="mb-1.5 text-[13.5px] text-ink font-semibold">活动榜</span>
            <template v-if="runningDetail[a.id]">
              <p v-if="!runningDetail[a.id].leaderboard?.length" class="m-0 text-[13px] text-muted">活动期间还没有人通过，做第一个吧。</p>
              <div
                v-for="(row, i) in runningDetail[a.id].leaderboard?.slice(0, 5)"
                :key="row.userId"
                class="flex items-center gap-2.5 rounded-lg px-2 py-2 text-[13.5px]"
                :class="{ 'bg-brand-soft': row.userId === auth.me?.id }"
              >
                <span class="num w-5 text-muted font-semibold">{{ i + 1 }}</span>
                <span class="min-w-0 flex-1 truncate text-ink">{{ row.displayName }}<template v-if="row.userId === auth.me?.id">（我）</template></span>
                <span class="num text-ink font-semibold">{{ row.solvedCount }} 题</span>
              </div>
              <p v-if="myRank(a) && myRank(a)! > 5" class="m-0 px-2 text-[12.5px] text-muted">我排第 {{ myRank(a) }}</p>
            </template>
            <template v-else>
              <Skeleton v-for="i in 4" :key="i" class="my-1 h-7 w-full" />
            </template>
          </div>
        </article>
      </section>

      <div class="grid grid-cols-1 items-start gap-5 md:(grid-cols-2 gap-4)">
        <section v-for="group in [
            { key: 'upcoming', label: '即将开始', dot: 'bg-warning', rows: upcoming, empty: '没有排好的活动。' },
            { key: 'ended', label: '已结束', dot: 'bg-faint', rows: ended, empty: '还没有结束的活动。' }
          ]" :key="group.key" class="flex flex-col gap-3" :aria-labelledby="`g-${group.key}`" :data-test="group.key">
          <h2 :id="`g-${group.key}`" class="m-0 flex items-center gap-2.5 text-base text-ink font-semibold">
            <span class="size-2 rounded-full" :class="group.dot" aria-hidden="true" />{{ group.label }}
            <span v-if="group.rows.length" class="num text-[13px] text-muted font-normal">{{ group.rows.length }}</span>
          </h2>
          <p v-if="!group.rows.length" class="m-0 rounded-card bg-surface-2 px-4 py-3 text-[13px] text-muted">{{ group.empty }}</p>
          <article v-for="a in group.rows" :key="a.id" class="flex flex-col gap-2 border border-line rounded-card border-solid bg-surface px-5 py-[18px]" :data-activity="a.id">
            <div class="flex flex-wrap items-center gap-2">
              <h3 class="m-0 text-[17px] text-ink font-semibold [overflow-wrap:anywhere]">{{ a.title }}</h3>
              <span v-if="group.key === 'upcoming'" class="h-[22px] inline-flex items-center rounded-md bg-warning-soft px-2 text-xs text-warning-ink font-semibold">{{ startLabel(a) }}</span>
              <span v-if="!a.published" class="h-[22px] inline-flex items-center rounded-md bg-danger-soft px-2 text-xs text-danger-ink font-semibold">未发布</span>
              <span v-if="scopeText(a)" class="h-[22px] inline-flex items-center rounded-md bg-surface-2 px-2 text-xs text-body">{{ scopeText(a) }}</span>
              <el-dropdown v-if="canManage" trigger="click" class="ml-auto" @command="(c: string) => onMore(a, c)">
                <button type="button" class="more-btn" :aria-label="`${a.title} 的更多操作`"><span class="i-lucide-ellipsis text-lg" aria-hidden="true" /></button>
                <template #dropdown>
                  <el-dropdown-menu>
                    <el-dropdown-item command="edit">编辑活动</el-dropdown-item>
                    <el-dropdown-item command="delete" class="!text-danger-ink">删除活动</el-dropdown-item>
                  </el-dropdown-menu>
                </template>
              </el-dropdown>
            </div>
            <template v-if="group.key === 'upcoming'">
              <span class="num text-[13.5px] text-muted">{{ activityRange(a.startAt, a.endAt) }} · {{ listLine(a) }}</span>
              <p v-if="a.description" class="m-0 text-[13px] text-body leading-[1.6] [overflow-wrap:anywhere]">{{ a.description }}</p>
              <button type="button" class="text-link" @click="openDetail(a)">先看看题目 →</button>
            </template>
            <template v-else>
              <span class="num text-[13.5px] text-muted">
                {{ activityRange(a.startAt, a.endAt, true) }}<template v-if="total(a)"> · 我完成 {{ done(a) }} / {{ total(a) }}</template>
              </span>
              <span v-if="total(a)" class="h-2 overflow-hidden rounded bg-surface-2" aria-hidden="true">
                <span class="block h-full rounded bg-brand-400" :style="{ width: `${pct(a)}%` }" />
              </span>
              <button type="button" class="text-link" @click="openDetail(a)">查看题目与榜单 →</button>
            </template>
          </article>
        </section>
      </div>

      <p v-if="note" class="m-0 text-xs text-muted leading-[1.6]">{{ note }}</p>
    </template>

    <BottomSheet v-if="isMobile" v-model="detailOpen" :title="detail?.title ?? '活动详情'" full>
      <ActivityDetail v-if="detail" :activity="detail" :me-id="auth.me?.id" />
      <div v-else class="flex flex-col gap-3"><Skeleton v-for="i in 6" :key="i" class="h-9 w-full" /></div>
    </BottomSheet>
    <el-drawer v-else v-model="detailOpen" :title="detail?.title ?? '活动详情'" size="560px">
      <ActivityDetail v-if="detail" :activity="detail" :me-id="auth.me?.id" />
      <div v-else class="flex flex-col gap-3"><Skeleton v-for="i in 6" :key="i" class="h-9 w-full" /></div>
    </el-drawer>

    <ActivityEditDialog v-if="canManage" v-model="editOpen" :activity="editing" @saved="load" />
  </div>
</template>

<style scoped>
.running-card {
  display: grid;
  grid-template-columns: minmax(0, 1fr);
  gap: 20px;
  padding: 18px;
  border: 1px solid color-mix(in srgb, var(--success) 35%, var(--border));
  border-radius: 16px;
  background: var(--bg-elevated);
}

.board-col {
  padding-top: 16px;
  border-top: 1px solid var(--border-soft);
}

@media (min-width: 1024px) {
  .running-card {
    grid-template-columns: minmax(0, 1.4fr) minmax(0, 1fr);
    gap: 28px;
    padding: 22px 24px;
  }

  .board-col {
    padding: 0 0 0 24px;
    border-top: 0;
    border-left: 1px solid var(--border-soft);
  }
}

.primary-link {
  display: inline-flex;
  align-items: center;
  height: 38px;
  padding: 0 16px;
  border-radius: 10px;
  background: var(--brand-btn);
  color: #fff;
  font-size: 14px;
  font-weight: 600;
  text-decoration: none;
}

.primary-link:hover {
  filter: brightness(1.08);
}

.ghost-btn {
  height: 38px;
  padding: 0 14px;
  border: 1px solid var(--border-strong);
  border-radius: 10px;
  background: var(--bg-elevated);
  color: var(--text-strong);
  font: inherit;
  font-size: 14px;
  cursor: pointer;
}

.ghost-btn:hover {
  background: var(--surface-hover);
}

.text-link {
  align-self: flex-start;
  margin-top: 2px;
  padding: 0;
  border: 0;
  background: none;
  color: var(--brand-600);
  font: inherit;
  font-size: 13.5px;
  cursor: pointer;
}

.text-link:hover {
  text-decoration: underline;
}

.more-btn {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  width: 32px;
  height: 32px;
  border: 0;
  border-radius: 8px;
  background: transparent;
  color: var(--text-muted);
  cursor: pointer;
}

.more-btn:hover {
  background: var(--surface-hover);
}

.primary-link:focus-visible,
.ghost-btn:focus-visible,
.text-link:focus-visible,
.more-btn:focus-visible {
  outline: 2px solid var(--brand-500);
  outline-offset: 2px;
}
</style>
