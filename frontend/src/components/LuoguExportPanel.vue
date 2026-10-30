<script setup lang="ts">
import { computed, ref, watch } from 'vue'
import { ElMessage } from 'element-plus'
import { parsePractice, passedCsv } from '@/utils/luoguExport'
import { get } from '@/api/client'
import { codeforcesPassed, type HistoryProblem } from '@/utils/codeforcesExport'
import PlatformLogo from '@/components/PlatformLogo.vue'
import { httpUrl } from '@/utils/url'

const props = withDefaults(defineProps<{ platform?: 'LUOGU' | 'CODEFORCES' }>(), { platform: 'LUOGU' })
const isCf = computed(() => props.platform === 'CODEFORCES')
const difficultyName = computed(() => isCf.value ? 'CF Rating' : '洛谷难度编号')

const opened = ref(false)
const data = ref<ReturnType<typeof parsePractice> | null>(null)
const error = ref('')
const loading = ref(false)
const filename = ref('')
const tagProgress = ref<{total: number; completed: number; message: string} | null>(null)
const query = ref('')
const difficulty = ref<number | ''>('')
const tag = ref('')
const includeTags = ref(true)
const grouped = ref(false)
const page = ref(1)
const input = ref<HTMLInputElement>()
let generation = 0
const tags = computed(() => [...new Set(data.value?.problems.flatMap(p => p.tags) ?? [])].sort())
const difficulties = computed(() => [...new Set(data.value?.problems.flatMap(p => p.difficulty === null ? [] : [p.difficulty]) ?? [])].sort((a, b) => a - b))
const missing = computed(() => data.value?.problems.filter(p => !p.tags.length).length ?? 0)
const filtered = computed(() => (data.value?.problems ?? []).filter(p =>
  (!query.value || `${p.pid} ${p.title}`.toLowerCase().includes(query.value.trim().toLowerCase())) &&
  (difficulty.value === '' || p.difficulty === difficulty.value) &&
  (!tag.value || (tag.value === '__missing' ? !p.tags.length : p.tags.includes(tag.value)))))
const visible = computed(() => filtered.value.slice((page.value - 1) * 20, page.value * 20))
watch([query, difficulty, tag], () => { page.value = 1 })

function clear() {
  generation++
  data.value = null
  error.value = ''
  filename.value = ''
  loading.value = false
  query.value = ''; difficulty.value = ''; tag.value = ''; page.value = 1
  if (input.value) input.value.value = ''
}
async function readFile(event: Event) {
  const file = (event.target as HTMLInputElement).files?.[0]
  if (!file) return
  clear()
  const current = generation
  loading.value = true
  try {
    if (file.size > 20 * 1024 * 1024) throw new Error('文件过大，请选择 20 MB 以内的练习页响应。')
    const text = await file.text()
    if (current !== generation) return
    data.value = parsePractice(text)
    filename.value = file.name
  } catch (e) {
    if (current === generation) error.value = (e as Error).message
  } finally {
    if (current === generation) loading.value = false
  }
}
async function loadCodeforces() {
  clear()
  const current = generation
  loading.value = true
  try {
    const rows: HistoryProblem[] = []
    for (let n = 1; ; n++) {
      const result = await get<{ rows: HistoryProblem[]; total: number }>(`/history?platform=CODEFORCES&accepted=true&page=${n}&size=100`)
      if (current !== generation) return
      if (result.total > 100000) throw new Error('通过记录超过当前导出上限（10 万条），未生成不完整题单。')
      rows.push(...result.rows)
      if (rows.length >= result.total) break
      if (!result.rows.length || n >= 1000) throw new Error('记录读取不完整，请刷新后重试。')
    }
    data.value = { problems: codeforcesPassed(rows), uid: '', excluded: 0, reported: null }
  } catch (e) {
    if (current === generation) error.value = (e as Error).message
  } finally {
    if (current === generation) loading.value = false
  }
}
async function loadLuogu() {
  clear()
  const current = generation
  loading.value = true
  try {
    const rows = await get<{ pid: string; title: string; url: string; difficulty: string | null; tagsJson: string }[]>('/history/solved?platform=LUOGU')
    if (current !== generation) return
    data.value = { uid: '', excluded: 0, reported: null, problems: rows.map(p => ({
      pid: p.pid, title: p.title, url: p.url,
      difficulty: p.difficulty !== null && Number.isFinite(Number(p.difficulty)) ? Number(p.difficulty) : null,
      tags: JSON.parse(p.tagsJson) as string[],
    })) }
    filename.value = '本站已同步的在线题单'
    tagProgress.value = await get('/luogu/tags/progress')
  } catch (e) { if (current === generation) error.value = (e as Error).message }
  finally { if (current === generation) loading.value = false }
}
function download(format: 'csv' | 'json') {
  if (!filtered.value.length) return
  const payload = format === 'csv' ? passedCsv(filtered.value, includeTags.value, grouped.value, difficultyName.value) : JSON.stringify({
    source: isCf.value ? 'CODEFORCES_LOCAL_HISTORY' : filename.value === '本站已同步的在线题单' ? 'LUOGU_SYNCED_PRACTICE' : 'LUOGU_SAVED_PRACTICE', uid: data.value?.uid, count: filtered.value.length,
    note: '去重后的已通过题单；标签缺失表示未获取。',
    problems: filtered.value.map(p => includeTags.value ? p : { pid: p.pid, title: p.title, url: p.url, difficulty: p.difficulty }),
  }, null, 2)
  const url = URL.createObjectURL(new Blob([payload], { type: format === 'csv' ? 'text/csv;charset=utf-8' : 'application/json;charset=utf-8' }))
  const link = document.createElement('a')
  link.href = url
  link.download = `${props.platform.toLowerCase()}-passed-${data.value?.uid || 'export'}${format === 'csv' && grouped.value ? '-by-tag' : ''}.${format}`
  link.click()
  setTimeout(() => URL.revokeObjectURL(url), 1000)
  ElMessage.success(`已生成 ${filtered.value.length} 道题目的导出文件`)
}
</script>

<template>
  <section class="export-card luogu-export" :data-test="`export-${platform.toLowerCase()}`">
    <!-- 手机上按钮单独一行占满，标题和说明不被挤窄 -->
    <div class="grid grid-cols-[auto_minmax(0,1fr)] items-center gap-3 md:flex">
      <PlatformLogo :platform="platform" :size="28" class="shrink-0" />
      <div class="min-w-0 md:flex-1">
        <h2 class="m-0 text-base text-ink font-semibold">{{ isCf ? 'Codeforces 题单导出' : '洛谷题单导出' }}</h2>
        <p class="m-0 mt-0.5 text-[13px] text-muted">把已经通过的题目整理成自己的复习清单，导出 CSV 或 JSON。</p>
      </div>
      <el-button class="col-span-2 md:col-span-1" :aria-expanded="opened" @click="opened = !opened">{{ opened ? '收起' : '导出已通过题单' }}</el-button>
    </div>
    <div v-if="opened" v-loading="loading">
      <p v-if="isCf" class="muted">读取当前登录账号已同步的 Codeforces 通过记录，按题号去重并保留全部标签。请先在“平台绑定”完成同步；导出范围不代表尚未同步的全部历史。</p>
      <p v-else class="muted">可直接读取本站已同步的洛谷题单。先在平台绑定页“立即同步”以获取最新数据；也可选择 HTML/TXT 练习页文件，仅用于本地预览和导出。</p>
      <div class="actions">
        <el-button v-if="isCf" type="primary" @click="loadCodeforces">读取已同步题目</el-button>
        <el-button v-else type="primary" @click="loadLuogu">读取已同步洛谷题目</el-button>
        <label v-if="!isCf" class="file-label">或选择练习页文件<input ref="input" type="file" accept=".html,.htm,.txt,text/html,text/plain" @change="readFile" /></label>
        <el-button v-if="data || error" @click="clear">清空</el-button>
      </div>
      <el-alert v-if="error" :title="error" type="error" :closable="false" />
      <template v-if="data">
        <el-alert v-if="!isCf && tagProgress" type="info" :closable="false" :title="`算法标签已检查 ${tagProgress.completed} / ${tagProgress.total} 题。${tagProgress.message}；点击“读取已同步洛谷题目”刷新。`" />
        <p class="summary"><span v-if="data.uid">UID {{ data.uid }} · </span>已读取 <strong>{{ data.problems.length }}</strong> 道已通过题目 <span v-if="!isCf && data.uid">· 排除其他列表 {{ data.excluded }} 道</span> <span class="muted">{{ filename }}</span></p>
        <el-alert v-if="!data.problems.length" type="info" :closable="false" title="暂无可导出的已通过题目，请检查数据来源或完成平台同步。" />
        <el-alert v-if="data.reported !== null && data.reported !== data.problems.length" type="warning" :closable="false" :title="`页面报告通过 ${data.reported} 道，文件实际只有 ${data.problems.length} 道；本次只导出实际读取的题目。`" />
        <el-alert v-if="missing" type="info" :closable="false" :title="`${missing} 道题尚未获取算法标签，分组时归入“未获取标签”。`" />
        <div class="filters">
          <el-input v-model="query" aria-label="搜索题号或标题" placeholder="搜索题号 / 标题" clearable />
          <el-select v-model="difficulty" aria-label="难度筛选"><el-option label="全部难度" value="" /><el-option v-for="d in difficulties" :key="d" :label="`${difficultyName} ${d}`" :value="d" /></el-select>
          <el-select v-model="tag" aria-label="标签筛选"><el-option label="全部标签" value="" /><el-option label="未获取标签" value="__missing" /><el-option v-for="t in tags" :key="t" :label="t" :value="t" /></el-select>
        </div>
        <div class="actions">
          <el-checkbox v-model="includeTags">包含标签列 / 字段</el-checkbox>
          <el-checkbox v-model="grouped">CSV 按标签分组（多标签题目重复列出）</el-checkbox>
          <el-button type="primary" :disabled="!filtered.length" @click="download('csv')">导出 CSV（{{ filtered.length }} 题）</el-button>
          <el-button :disabled="!filtered.length" @click="download('json')">导出 JSON</el-button>
        </div>
        <el-table :data="visible" empty-text="当前筛选下没有题目">
          <el-table-column prop="pid" label="题号" width="110" />
          <el-table-column label="已通过题目" min-width="200"><template #default="{ row }"><a v-if="httpUrl(row.url)" :href="httpUrl(row.url)" target="_blank" rel="noopener noreferrer">{{ row.title }}</a><span v-else>{{ row.title }}</span></template></el-table-column>
          <el-table-column prop="difficulty" :label="difficultyName" width="130" />
          <el-table-column label="算法标签" min-width="160"><template #default="{ row }">{{ row.tags.join('、') || '未获取标签' }}</template></el-table-column>
        </el-table>
        <el-pagination v-model:current-page="page" :page-size="20" :total="filtered.length" layout="prev, pager, next, total" />
      </template>
    </div>
  </section>
</template>

<style scoped>
.export-card { display: flex; flex-direction: column; gap: 4px; min-width: 0; padding: 16px; border: 1px solid var(--border); border-radius: var(--radius); background: var(--bg-elevated); }
@media (min-width: 768px) { .export-card { padding: 20px 22px; } }
.actions { display: flex; flex-wrap: wrap; align-items: center; gap: 12px; margin: 16px 0; }
.filters { display: grid; grid-template-columns: 2fr 1fr 1fr; gap: 12px; margin-top: 16px; }
.file-label { display: flex; flex-wrap: wrap; align-items: center; gap: 12px; font-size: 14px; }
input { max-width: 100%; }
.summary { line-height: 1.8; overflow-wrap: anywhere; }
.summary strong { color: var(--brand-500); font-size: 22px; }
.el-pagination { margin-top: 16px; max-width: 100%; overflow: auto; }
.el-alert { margin: 10px 0; }
@media (max-width: 640px) { .filters { grid-template-columns: 1fr; } }
</style>
