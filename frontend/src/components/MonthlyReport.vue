<script setup lang="ts">
import { computed, nextTick, onMounted, ref, watch } from 'vue'
import { get, notifyError } from '@/api/client'
import PlatformLogo from '@/components/PlatformLogo.vue'
import InfoTip from '@/components/ui/InfoTip.vue'
import Skeleton from '@/components/ui/Skeleton.vue'
import { platformName } from '@/utils/platformBrand'
import { beijingTime, campusDate, relativeTime } from '@/utils/statistics'

/**
 * 月度报告：‹ 2026 年 9 月 › 切月（最多到本月，往前十年），三个数（已确认新增通过、有记录的天数、同届排名）、
 * 各平台新增通过，和一张可以导出成 PNG 的分享图。分享图默认收起，但画布照常绘制，导出不受影响；
 * 分享图不写姓名、平台账号、学号和邮箱。
 */
interface Report {
  month: string
  from: string
  to: string
  generatedAt: string
  timezone: string
  newSolved: number | null
  activeDays: number | null
  enrollmentYear: number | null
  myRank: number | null
  participantCount: number
  rankNote: string
  note: string
  platforms: { platform: string; solved: number; firstAcAvailable: boolean; historyComplete: boolean; lastSuccessAt: string | null }[]
}

const THIS_MONTH = campusDate().slice(0, 7)
const FIRST_MONTH = `${Number(THIS_MONTH.slice(0, 4)) - 9}-01`
const month = ref(THIS_MONTH)
const report = ref<Report | null>(null)
const busy = ref(false)
const error = ref('')
const preview = ref(false)
const canvas = ref<HTMLCanvasElement>()
let requestId = 0

function shiftMonth(value: string, delta: number) {
  const [y, m] = value.split('-').map(Number)
  const index = y * 12 + m - 1 + delta
  return `${Math.floor(index / 12)}-${String((index % 12) + 1).padStart(2, '0')}`
}
const monthTitle = computed(() => `${month.value.slice(0, 4)} 年 ${Number(month.value.slice(5))} 月`)

async function load() {
  const request = ++requestId
  busy.value = true
  error.value = ''
  try {
    const data = await get<Report>('/stats/monthly-report', { month: month.value })
    if (request !== requestId) return
    report.value = data
    await nextTick()
    draw()
  } catch (e) {
    if (request === requestId) {
      report.value = null
      error.value = '月度报告加载失败，请重试'
      notifyError(e)
    }
  } finally {
    if (request === requestId) busy.value = false
  }
}

const maxSolved = computed(() => Math.max(1, ...(report.value?.platforms ?? []).map((p) => p.solved)))
const rankLabel = computed(() => (report.value?.enrollmentYear ? `${report.value.enrollmentYear} 级月度排名` : '校内月度排名'))

/** 分享图（固定浅色，和页面主题无关） */
const INK = '#121629'
const BODY = '#4A5170'
const MUTED = '#697089'
const BRAND = '#4F46E5'
function draw() {
  const r = report.value
  const el = canvas.value
  if (!r || !el) return
  const width = 900
  const height = 620 + r.platforms.length * 74
  el.width = width * 2
  el.height = height * 2
  const ctx = el.getContext('2d')!
  ctx.scale(2, 2)
  ctx.fillStyle = '#F5F6FA'
  ctx.fillRect(0, 0, width, height)
  ctx.fillStyle = '#FFFFFF'
  ctx.fillRect(24, 24, width - 48, height - 48)
  const font = (size: number) => `${size >= 24 ? 600 : 400} ${size}px "PingFang SC", "Microsoft YaHei", sans-serif`
  const text = (value: string, x: number, y: number, size = 16, color = BODY) => {
    ctx.fillStyle = color
    ctx.font = font(size)
    ctx.fillText(value, x, y)
  }
  const wrap = (value: string, y: number) => {
    let line = ''
    ctx.font = font(15)
    for (const char of value) {
      if (ctx.measureText(line + char).width > 780) {
        text(line, 60, y, 15)
        y += 25
        line = ''
      }
      line += char
    }
    if (line) text(line, 60, y, 15)
    return y + 25
  }
  text('校园数据档案 / 月度回顾', 60, 72, 15, MUTED)
  text(`${r.month.replace('-', ' 年 ')} 月`, 60, 135, 42, INK)
  text(`${r.from} — ${r.to} · 北京时间`, 60, 171, 16, MUTED)
  const metrics = [
    ['已确认新增通过', r.newSolved == null ? '暂无数据' : `${r.newSolved} 题`],
    ['有记录的天数', r.activeDays == null ? '暂无数据' : `${r.activeDays} 天`],
    [rankLabel.value, r.myRank == null ? '未参与' : `${r.myRank} / ${r.participantCount}`]
  ]
  metrics.forEach(([label, value], i) => {
    text(label, 60 + i * 265, 230, 15, MUTED)
    text(value, 60 + i * 265, 277, 30, '#4338CA')
  })
  text('平台构成 · 已确认新增通过', 60, 340, 20, INK)
  const maximum = Math.max(1, ...r.platforms.map((p) => p.solved))
  r.platforms.forEach((p, i) => {
    const y = 382 + i * 74
    text(platformName(p.platform), 60, y, 17, INK)
    ctx.fillStyle = '#EEF2FF'
    ctx.fillRect(250, y - 15, 330, 16)
    if (p.firstAcAvailable) {
      ctx.fillStyle = BRAND
      ctx.fillRect(250, y - 15, (330 * p.solved) / maximum, 16)
    }
    text(p.firstAcAvailable ? `${p.solved} 题` : '暂不支持此指标', 605, y)
    text(`最近同步：${beijingTime(p.lastSuccessAt)}${p.historyComplete ? '' : ' · 历史不完整'}`, 60, y + 26, 13, MUTED)
  })
  let y = 398 + r.platforms.length * 74
  if (!r.platforms.length) {
    text('尚无可核对的平台数据', 60, 382)
    y += 20
  }
  y = wrap(r.note, y)
  if (r.myRank == null) y = wrap(r.rankNote, y + 8)
  text(`报告生成：${beijingTime(r.generatedAt)}`, 60, Math.max(y + 24, height - 76), 13, MUTED)
  text('分享版已隐藏姓名、平台账号、学号与邮箱', 60, Math.max(y + 48, height - 50), 13, MUTED)
}

async function download() {
  const el = canvas.value
  if (!el || !report.value) return
  try {
    const blob = await new Promise<Blob>((resolve, reject) => el.toBlob((b) => (b ? resolve(b) : reject(new Error('图片生成失败'))), 'image/png'))
    const url = URL.createObjectURL(blob)
    const link = document.createElement('a')
    link.href = url
    link.download = `月度数据报告-${report.value.month}.png`
    link.click()
    setTimeout(() => URL.revokeObjectURL(url), 1000)
  } catch (e) {
    notifyError(e)
  }
}

watch(month, load)
onMounted(load)
</script>

<template>
  <section class="monthly-report report-card" data-test="monthly-report">
    <div class="flex flex-col gap-3 md:(flex-row items-center justify-between)">
      <h2 class="m-0 flex items-center gap-1 text-base text-ink font-semibold">
        月度报告
        <InfoTip title="月度报告怎么算">
          按北京时间的自然月回顾：已确认的新增通过、有记录的天数、同届里的位置；这个月截至今天。只算已同步、首次通过时间能确认的记录。导出的图片不写姓名、平台账号、学号和邮箱。
        </InfoTip>
      </h2>
      <div class="flex items-center gap-2">
        <div class="flex items-center gap-1" role="group" aria-label="报告月份">
          <button type="button" class="nav" aria-label="上个月" :disabled="month <= FIRST_MONTH" @click="month = shiftMonth(month, -1)">
            <span class="i-lucide-chevron-left text-lg" aria-hidden="true" />
          </button>
          <span class="num min-w-[104px] text-center text-sm text-ink font-semibold" data-test="report-month">{{ monthTitle }}</span>
          <button type="button" class="nav" aria-label="下个月" :disabled="month >= THIS_MONTH" @click="month = shiftMonth(month, 1)">
            <span class="i-lucide-chevron-right text-lg" aria-hidden="true" />
          </button>
        </div>
        <el-button :disabled="!report || busy" @click="download">
          <span class="i-lucide-image-down mr-1.5 text-[15px]" aria-hidden="true" />导出图片
        </el-button>
      </div>
    </div>

    <el-alert v-if="error" type="error" :title="error" :closable="false" show-icon>
      <el-button text @click="load">重试</el-button>
    </el-alert>
    <div v-else-if="!report" class="grid grid-cols-1 gap-3 md:grid-cols-3">
      <Skeleton v-for="n in 3" :key="n" class="h-[76px]" />
    </div>
    <template v-else>
      <dl class="m-0 grid grid-cols-1 gap-2 md:(grid-cols-3 gap-3)" :class="{ 'opacity-60': busy }" data-test="report-kpis">
        <div class="tile">
          <dt class="text-[12.5px] text-muted">已确认新增通过</dt>
          <dd class="m-0 flex items-baseline gap-1">
            <span class="num text-2xl text-ink font-semibold">{{ report.newSolved ?? '—' }}</span>
            <span v-if="report.newSolved != null" class="text-[12.5px] text-muted">题</span>
          </dd>
        </div>
        <div class="tile">
          <dt class="text-[12.5px] text-muted">有记录的天数</dt>
          <dd class="m-0 flex items-baseline gap-1">
            <span class="num text-2xl text-ink font-semibold">{{ report.activeDays ?? '—' }}</span>
            <span v-if="report.activeDays != null" class="text-[12.5px] text-muted">天</span>
          </dd>
        </div>
        <div class="tile">
          <dt class="text-[12.5px] text-muted">{{ rankLabel }}</dt>
          <dd class="m-0 flex items-baseline gap-1">
            <template v-if="report.myRank != null">
              <span class="num text-2xl text-ink font-semibold">#{{ report.myRank }}</span>
              <span class="num text-[12.5px] text-muted">/ {{ report.participantCount }} 人</span>
            </template>
            <span v-else class="text-[13px] text-muted leading-snug">{{ report.rankNote || '这个月没有参与排名' }}</span>
          </dd>
        </div>
      </dl>

      <div class="flex flex-col gap-1" :class="{ 'opacity-60': busy }">
        <h3 class="m-0 mb-1 text-sm text-ink font-semibold">各平台新增通过</h3>
        <p v-if="!report.platforms.length" class="m-0 text-[13px] text-muted">还没有可核对的平台数据。</p>
        <div v-for="p in report.platforms" :key="p.platform" class="platform-row">
          <PlatformLogo :platform="p.platform" :size="22" class="shrink-0" />
          <span class="min-w-0 flex flex-1 flex-col gap-1">
            <span class="flex items-center justify-between gap-3 text-[13.5px]">
              <span class="text-ink">{{ platformName(p.platform) }}</span>
              <span v-if="p.firstAcAvailable" class="num text-ink font-semibold">{{ p.solved }} 题</span>
              <RouterLink v-else-if="p.platform === 'LUOGU'" to="/luogu-sync" class="text-[12.5px]">完成浏览器同步后统计 →</RouterLink>
              <span v-else class="text-[12.5px] text-muted">没有逐条通过时间，不统计</span>
            </span>
            <span v-if="p.firstAcAvailable" class="track" aria-hidden="true"><span :style="{ width: `${(p.solved / maxSolved) * 100}%` }" /></span>
            <span class="text-xs text-muted" :title="beijingTime(p.lastSuccessAt)">最近同步 {{ relativeTime(p.lastSuccessAt) }}{{ p.historyComplete ? '' : ' · 历史不完整' }}</span>
          </span>
        </div>
      </div>

      <p class="m-0 text-[12.5px] text-muted leading-relaxed">{{ report.note }}</p>

      <div class="flex flex-col items-start gap-3">
        <el-button text type="primary" class="!px-0" :aria-expanded="preview" @click="preview = !preview">
          <span :class="preview ? 'i-lucide-chevron-up' : 'i-lucide-chevron-down'" class="mr-1" aria-hidden="true" />{{ preview ? '收起分享图' : '预览分享图' }}
        </el-button>
        <canvas v-show="preview" ref="canvas" role="img" :aria-label="`${report.month} 月度数据报告，${report.note}`" />
      </div>
    </template>
  </section>
</template>

<style scoped>
.report-card {
  display: flex;
  flex-direction: column;
  gap: 16px;
  padding: 16px;
  border: 1px solid var(--border);
  border-radius: var(--radius);
  background: var(--bg-elevated);
}

@media (min-width: 768px) {
  .report-card {
    padding: 20px 22px;
  }
}

.nav {
  display: flex;
  align-items: center;
  justify-content: center;
  width: 32px;
  height: 32px;
  padding: 0;
  border: 0;
  border-radius: 8px;
  background: var(--bg);
  color: var(--text-strong);
  cursor: pointer;
}

.nav:disabled {
  color: var(--text-faint);
  cursor: default;
}

.nav:focus-visible {
  outline: 2px solid var(--brand-500);
  outline-offset: 2px;
}

/* 手机上三个数是「名称 … 数」三行；平板起是三张小卡 */
.tile {
  display: flex;
  align-items: baseline;
  justify-content: space-between;
  gap: 12px;
  padding: 10px 14px;
  border: 1px solid var(--border-soft);
  border-radius: 12px;
  background: var(--bg);
}

.tile dd {
  justify-content: flex-end;
  text-align: right;
}

@media (min-width: 768px) {
  .tile {
    flex-direction: column;
    align-items: stretch;
    justify-content: flex-start;
    gap: 4px;
    padding: 12px 14px;
  }

  .tile dd {
    justify-content: flex-start;
    text-align: left;
  }
}

.platform-row {
  display: flex;
  align-items: flex-start;
  gap: 12px;
  padding: 10px 0;
  border-top: 1px solid var(--border-soft);
}

.track {
  display: block;
  height: 8px;
  overflow: hidden;
  border-radius: 4px;
  background: var(--bg-subtle);
}

.track > span {
  display: block;
  height: 100%;
  border-radius: 4px;
  background: var(--brand-500);
}

canvas {
  display: block;
  width: min(100%, 720px);
  height: auto;
  border: 1px solid var(--border);
  border-radius: 8px;
}
</style>
