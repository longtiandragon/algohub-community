<script setup lang="ts">
import { nextTick, onMounted, ref, watch } from 'vue'
import type { RouteLocationRaw } from 'vue-router'

/**
 * 页面分区的标签（如个人档案的概览 / 分布 / 比赛与 Rating……）：电脑上是下划线标签，手机上是横向滚动的胶囊。
 * 每个标签是一个链接（通常改 ?tab=，刷新和分享都停在这个标签），当前的带 aria-current；
 * 切换时替换当前历史记录，不在浏览器历史里堆一串标签。short 是手机胶囊上的短名；count 大于 0 时在名字后面写待办数（如管理后台的待审批）。
 */
const props = defineProps<{
  tabs: { key: string; label: string; short?: string; count?: number; to: RouteLocationRaw }[]
  active: string
  /** 读屏名称，如「档案分区」 */
  label: string
}>()

/** 手机上胶囊一行放不下时，把当前标签滚进视野（直接打开 ?tab=reports 这类链接时，最后几个会被挡在右边） */
const nav = ref<HTMLElement>()
function reveal() {
  const bar = nav.value
  const tab = bar?.querySelector<HTMLElement>('.page-tab.is-active')
  if (!bar || !tab || bar.scrollWidth <= bar.clientWidth) return
  const left = tab.offsetLeft - bar.offsetLeft
  if (left < bar.scrollLeft || left + tab.offsetWidth > bar.scrollLeft + bar.clientWidth) {
    bar.scrollLeft = left - (bar.clientWidth - tab.offsetWidth) / 2
  }
}
onMounted(reveal)
watch(() => props.active, () => void nextTick(reveal))
</script>

<template>
  <nav ref="nav" :aria-label="label" class="page-tabs">
    <RouterLink
      v-for="tab in tabs"
      :key="tab.key"
      :to="tab.to"
      replace
      class="page-tab"
      :class="{ 'is-active': tab.key === active }"
      :aria-current="tab.key === active ? 'page' : undefined"
      :data-tab="tab.key"
    >
      <template v-if="tab.short">
        <span class="md:hidden">{{ tab.short }}</span><span class="hidden md:inline">{{ tab.label }}</span>
      </template>
      <template v-else>{{ tab.label }}</template>
      <span v-if="tab.count" class="tab-count num" :aria-label="`${tab.count} 条待处理`">{{ tab.count }}</span>
    </RouterLink>
  </nav>
</template>

<style scoped>
.tab-count {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  min-width: 18px;
  height: 18px;
  margin-left: 6px;
  padding: 0 5px;
  box-sizing: border-box;
  border-radius: 999px;
  background: var(--warning-tint);
  color: var(--warning-ink);
  font-size: 11.5px;
  font-weight: 600;
}

/* 选中的标签（手机上是深浅反过来的胶囊）里，徽标跟着文字颜色走，不然琥珀色在浅底上看不清 */
.page-tab.is-active .tab-count {
  background: color-mix(in srgb, currentColor 16%, transparent);
  color: inherit;
}

/* 手机：横向滚动的胶囊，贴到页面左右边缘（页面左右内边距 16） */
.page-tabs {
  display: flex;
  gap: 8px;
  margin: 0 -16px;
  padding: 0 16px;
  overflow-x: auto;
  scrollbar-width: none;
}

.page-tabs::-webkit-scrollbar {
  display: none;
}

.page-tab {
  display: inline-flex;
  flex-shrink: 0;
  align-items: center;
  height: 34px;
  padding: 0 14px;
  border: 1px solid var(--border);
  border-radius: 999px;
  background: var(--bg-elevated);
  color: var(--text);
  font-size: 13.5px;
  white-space: nowrap;
  text-decoration: none;
}

.page-tab.is-active {
  border-color: var(--text-strong);
  background: var(--text-strong);
  color: var(--bg-elevated);
  font-weight: 600;
}

.page-tab:focus-visible {
  outline: 2px solid var(--brand-500);
  outline-offset: 2px;
}

/* 电脑、平板：下划线标签 */
@media (min-width: 768px) {
  .page-tabs {
    gap: 4px;
    margin: 0;
    padding: 0;
    overflow: visible;
    border-bottom: 1px solid var(--border);
  }

  .page-tab {
    height: 44px;
    margin-bottom: -1px;
    padding: 0 16px;
    border: 0;
    border-bottom: 2px solid transparent;
    border-radius: 0;
    background: transparent;
    color: var(--text);
    font-size: 14.5px;
  }

  .page-tab:hover {
    color: var(--text-strong);
  }

  .page-tab.is-active {
    border-bottom-color: var(--brand-500);
    background: transparent;
    color: var(--brand-600);
  }

  .page-tab:focus-visible {
    outline-offset: -2px;
  }
}
</style>
