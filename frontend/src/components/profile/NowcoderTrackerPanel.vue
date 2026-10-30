<script setup lang="ts">
import { onBeforeUnmount, onMounted, ref } from 'vue'
import { ElMessage } from 'element-plus'
import { get, post } from '@/api/client'
import { httpUrl } from '@/utils/url'

/**
 * 牛客 Tracker 通过的题：后台分批同步 Tracker 比赛目录和练习专区，这里列出已确认通过的题。
 * 原来和账号卡片一起放在档案页的「平台账号与更多记录」里；账号卡片账号页已有，这一块挪进「做题记录」。
 * 同步进行中每 10 秒刷新一次（页面在后台时不刷）。
 */
const props = defineProps<{
  userId?: number
  /** 牛客 UID */
  handle: string
  /** 牛客统计快照的附加说明，里面有 Tracker 排行榜报的题数 */
  rawExtra: string | null
}>()

interface TrackerProblem {
  pid: string
  title: string
  url: string
  difficulty: string
}

const problems = ref<TrackerProblem[]>([])
const page = ref(0)
const hasMore = ref(true)
const loading = ref(false)
const error = ref('')
const scanned = ref(0)
const updated = ref<string | null>(null)
const nextRun = ref<string | null>(null)
const queuing = ref(false)

function trackerTotal(note: string | null) {
  return note?.match(/牛客 Tracker：(\d+) 题/)?.[1] ?? '暂未获取'
}
function time(value: string | null) {
  return value ? new Date(value).toLocaleString('zh-CN') : '尚未成功同步'
}

async function load() {
  if (loading.value) return
  loading.value = true
  error.value = ''
  try {
    const result = await get<{
      problems: TrackerProblem[]
      progress: { completedPages: number; totalContests: number; complete: boolean; errorMessage: string | null; lastSuccessAt: string | null; nextRunAt: string | null }[]
    }>(props.userId ? `/accounts/nowcoder-tracker/cache/${props.userId}` : '/accounts/nowcoder-tracker/cache')
    problems.value = result.problems
    const progress = result.progress[0]
    page.value = progress?.completedPages ?? 0
    hasMore.value = !progress?.complete
    scanned.value = progress?.totalContests ?? 0
    error.value = progress?.errorMessage ?? ''
    updated.value = progress?.lastSuccessAt ?? null
    nextRun.value = progress?.nextRunAt ?? null
  } catch (e) {
    error.value = (e as Error).message
  } finally {
    loading.value = false
  }
}

async function sync() {
  queuing.value = true
  try {
    ElMessage.success(await post<string>('/accounts/nowcoder-tracker/sync'))
    await load()
  } catch (e) {
    error.value = (e as Error).message
  } finally {
    queuing.value = false
  }
}

onMounted(load)
const timer = window.setInterval(() => {
  if (!document.hidden) void load()
}, 10000)
onBeforeUnmount(() => window.clearInterval(timer))
</script>

<template>
  <section class="card" v-loading="loading && !problems.length" aria-label="牛客 Tracker 通过的题">
    <div class="card-head">
      <span class="card-title">牛客 Tracker 通过的题</span>
      <a href="https://www.nowcoder.com/problem/tracker" target="_blank" rel="noopener noreferrer" class="text-[13px]">打开 Tracker ↗</a>
    </div>
    <div class="tracker-summary">
      <div class="count">
        {{ problems.length || (hasMore ? '同步中' : 0) }}<small v-if="problems.length || !hasMore"> 道已同步通过题</small>
      </div>
      <p class="muted">{{ props.handle ? `UID ${props.handle} · ` : '' }}题目更新时间：{{ time(updated) }}</p>
      <p class="muted">Tracker 排行榜另报 {{ trackerTotal(props.rawExtra) }} 题，与题目状态接口口径不同。这里显示已确认通过题，按相同题号合并到牛客累计，重合题只计一次。</p>
      <p class="muted">{{ error ? '同步暂停，已有题目已保留' : hasMore ? '后台已安排分批同步，可离开页面' : '本轮比赛目录与练习专区已同步完成' }}。</p>
      <p v-if="nextRun" class="muted">{{ error ? '预计重试' : '下一次后台检查' }}：{{ time(nextRun) }}</p>
      <div class="actions">
        <el-button v-if="!userId" type="primary" :loading="queuing" :disabled="hasMore || !!error" @click="sync">{{ hasMore ? '后台同步已安排' : '同步 Tracker 最新记录' }}</el-button>
        <el-button :loading="loading" @click="load">刷新已同步题目</el-button>
      </div>
    </div>
    <el-alert v-if="error" :title="error" type="error" :closable="false" />
    <p class="muted">
      Tracker 比赛目录已读取 {{ page }} / {{ Math.ceil(scanned / 30) || '待获取' }} 页，随后核对练习专区。通过题保存在平台服务器上，刷新后仍在；不提供首次 AC 时间，覆盖范围为源站当前公开目录。
    </p>
    <el-table :data="problems" max-height="420" empty-text="尚无已缓存的通过题，后台会自动同步；不代表源站为 0 题">
      <el-table-column prop="pid" label="题号" width="100" />
      <el-table-column label="Tracker 题目">
        <template #default="{ row }"><a v-if="httpUrl(row.url)" :href="httpUrl(row.url)" target="_blank" rel="noopener noreferrer">{{ row.title }}</a><span v-else>{{ row.title }}</span></template>
      </el-table-column>
      <el-table-column prop="difficulty" label="Tracker 难度" width="130" />
    </el-table>
  </section>
</template>

<style scoped>
.tracker-summary {
  padding: 18px;
  border: 1px solid var(--border);
  border-radius: var(--radius-sm);
  background: var(--bg-subtle);
}

.count {
  margin-bottom: 6px;
  color: var(--text-strong);
  font-size: 21px;
  font-weight: 650;
}

.tracker-summary p {
  margin: 4px 0 0;
  font-size: 12px;
}

.actions {
  display: flex;
  flex-wrap: wrap;
  gap: 8px;
  margin-top: 12px;
}

.actions :deep(.el-button + .el-button) {
  margin-left: 0;
}

.card > p.muted {
  margin: 12px 0 8px;
}
</style>
