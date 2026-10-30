<script setup lang="ts">
import { computed, nextTick, onBeforeUnmount, reactive, ref, watch } from 'vue'
import { ElMessage } from 'element-plus'
import http, { ApiError, post, notifyError } from '@/api/client'
import { campusDate, shiftDate } from '@/utils/statistics'
import PageHeader from '@/components/ui/PageHeader.vue'

interface Result {
  markdown: string
  archive: {
    schemaVersion: string; generatedAt: string
    coverage: unknown[]
    period: { from: string; to: string }
    recordSelection: { totalInRange: number; included: number; truncated: boolean }
    [key: string]: unknown
  }
}
const today = campusDate()
const dates = ref<[string,string]>([shiftDate(today,-29),today])
const form = reactive({ goal: '', language: '', weeklyHours: undefined as number | undefined, reflection: '', includeDetails: false })
const result = ref<Result | null>(null)
const busy = ref(false)
const copying = ref(false)
const downloadingSkill = ref(false)
const error = ref('')
const preview = ref<HTMLTextAreaElement>()
const importInput = ref<HTMLInputElement>()
const importing = ref(false)
const resultSection = ref<HTMLElement>()
let requestId = 0
const filename = computed(() => `学习档案-${result.value?.archive.period.from}-${result.value?.archive.period.to}`)
watch([form, dates], () => { ++requestId; busy.value=false; result.value=null; error.value='' }, { deep: true, flush: 'sync' })
onBeforeUnmount(() => { ++requestId })

async function generate() {
  if (!dates.value?.[0] || !dates.value?.[1]) { ElMessage.warning('请选择统计日期范围'); return }
  const [from,to]=dates.value
  if(from>to || from<'2000-01-01' || to>campusDate() || (Date.parse(to)-Date.parse(from))/86400000>365) {
    error.value='请选择 2000 年以后、不晚于今天且不超过 366 天的日期范围'; return
  }
  const request = ++requestId
  busy.value=true; error.value=''; result.value=null
  try {
    const data=await post<Result>('/ai-archive', { ...form, from: dates.value[0], to: dates.value[1] })
    if(request===requestId) {
      result.value=data
      // 第 2 步出现在表单下面，生成后滚过去
      await nextTick(); resultSection.value?.scrollIntoView({ behavior: 'smooth', block: 'start' })
    }
  } catch(e) { if(request===requestId) { error.value=e instanceof ApiError ? e.message : '档案生成失败，请稍后重试'; notifyError(e) } }
  finally { if(request===requestId) busy.value=false }
}
async function copy() {
  if(!result.value) return
  copying.value=true
  try { await navigator.clipboard.writeText(result.value.markdown); ElMessage.success('已复制档案与分析指引，可以粘贴给你选择的 AI') }
  catch { preview.value?.focus(); preview.value?.select(); ElMessage.warning('浏览器未允许复制，已选中预览文字，请手动复制') }
  finally { copying.value=false }
}
function selectPreview() {
  preview.value?.focus(); preview.value?.select()
  ElMessage.info('已选中档案，请使用系统复制，再粘贴到你选择的 AI')
}
function download(blob: Blob, name: string) {
  const url=URL.createObjectURL(blob), link=document.createElement('a')
  link.href=url; link.download=name; document.body.appendChild(link); link.click(); link.remove(); setTimeout(()=>URL.revokeObjectURL(url),1000)
}
function exportFile(format: 'md' | 'json') {
  if(!result.value) return
  const content=format==='md'?result.value.markdown:JSON.stringify(result.value.archive,null,2)
  download(new Blob([content],{type:format==='md'?'text/markdown;charset=utf-8':'application/json;charset=utf-8'}),`${filename.value}.${format}`)
}
async function skill() {
  downloadingSkill.value=true
  try { const response=await http.get('/ai-archive/skill',{responseType:'blob'}); download(response.data,'ai-learning-review.zip') }
  catch(e) { notifyError(e) }
  finally { downloadingSkill.value=false }
}
async function restoreContext(event: Event) {
  const input = event.target as HTMLInputElement
  const file = input.files?.[0]
  if (!file) return
  importing.value = true
  try {
    if (file.size > 5 * 1024 * 1024) throw new Error('档案超过 5 MB，请选择本站导出的 JSON 档案')
    const archive = JSON.parse(await file.text())
    const context = archive?.studentContext
    if (archive?.schemaVersion !== '1.0' || !context || typeof context !== 'object'
      || !['goal', 'language', 'reflection'].every(key => typeof context[key] === 'string')
      || context.goal.length > 500 || context.language.length > 80 || context.reflection.length > 2000
      || (context.weeklyHours != null && (!Number.isInteger(context.weeklyHours) || context.weeklyHours < 0 || context.weeklyHours > 168))) {
      throw new Error('档案格式不符合要求，请选择本站导出的 JSON 档案')
    }
    Object.assign(form, { goal: context.goal, language: context.language,
      reflection: context.reflection, weeklyHours: context.weeklyHours ?? undefined })
    // Even identical context must invalidate any previously generated preview.
    ++requestId; busy.value = false; result.value = null; error.value = ''
    ElMessage.success('已恢复上次自述，请补充本次执行结果，再生成最新档案')
  } catch (e) {
    ElMessage.error(e instanceof SyntaxError ? '文件不是有效 JSON，请选择本站导出的 JSON 档案' : e instanceof Error ? e.message : '读取档案失败')
  } finally { importing.value = false; input.value = '' }
}
/** 右边「你的数据，你决定」三条 */
const POINTS = [
  { icon: 'i-lucide-shield-check', title: '只读本站数据', text: '生成档案只读取本站已有的数据，不会触发同步；复制或下载后由你决定交不交给外部 AI，不会自动发送。' },
  { icon: 'i-lucide-lock', title: '去掉身份信息', text: '不含姓名、平台账号标识、学号、邮箱和登录凭据；你自己写的话会原样保留，分享前请检查。' },
  { icon: 'i-lucide-info', title: '通过不等于掌握', text: '档案里的分析指引会要求 AI 区分事实、推测和待确认信息，默认不替你生成题单。' }
]
const hasContext = computed(() => !!(form.goal || form.language || form.reflection || form.weeklyHours != null))
</script>


<template>
  <div class="ai-archive flex flex-col gap-5" data-test="ai-archive">
    <PageHeader title="交给 AI 分析" class="!mb-0">
      <template #description>把多平台记录整理成一份档案，再补几句你的目标，交给你习惯用的 AI</template>
      <template #actions>
        <!-- 手机顶栏已有返回箭头，这个链接只在电脑上显示 -->
        <RouterLink to="/profile" class="hidden text-sm text-brand-600 hover:underline md:inline">返回个人档案</RouterLink>
      </template>
    </PageHeader>

    <!-- 两步：选范围、补背景 → 检查后复制给 AI -->
    <ol aria-label="两个步骤" class="m-0 flex list-none items-center gap-3 p-0 text-sm">
      <li class="flex items-center gap-2" :class="result ? 'text-body' : 'text-brand-600 font-semibold'" :aria-current="result ? undefined : 'step'">
        <span class="step-dot" :class="result ? 'is-done' : 'is-active'"><span v-if="result" class="i-lucide-check text-sm" aria-hidden="true" /><template v-else>1</template></span>选范围、补背景
      </li>
      <li aria-hidden="true" class="h-0.5 w-8 rounded-sm md:w-14" :class="result ? 'bg-brand' : 'bg-line-strong'" />
      <li class="flex items-center gap-2" :class="result ? 'text-brand-600 font-semibold' : 'text-muted'" :aria-current="result ? 'step' : undefined">
        <span class="step-dot" :class="{ 'is-active': result }">2</span>检查后复制给 AI
      </li>
    </ol>

    <div class="grid grid-cols-1 items-start gap-5 lg:grid-cols-[minmax(0,7fr)_minmax(0,5fr)]">
      <section class="flex flex-col gap-4 border border-line rounded-card border-solid bg-surface p-4 md:(px-6 py-[22px])" aria-labelledby="step1-title" data-test="step1">
        <div class="flex flex-wrap items-center justify-between gap-2">
          <h2 id="step1-title" class="m-0 text-base text-ink font-semibold">01 · 选范围，补背景</h2>
          <input ref="importInput" type="file" accept=".json,application/json" hidden @change="restoreContext" />
          <el-button text type="primary" :loading="importing" :disabled="hasContext" data-test="restore" @click="importInput?.click()">
            <span class="i-lucide-file-up mr-1 text-base" aria-hidden="true" />从上次 JSON 档案恢复自述
          </el-button>
        </div>
        <p class="m-0 -mt-2 text-xs text-muted leading-[1.6]">恢复只在本机读取目标、语言、可用时间和自述，不上传文件，也不导入旧统计；表单为空时才能恢复，免得覆盖你正在写的内容。</p>
        <el-form label-position="top" class="archive-form" @submit.prevent="generate">
          <el-form-item label="统计范围（默认近 30 天，最多 366 天）">
            <el-date-picker v-model="dates" type="daterange" value-format="YYYY-MM-DD" start-placeholder="开始日期" end-placeholder="结束日期" :clearable="false" :disabled-date="(d: Date) => campusDate(d) > campusDate() || d.getFullYear() < 2000" />
          </el-form-item>
          <el-form-item label="我想达到的目标（选填）">
            <el-input v-model="form.goal" type="textarea" :rows="2" maxlength="500" show-word-limit placeholder="例如：跟上 C++ 课程，能独立解释自己写过的代码" data-test="goal" />
          </el-form-item>
          <div class="grid grid-cols-1 gap-x-3.5 md:grid-cols-2">
            <el-form-item label="在学的语言（选填）"><el-input v-model="form.language" maxlength="80" placeholder="例如：C++" /></el-form-item>
            <el-form-item label="每周能花几小时（选填）"><el-input-number v-model="form.weeklyHours" :min="0" :max="168" :precision="0" controls-position="right" /></el-form-item>
          </div>
          <el-form-item label="最近学了什么，哪里还不懂？（选填）">
            <el-input v-model="form.reflection" type="textarea" :rows="4" maxlength="2000" show-word-limit placeholder="可以写哪些题是自己独立做出来的、哪些看了题解，想让 AI 重点看什么。不要填账号密码或其他身份信息。" />
          </el-form-item>
          <el-checkbox v-model="form.includeDetails" data-test="include-details">附上这段时间的逐条记录（最多 1000 条）</el-checkbox>
          <p class="mb-4 mt-1 text-xs text-muted leading-[1.6]">不勾选时附最近 20 条。累计背景、每天统计、平台覆盖范围和分析指引总会带上；逐条记录被截断时会写明。日期未知的记录只算进累计背景。</p>
          <el-alert v-if="error" :title="error" type="error" :closable="false" show-icon class="!mb-4" />
          <el-button type="primary" size="large" native-type="submit" :loading="busy" data-test="generate">生成档案预览</el-button>
        </el-form>
      </section>

      <aside class="flex flex-col gap-4">
        <section class="flex flex-col gap-3.5 border border-line rounded-card border-solid bg-surface p-5" aria-labelledby="points-title">
          <h2 id="points-title" class="m-0 text-[15px] text-ink font-semibold">你的数据，你决定</h2>
          <div v-for="p in POINTS" :key="p.title" class="flex gap-3">
            <span class="size-8 flex shrink-0 items-center justify-center rounded-[9px] bg-brand-soft text-brand" aria-hidden="true"><span :class="p.icon" class="text-[17px]" /></span>
            <span class="flex flex-col gap-0.5"><span class="text-sm text-ink font-semibold">{{ p.title }}</span><span class="text-[13px] text-muted leading-[1.6]">{{ p.text }}</span></span>
          </div>
        </section>

        <details class="disclosure">
          <summary data-test="skill-toggle">
            <span class="flex flex-1 flex-col gap-0.5"><span class="text-sm text-ink font-semibold">需要安装 Skill 吗？</span><span class="text-[12.5px] text-muted">普通聊天直接粘贴就行；支持 Skill 的客户端可以下载分析技能包</span></span>
            <span class="chev i-lucide-chevron-down text-lg text-muted" aria-hidden="true" />
          </summary>
          <div class="flex flex-col items-start gap-3 px-[18px] pb-4">
            <p class="m-0 text-[13px] text-body leading-[1.7]">普通 AI 聊天直接粘贴或上传 Markdown 就行。支持 Agent Skills 的客户端可以先装下面的技能包，再给它档案。技能包不含个人数据，也不会自己联网取数据。</p>
            <el-button :loading="downloadingSkill" @click="skill">下载分析 Skill 包</el-button>
          </div>
        </details>

        <details class="disclosure">
          <summary>
            <span class="flex flex-1 flex-col gap-0.5"><span class="text-sm text-ink font-semibold">从记录到下一次复盘</span><span class="text-[12.5px] text-muted">三步用好这份档案</span></span>
            <span class="chev i-lucide-chevron-down text-lg text-muted" aria-hidden="true" />
          </summary>
          <ol class="m-0 flex flex-col gap-2.5 pb-4 pl-9 pr-[18px] text-[13px] text-body leading-[1.7]">
            <li>先到 <RouterLink to="/accounts" target="_blank" rel="noopener" class="text-brand-600">平台账号（新标签页）</RouterLink> 看同步有没有完成，再回来生成档案。生成只读已有数据，不会触发同步；刷新或关掉本页前请下载 JSON 档案，保留自述。</li>
            <li>普通 AI 聊天直接粘贴档案或上传 Markdown，说清楚希望它重点分析什么。内容太长就缩短日期范围、不勾逐条记录，或改用上传文件。</li>
            <li>挑一条建议去做，记下是不是独立完成、哪里还不懂。下次从 JSON 档案恢复自述，补上「上次建议和执行结果」，同步后再生成新档案；想比较变化就把两份档案一起给 AI。本站暂不保存 AI 的回复。</li>
          </ol>
        </details>

        <div v-if="!result" class="flex flex-col items-center gap-1.5 border-[1.5px] border-line-strong rounded-card border-dashed p-[22px] text-center">
          <span class="text-sm text-body font-semibold">第 2 步在这里出现</span>
          <span class="text-[12.5px] text-muted leading-[1.6]">生成后可以预览全文，再复制给 AI，或下载 Markdown / JSON</span>
        </div>
      </aside>
    </div>

    <section v-if="result" ref="resultSection" class="scroll-mt-20 flex flex-col gap-3 border border-line rounded-card border-solid bg-surface p-4 md:(px-6 py-[22px])" aria-labelledby="step2-title" data-test="step2">
      <div class="flex flex-wrap items-center justify-between gap-2">
        <h2 id="step2-title" class="m-0 text-base text-ink font-semibold">02 · 检查后，交给 AI</h2>
        <span class="rounded-full bg-surface-2 px-2.5 py-1 text-[12.5px] text-body">{{ result.archive.coverage.length }} 个账号 · {{ result.archive.recordSelection.included }} 条逐条记录</span>
      </div>
      <p v-if="!result.archive.coverage.length" class="m-0 text-[13px] text-muted">还没绑定账号。档案里只有你的自述和空数据说明，不能据此判断学习情况。<RouterLink to="/accounts" class="text-brand-600">先绑定平台账号</RouterLink></p>
      <el-alert v-if="result.archive.recordSelection.truncated" type="info" :closable="false" show-icon :title="`这段时间共 ${result.archive.recordSelection.totalInRange} 条记录，这次附了最近 ${result.archive.recordSelection.included} 条，不是完整历史。`" />
      <p class="m-0 text-[13px] text-muted">{{ result.archive.period.from }} 至 {{ result.archive.period.to }} · 生成于 {{ new Date(result.archive.generatedAt).toLocaleString('zh-CN', { timeZone: 'Asia/Shanghai' }) }}。改了表单要重新生成，免得分享旧范围的数据。</p>
      <textarea ref="preview" class="preview-text" aria-label="AI 学习档案预览" readonly :value="result.markdown" spellcheck="false" data-test="preview" />
      <div class="flex flex-wrap gap-2.5" data-test="export-actions">
        <el-button type="primary" :loading="copying" data-test="copy" @click="copy"><span class="i-lucide-copy mr-1.5 text-base" aria-hidden="true" />复制给 AI</el-button>
        <el-button class="!ml-0" @click="exportFile('md')">下载 Markdown 档案</el-button>
        <el-button class="!ml-0" @click="exportFile('json')">下载 JSON 数据</el-button>
        <el-button class="!ml-0" text @click="selectPreview">选中文字手动复制</el-button>
      </div>
      <p class="m-0 text-xs text-muted leading-[1.7]">内置浏览器存不了文件或粘贴不了时，可以选中文字用系统复制，或在系统浏览器里打开本站下载。粘贴后可以补一句：「请结合我的目标分析这份档案，先说明数据局限，再给出三个可执行的建议。」自述只用于这次生成，不保存到个人资料。</p>
    </section>
  </div>
</template>

<style scoped>
.step-dot {
  display: flex;
  align-items: center;
  justify-content: center;
  width: 26px;
  height: 26px;
  border: 1.5px solid var(--border-strong);
  border-radius: 50%;
  color: var(--text-muted);
  font-size: 13px;
  font-variant-numeric: tabular-nums;
}

.step-dot.is-active,
.step-dot.is-done {
  border-color: var(--brand-btn);
  background: var(--brand-btn);
  color: #fff;
}

.archive-form :deep(.el-date-editor) {
  width: 100%;
  max-width: 100%;
}

.archive-form :deep(.el-input-number) {
  width: 100%;
}

.disclosure {
  border: 1px solid var(--border);
  border-radius: 14px;
  background: var(--bg-elevated);
}

.disclosure summary {
  display: flex;
  align-items: center;
  gap: 10px;
  padding: 14px 18px;
  cursor: pointer;
  list-style: none;
}

.disclosure summary::-webkit-details-marker {
  display: none;
}

.disclosure summary:focus-visible {
  border-radius: 14px;
  outline: 2px solid var(--brand-500);
  outline-offset: 2px;
}

.disclosure[open] .chev {
  transform: rotate(180deg);
}

.preview-text {
  display: block;
  box-sizing: border-box;
  width: 100%;
  min-height: 380px;
  padding: 16px;
  border: 1px solid var(--border);
  border-radius: 10px;
  background: var(--bg-subtle);
  color: var(--text);
  font-family: 'JetBrains Mono', ui-monospace, Consolas, monospace;
  font-size: 12.5px;
  line-height: 1.7;
  resize: vertical;
}

.preview-text:focus-visible {
  outline: 2px solid var(--brand-500);
  outline-offset: 1px;
}
</style>
