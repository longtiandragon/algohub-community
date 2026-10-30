<script setup lang="ts">
import { useBreakpoint } from '@/composables/useBreakpoint'
import BottomSheet from './BottomSheet.vue'

/**
 * 对话框：电脑上是居中的对话框，手机上是底部弹层（fullscreenOnMobile 时占满整屏，如添加账号向导）。
 * footer 里按电脑的习惯写「取消、确定」；手机上按钮叠成一列、占满一行、48px 高，写在最后的主按钮排在最上面。
 */
withDefaults(
  defineProps<{
    modelValue: boolean
    title: string
    /** 电脑上的宽度 */
    width?: string | number
    fullscreenOnMobile?: boolean
  }>(),
  { width: 560, fullscreenOnMobile: false }
)

const emit = defineEmits<{ 'update:modelValue': [value: boolean]; opened: []; closed: [] }>()
const { isMobile } = useBreakpoint()
</script>

<template>
  <BottomSheet
    v-if="isMobile"
    :model-value="modelValue"
    :title="title"
    :full="fullscreenOnMobile"
    @update:model-value="emit('update:modelValue', $event)"
    @opened="emit('opened')"
    @closed="emit('closed')"
  >
    <template v-if="$slots['header-extra']" #header-extra>
      <slot name="header-extra" />
    </template>
    <slot />
    <template v-if="$slots.footer" #footer>
      <div class="dialog-sheet-actions">
        <slot name="footer" />
      </div>
    </template>
  </BottomSheet>
  <el-dialog
    v-else
    :model-value="modelValue"
    :title="title"
    :width="width"
    append-to-body
    class="responsive-dialog"
    @update:model-value="emit('update:modelValue', $event)"
    @opened="emit('opened')"
    @closed="emit('closed')"
  >
    <slot />
    <template v-if="$slots.footer" #footer>
      <slot name="footer" />
    </template>
  </el-dialog>
</template>

<style>
.responsive-dialog.el-dialog {
  border-radius: var(--radius);
}

.responsive-dialog .el-dialog__title {
  color: var(--text-strong);
  font-size: 18px;
  font-weight: 650;
}

.dialog-sheet-actions {
  display: flex;
  flex-direction: column-reverse;
  gap: 8px;
}

.dialog-sheet-actions .el-button {
  width: 100%;
  height: 48px;
  margin: 0;
  border-radius: 12px;
  font-size: 16px;
}
</style>
