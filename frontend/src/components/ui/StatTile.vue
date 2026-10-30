<script setup lang="ts">
import { computed } from 'vue'
import { RouterLink, type RouteLocationRaw } from 'vue-router'
import InfoTip from './InfoTip.vue'
import Skeleton from './Skeleton.vue'

/**
 * 数据块：标签 + ⓘ / 大数字 + 单位 / 一个小图（chart 插槽）/ 一行时间范围或补充（foot）。
 * 不再在下面写两行口径说明：口径放进 tip（标题）和 tip 插槽（正文）。
 * 每块单独加载、单独报错：loading 时显示同形状的占位，error 时写原因，可以重试。给 to 时整块是链接。
 */
const props = withDefaults(
  defineProps<{
    label: string
    value?: string | number | null
    unit?: string
    /** 整块是个链接（如排名、平台账号） */
    to?: RouteLocationRaw
    /** 数字用品牌色（如排名） */
    accent?: boolean
    /** 口径说明的标题；有它才显示 ⓘ，说明正文写在 tip 插槽里 */
    tip?: string
    foot?: string
    loading?: boolean
    error?: string | null
  }>(),
  { value: null, unit: undefined, to: undefined, accent: false, tip: undefined, foot: undefined, loading: false, error: null }
)

defineEmits<{ retry: [] }>()

/** 整块是链接时，里面不能再放按钮（ⓘ、重试）：有 to 的块别用 tip，加载中和出错时按普通块显示 */
const linked = computed(() => !!props.to && !props.loading && !props.error)
</script>

<template>
  <!-- 加载中、出错时不当链接：里面的「重试」按钮不能被链接吞掉 -->
  <component
    :is="linked ? RouterLink : 'div'"
    :to="linked ? to : undefined"
    class="stat-tile flex flex-col gap-2 rounded-card border border-solid border-line bg-surface p-3.5 md:(gap-2.5 px-5 py-[18px])"
    :class="{ 'is-link': linked }"
    :aria-busy="loading ? 'true' : undefined"
  >
    <div class="flex items-center gap-1.5 text-[12.5px] leading-5 text-muted md:text-[13.5px]">
      <span>{{ label }}</span>
      <InfoTip v-if="tip" :title="tip"><slot name="tip" /></InfoTip>
    </div>
    <template v-if="loading">
      <Skeleton class="h-[30px] w-2/5 md:h-[34px]" />
      <Skeleton v-if="$slots.chart" class="h-6 w-full md:h-[34px]" />
      <Skeleton class="h-3 w-1/2" />
    </template>
    <template v-else-if="error">
      <p class="m-0 text-[13px] leading-5 text-danger-ink">{{ error }}</p>
      <el-button class="self-start" size="small" @click="$emit('retry')">重试</el-button>
    </template>
    <template v-else>
      <div class="flex items-baseline gap-1.5">
        <span class="num text-[30px] font-semibold leading-none md:text-[34px]" :class="accent ? 'text-brand-600' : 'text-ink'">{{ value ?? '—' }}</span>
        <span v-if="unit" class="num text-[13px] text-muted md:text-sm">{{ unit }}</span>
      </div>
      <div v-if="$slots.chart" class="h-6 md:h-[34px]">
        <slot name="chart" />
      </div>
      <div v-if="foot || $slots.foot" class="text-xs leading-[18px] text-muted md:text-[12.5px]">
        <slot name="foot">{{ foot }}</slot>
      </div>
    </template>
  </component>
</template>

<style scoped>
.stat-tile.is-link {
  color: inherit;
  text-decoration: none;
  transition: border-color 0.15s ease;
}

.stat-tile.is-link:hover {
  border-color: var(--border-strong);
  text-decoration: none;
}

.stat-tile.is-link:focus-visible {
  outline: 2px solid var(--brand-500);
  outline-offset: 2px;
}
</style>
