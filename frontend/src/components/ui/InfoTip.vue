<script setup lang="ts">
import { ref } from 'vue'
import type { PopoverInstance } from 'element-plus'
import { useBreakpoint } from '@/composables/useBreakpoint'
import BottomSheet from './BottomSheet.vue'

/**
 * ⓘ 口径说明：默认收起，页面上最多留一行说明，其余写在这里（默认插槽）。
 * 电脑上悬停或点击弹出，键盘按回车 / 空格打开、Esc 关闭；手机上点击后从底部弹出。
 * 按钮三种样子：默认是跟在文字后面的 20px 小图标（手机上点击区域向外扩到 44px）；
 * text 时是「ⓘ 统计口径」这样带字的链接按钮；topbar 时是手机顶栏里 40px 的图标按钮（本身够大，不往外扩，免得伸出屏幕）。
 */
const props = defineProps<{
  /** 弹出层的标题，例如「新增通过怎么算？」 */
  title: string
  /** 按钮的读屏文字，默认「口径说明：标题」 */
  label?: string
  /** 按钮上显示的字，如「统计口径」 */
  text?: string
  topbar?: boolean
}>()

const { isMobile } = useBreakpoint()
const sheetOpen = ref(false)
// 用键盘打开后焦点还在按钮上，弹出层自己收不到 Esc，由按钮关掉
const popover = ref<PopoverInstance>()
const ariaLabel = props.label ?? `口径说明：${props.title}`
</script>

<template>
  <el-popover
    v-if="!isMobile"
    ref="popover"
    :trigger="['hover', 'click']"
    placement="bottom-start"
    :width="320"
    :show-arrow="false"
    popper-class="info-tip-popper"
  >
    <template #reference>
      <button
        type="button"
        class="info-tip-trigger"
        :class="{ 'is-text': text, 'is-topbar': topbar }"
        :aria-label="ariaLabel"
        @keydown.esc="popover?.hide()"
      >
        <span class="i-lucide-info" aria-hidden="true" />
        <template v-if="text">{{ text }}</template>
      </button>
    </template>
    <div class="flex flex-col gap-1.5">
      <div class="text-sm font-semibold leading-[22px] text-ink">{{ title }}</div>
      <div class="text-[13px] leading-[1.7] text-body">
        <slot />
      </div>
    </div>
  </el-popover>
  <template v-else>
    <button
      type="button"
      class="info-tip-trigger"
      :class="{ 'is-text': text, 'is-topbar': topbar }"
      :aria-label="ariaLabel"
      @click="sheetOpen = true"
    >
      <span class="i-lucide-info" aria-hidden="true" />
      <template v-if="text && !topbar">{{ text }}</template>
    </button>
    <BottomSheet v-model="sheetOpen" :title="title">
      <div class="text-[15px] leading-[1.7] text-body">
        <slot />
      </div>
    </BottomSheet>
  </template>
</template>

<style scoped>
.info-tip-trigger {
  position: relative;
  display: inline-flex;
  align-items: center;
  justify-content: center;
  width: 20px;
  height: 20px;
  padding: 0;
  border: 0;
  border-radius: 50%;
  background: transparent;
  color: var(--text-faint);
  font-size: 15px;
  cursor: help;
}

.info-tip-trigger:hover {
  color: var(--text-muted);
}

.info-tip-trigger:focus-visible {
  outline: 2px solid var(--brand-500);
  outline-offset: 1px;
}

/* 按钮只有 20px，手机上点击区域向外扩到 44px */
.info-tip-trigger::after {
  content: '';
  position: absolute;
  inset: -12px;
}

.info-tip-trigger.is-text {
  gap: 6px;
  width: auto;
  height: 36px;
  padding: 0 12px;
  border-radius: 9px;
  color: var(--brand-600);
  font: inherit;
  font-size: 13.5px;
  cursor: pointer;
}

.info-tip-trigger.is-text:hover {
  background: var(--brand-soft);
}

.info-tip-trigger.is-topbar {
  width: 40px;
  height: 40px;
  border-radius: 10px;
  color: var(--text-muted);
  font-size: 20px;
  cursor: pointer;
}

.info-tip-trigger.is-text::after,
.info-tip-trigger.is-topbar::after {
  content: none;
}
</style>

<style>
.info-tip-popper.el-popover.el-popper {
  padding: 14px 16px;
  border-radius: 12px;
  border-color: var(--border);
  box-shadow: var(--shadow);
}

</style>
