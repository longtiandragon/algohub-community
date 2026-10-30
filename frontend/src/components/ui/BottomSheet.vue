<script setup lang="ts">
/**
 * 手机上的底部弹层（口径说明、筛选、对话框都用它）：顶部圆角 20、一根拖动条、标题 + 右侧操作 + 关闭，
 * 内容过长时在弹层里滚动，底部操作区留出 iPhone 的安全区。full 时占满整屏（如添加账号向导）。
 * Esc 和点遮罩都能关；打开期间焦点留在弹层里（组件库抽屉自带）。
 */
withDefaults(
  defineProps<{
    modelValue: boolean
    title?: string
    full?: boolean
  }>(),
  { full: false }
)

const emit = defineEmits<{ 'update:modelValue': [value: boolean]; opened: []; closed: [] }>()
</script>

<template>
  <el-drawer
    :model-value="modelValue"
    direction="btt"
    :size="full ? '100%' : 'auto'"
    :with-header="false"
    append-to-body
    class="bottom-sheet"
    :class="{ 'is-full': full }"
    @update:model-value="emit('update:modelValue', $event)"
    @opened="emit('opened')"
    @closed="emit('closed')"
  >
    <div class="flex max-h-full min-h-0 flex-col">
      <!-- 拖动条只给半屏的弹层；占满整屏时顶上留出状态栏 -->
      <span v-if="!full" class="mx-auto mt-2.5 block h-1 w-9 shrink-0 rounded-sm bg-line-strong" aria-hidden="true" />
      <header class="flex shrink-0 items-center gap-2 px-5 pb-2" :class="full ? 'pt-[calc(6px_+_env(safe-area-inset-top))]' : 'pt-3'">
        <h2 class="m-0 min-w-0 flex-1 text-[17px] font-semibold leading-6 text-ink">
          <slot name="title">{{ title }}</slot>
        </h2>
        <slot name="header-extra" />
        <button
          type="button"
          class="-mr-2 inline-flex h-11 w-11 items-center justify-center rounded-control border-0 bg-transparent text-xl text-muted"
          aria-label="关闭"
          @click="emit('update:modelValue', false)"
        >
          <span class="i-lucide-x" aria-hidden="true" />
        </button>
      </header>
      <div class="min-h-0 flex-1 overflow-y-auto px-5 pb-4">
        <slot />
      </div>
      <footer v-if="$slots.footer" class="shrink-0 border-t border-t-solid border-line px-5 pb-[calc(12px_+_env(safe-area-inset-bottom))] pt-3">
        <slot name="footer" />
      </footer>
      <div v-else class="h-[env(safe-area-inset-bottom)] shrink-0" />
    </div>
  </el-drawer>
</template>

<style>
.bottom-sheet.el-drawer {
  max-height: 90vh;
  border-radius: 20px 20px 0 0;
}

.bottom-sheet.el-drawer.is-full {
  max-height: none;
  border-radius: 0;
}

.bottom-sheet .el-drawer__body {
  display: flex;
  flex-direction: column;
  padding: 0;
  overflow: hidden;
}
</style>
