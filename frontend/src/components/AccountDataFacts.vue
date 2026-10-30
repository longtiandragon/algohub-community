<script setup lang="ts">
import { computed } from 'vue'
import type { AccountDataItem, PlatformAccount } from '@/api/types'
import { platformName } from '@/utils/platformBrand'
import { DATA_ITEM_LABEL, DATA_ITEM_STATUS, beijingTime, dataNote, dataSummary, relativeTime, trackerNote } from '@/utils/statistics'

/**
 * 一个账号的数据概况：平台统计题数、已收录明细、有通过时间的题数与各数据项最近成功时间分开写。
 * 没取得、取失败与平台说是零各自说明，不把"没取到"写成 0。
 * compact：首页用，一行关键数字，只在数字说不清时补一句；各数据项的明细留给账号页。
 * itemsOnly：账号列表展开「详情」时用，数字已在行里，只列 Tracker 说明与各数据项。
 * hideTimed：账号列表的行里不显示「有时间 N」。
 */
const props = defineProps<{ account: PlatformAccount; compact?: boolean; itemsOnly?: boolean; hideTimed?: boolean }>()

const summary = computed(() => dataSummary(props.account, platformName(props.account.platform)))
const compactNote = computed(() => dataNote(props.account))
const tracker = computed(() => trackerNote(props.account))
const order = Object.keys(DATA_ITEM_LABEL)
const items = computed(() => [...(props.account.dataItems ?? [])]
  .sort((a, b) => order.indexOf(a.item) - order.indexOf(b.item)))

/** 例行的"历史已完整"只打个标记；真正有信息量的说明（回填中、只公开最近几道等）才单独占一行。 */
function note(item: AccountDataItem) {
  if (item.status !== 'SUCCESS') return item.errorMsg
  return item.detail && item.detail !== '历史已完整' ? item.detail : null
}

/**
 * 同步的是完整提交历史、又不另取平台统计的（Codeforces、AtCoder、QOJ）：已收录明细就是全部通过题，
 * 平台统计一栏写"以明细为准"，不写成吓人的"尚未取得"。
 */
const totalsFromDetails = computed(() => props.account.platformSolvedTotal == null
  && props.account.historyComplete
  && !props.account.dataItems?.some((item) => item.item === 'TOTALS'))

const numbers = computed(() => {
  const a = props.account
  const list: { label: string; value: string; muted?: boolean }[] = []
  if (!totalsFromDetails.value) {
    list.push({ label: '平台统计', value: a.platformSolvedTotal != null ? `${a.platformSolvedTotal}` : '尚未取得', muted: a.platformSolvedTotal == null })
  }
  list.push({ label: '明细', value: `${a.collectedProblems ?? 0}` })
  if (!props.hideTimed) list.push({ label: '有时间', value: `${a.timedProblems ?? 0}` })
  if (a.currentRating != null) list.push({ label: 'Rating', value: `${a.currentRating}` })
  return list
})
</script>

<template>
  <div class="data-facts" :class="{ compact }">
    <p v-if="!compact && !itemsOnly" class="data-summary">{{ summary }}</p>

    <template v-if="compact">
      <p class="data-inline">
        <span v-for="n in numbers" :key="n.label">
          {{ n.label }} <b :class="{ muted: n.muted }">{{ n.value }}</b>
        </span>
      </p>
      <p v-if="compactNote" class="data-summary">{{ compactNote }}</p>
    </template>

    <template v-else>
      <dl v-if="!itemsOnly" class="data-numbers">
        <div>
          <dt>平台统计</dt>
          <dd v-if="account.platformSolvedTotal != null"><strong>{{ account.platformSolvedTotal }}</strong> 题</dd>
          <dd v-else-if="totalsFromDetails" class="muted" title="同步的是完整提交历史，已收录明细就是全部通过题，不另取平台统计">以明细为准</dd>
          <dd v-else class="muted">尚未取得</dd>
        </div>
        <div>
          <dt>已收录明细</dt>
          <dd><strong>{{ account.collectedProblems ?? 0 }}</strong> 道</dd>
        </div>
        <div>
          <dt>有通过时间</dt>
          <dd><strong>{{ account.timedProblems ?? 0 }}</strong> 道</dd>
        </div>
        <div v-if="account.currentRating != null">
          <dt>Rating</dt>
          <dd><strong>{{ account.currentRating }}</strong><small>最高 {{ account.maxRating }}</small></dd>
        </div>
        <div v-if="account.trackerProblems">
          <dt>仅 Tracker</dt>
          <dd><strong>{{ account.trackerProblems }}</strong> 道</dd>
        </div>
      </dl>

      <p v-if="tracker" class="data-note">{{ tracker }}</p>

      <ul v-if="items.length" class="data-items">
        <li v-for="item in items" :key="item.item" :class="`is-${DATA_ITEM_STATUS[item.status]?.type ?? 'info'}`">
          <div class="item-row">
            <span class="item-dot" aria-hidden="true" />
            <span class="item-name">{{ DATA_ITEM_LABEL[item.item] ?? item.item }}</span>
            <span v-if="item.status !== 'SUCCESS'" class="item-state">{{ DATA_ITEM_STATUS[item.status]?.label ?? item.status }}</span>
            <span v-else-if="item.detail === '历史已完整'" class="item-state quiet">完整</span>
            <span class="item-time" :title="item.lastSuccessAt ? `最近成功：${beijingTime(item.lastSuccessAt)}` : '还没有成功过'">
              {{ item.lastSuccessAt ? relativeTime(item.lastSuccessAt) : '未成功过' }}
            </span>
          </div>
          <p v-if="note(item)" class="item-note">{{ note(item) }}</p>
        </li>
      </ul>
      <p v-else class="muted item-none">还没有任何一项同步成功过；同步后这里会分别列出各项的最近成功时间。</p>
    </template>
  </div>
</template>

<style scoped>
.data-facts {
  display: grid;
  gap: 10px;
  /* 数字格子按卡片自身宽度排：窄卡片上四格排成 2×2，不留一格掉到下一行 */
  container-type: inline-size;
}

.data-summary {
  margin: 0;
  font-size: 13.5px;
  line-height: 1.6;
  color: var(--text-strong);
}

/* 首页卡片里的补充说明都是需要看到的（零题、缺明细、口径不同），不截断 */
.compact .data-summary {
  font-size: 12.5px;
  color: var(--text);
}

.data-inline {
  margin: 0;
  display: flex;
  flex-wrap: wrap;
  gap: 4px 14px;
  font-size: 12.5px;
  color: var(--text-muted);
}

.data-inline b {
  color: var(--text-strong);
  font-weight: 640;
  font-variant-numeric: tabular-nums;
}

.data-inline b.muted {
  color: var(--text-muted);
  font-weight: 400;
}

.data-numbers {
  margin: 0;
  display: grid;
  grid-template-columns: repeat(auto-fit, minmax(72px, 1fr));
  gap: 6px;
}

.data-numbers div {
  border-radius: var(--radius-sm);
  padding: 7px 8px;
  background: var(--bg-subtle);
  min-width: 0;
}

.data-numbers dt {
  font-size: 12px;
  color: var(--text-muted);
  white-space: nowrap;
}

.data-numbers dd {
  margin: 2px 0 0;
  font-size: 12.5px;
  color: var(--text-muted);
  display: flex;
  align-items: baseline;
  flex-wrap: wrap;
  gap: 0 4px;
}

.data-numbers strong {
  font-size: 16px;
  font-weight: 650;
  color: var(--text-strong);
  font-variant-numeric: tabular-nums;
}

.data-numbers small {
  font-size: 12px;
  white-space: nowrap;
}

.data-note {
  margin: 0;
  font-size: 12.5px;
  line-height: 1.6;
  color: var(--text-muted);
}

.data-items {
  list-style: none;
  margin: 0;
  padding: 0;
  display: grid;
  gap: 6px;
  font-size: 12.5px;
}

.item-row {
  display: flex;
  align-items: center;
  gap: 8px;
  min-width: 0;
}

.item-dot {
  width: 7px;
  height: 7px;
  border-radius: 50%;
  flex: none;
  background: var(--text-muted);
}

.is-success .item-dot {
  background: var(--success);
}

.is-warning .item-dot {
  background: var(--warning);
}

.is-danger .item-dot {
  background: var(--danger);
}

.item-name {
  color: var(--text);
  min-width: 0;
}

.item-state {
  font-size: 12px;
  padding: 0 6px;
  border-radius: 999px;
  line-height: 18px;
  flex: none;
}

.is-warning .item-state {
  color: var(--warning-ink);
  background: color-mix(in srgb, var(--warning) 12%, transparent);
}

.is-danger .item-state {
  color: var(--danger-ink);
  background: color-mix(in srgb, var(--danger) 12%, transparent);
}

.item-state.quiet {
  color: var(--text-muted);
  background: var(--bg-subtle);
}

.item-time {
  margin-left: auto;
  color: var(--text-muted);
  white-space: nowrap;
  font-variant-numeric: tabular-nums;
}

.item-note {
  margin: 2px 0 0 15px;
  color: var(--text-muted);
  line-height: 1.55;
}

.is-danger .item-note,
.is-warning .item-note {
  color: var(--text);
}

.item-none {
  margin: 0;
}

@container (max-width: 330px) {
  .data-numbers:has(> div:nth-child(4)) {
    grid-template-columns: repeat(2, 1fr);
  }
}
</style>
