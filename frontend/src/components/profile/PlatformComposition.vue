<script setup lang="ts">
import { computed } from 'vue'
import PlatformLogo from '@/components/PlatformLogo.vue'
import { platformName } from '@/utils/platformBrand'

/**
 * 平台构成：各平台已收录的通过题数（有题目明细的），按多少排；
 * 只有平台统计总数、还没有题目明细的平台单独一行（虚线），不混进上面的条里。
 */
const props = defineProps<{
  solved: { platform: string; solved: number }[]
  totals?: { platform: string; solved: number }[]
  totalOnly: { platform: string; handle: string; solvedTotal: number | null }[]
}>()

const rows = computed(() => [...(props.totals ?? props.solved)].filter((row) => row.solved > 0).sort((a, b) => b.solved - a.solved))
const max = computed(() => Math.max(1, ...rows.value.map((row) => row.solved)))
</script>

<template>
  <section id="platform-composition" class="flex flex-col gap-3 border border-line rounded-card border-solid bg-surface p-4 md:(px-[22px] py-5)" data-test="platform-composition">
    <div class="flex items-center justify-between gap-3">
      <h2 class="m-0 text-base text-ink font-semibold">平台构成</h2>
      <span class="text-[12.5px] text-muted">{{ totals ? '已绑定账号的通过题' : '已收录的通过题' }}</span>
    </div>
    <ul class="m-0 flex list-none flex-col p-0">
      <li v-for="row in rows" :key="row.platform" class="row">
        <PlatformLogo :platform="row.platform" :size="22" class="logo" />
        <span class="name text-ink">{{ platformName(row.platform) }}</span>
        <span class="track" aria-hidden="true"><span :style="{ width: `${(row.solved / max) * 100}%` }" /></span>
        <span class="num value text-ink">{{ row.solved }}</span>
      </li>
      <li v-for="item in totals ? [] : totalOnly" :key="`${item.platform}-${item.handle}`" class="row" data-test="total-only">
        <PlatformLogo :platform="item.platform" :size="22" class="logo opacity-60" />
        <span class="name text-muted">{{ platformName(item.platform) }}</span>
        <span class="track dashed" aria-hidden="true" />
        <span class="value whitespace-nowrap text-[12.5px] text-muted">只有总数 {{ item.solvedTotal }}</span>
      </li>
    </ul>
    <p v-if="!rows.length && !totalOnly.length" class="m-0 text-[13px] text-muted">还没有同步到通过题；绑定并同步平台账号后，这里按平台列出。</p>
    <p v-else-if="totals" class="m-0 text-[12.5px] text-muted leading-relaxed">洛谷公开题单与额外评测题合并去重；其他账号取已收录题数与最新平台累计数的较大值。只有累计数的平台无法展示逐题记录，跨平台同题可能重复计数。</p>
    <p v-else-if="totalOnly.length" class="m-0 text-[12.5px] text-muted leading-relaxed">只有总数的平台还没有题目明细，不计入上面的条，也不计入「已收录通过」；不代表没有刷题。</p>
  </section>
</template>

<style scoped>
.row {
  display: flex;
  align-items: center;
  gap: 12px;
  min-height: 36px;
}

#platform-composition { scroll-margin-top: 80px; }

.name {
  flex-shrink: 0;
  width: 112px;
  overflow: hidden;
  font-size: 13.5px;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.track {
  flex: 1;
  height: 10px;
  overflow: hidden;
  border-radius: 5px;
  background: var(--bg-subtle);
}

.track > span {
  display: block;
  height: 100%;
  border-radius: 5px;
  background: var(--brand-500);
}

.track.dashed {
  box-sizing: border-box;
  border: 1px dashed var(--border-strong);
  background: transparent;
}

.num.value {
  width: 40px;
  font-size: 14px;
  font-weight: 600;
  text-align: right;
}

/* 手机：图标、名字、题数一行，条在下面 */
@media (max-width: 767px) {
  .row {
    display: grid;
    grid-template-areas: 'logo name value' 'track track track';
    grid-template-columns: auto minmax(0, 1fr) auto;
    gap: 6px 8px;
    min-height: 0;
    padding: 6px 0;
  }

  .logo { grid-area: logo; }
  .name { grid-area: name; width: auto; }
  .value { grid-area: value; }

  .track {
    grid-area: track;
    height: 8px;
  }

  .num.value {
    width: auto;
  }
}
</style>
