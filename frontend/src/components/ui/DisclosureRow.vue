<script setup lang="ts">
import { ref, useId, watch } from 'vue'
import type { RouteLocationRaw } from 'vue-router'

/**
 * 默认收起的一行：图标 + 标题 + 一句说明，点开在下面展开内容（第一次展开时才渲染，里面要读的数据那时才读）。
 * 传了 to 就不展开，改成去二级页面的链接（手机上「同步记录」这样用）。
 * 几行放在同一张卡片里时，行之间有分隔线。
 */
const props = defineProps<{
  title: string
  description?: string
  /** lucide 图标类名，如 i-lucide-upload */
  icon?: string
  to?: RouteLocationRaw
}>()

const open = defineModel<boolean>('open', { default: false })
/** expanded：展开的动画做完了（这时页面才变长，要滚过去的等它） */
const emit = defineEmits<{ expanded: [] }>()
const id = useId()
/** 展开过一次以后一直保留，收起只是隐藏（再展开不重新读数据、不丢表单里填的内容） */
const rendered = ref(open.value)
watch(open, (value) => {
  if (value) rendered.value = true
})
</script>

<template>
  <div class="disclosure">
    <RouterLink v-if="props.to" :to="props.to" class="disclosure-head">
      <span v-if="icon" :class="icon" class="text-lg text-muted" aria-hidden="true" />
      <span class="min-w-0 flex flex-1 flex-col gap-0.5">
        <span class="text-[14.5px] text-ink font-semibold leading-snug">{{ title }}</span>
        <span v-if="description" class="text-[12.5px] text-muted leading-normal">{{ description }}</span>
      </span>
      <span class="i-lucide-chevron-right text-lg text-muted" aria-hidden="true" />
    </RouterLink>
    <template v-else>
      <button type="button" class="disclosure-head" :aria-expanded="open" :aria-controls="id" @click="open = !open">
        <span v-if="icon" :class="icon" class="text-lg text-muted" aria-hidden="true" />
        <span class="min-w-0 flex flex-1 flex-col gap-0.5">
          <span class="text-[14.5px] text-ink font-semibold leading-snug">{{ title }}</span>
          <span v-if="description" class="text-[12.5px] text-muted leading-normal">{{ description }}</span>
        </span>
        <span class="i-lucide-chevron-down text-lg text-muted transition-transform duration-200" :class="{ 'rotate-180': open }" aria-hidden="true" />
      </button>
      <el-collapse-transition @after-enter="emit('expanded')">
        <div v-show="open" :id="id" role="region" :aria-label="title">
          <div v-if="rendered" class="disclosure-body">
            <slot />
          </div>
        </div>
      </el-collapse-transition>
    </template>
  </div>
</template>

<style scoped>
.disclosure + .disclosure {
  border-top: 1px solid var(--border-soft);
}

.disclosure-head {
  display: flex;
  align-items: center;
  gap: 12px;
  width: 100%;
  padding: 14px 16px;
  border: 0;
  background: transparent;
  color: inherit;
  font: inherit;
  text-align: left;
  text-decoration: none;
  cursor: pointer;
}

.disclosure-head:hover {
  background: var(--surface-hover);
}

.disclosure-head:focus-visible {
  outline: 2px solid var(--brand-500);
  outline-offset: -2px;
}

.disclosure-body {
  padding: 4px 16px 16px;
}

@media (min-width: 768px) {
  .disclosure-head {
    padding: 16px 20px;
  }

  .disclosure-body {
    padding: 4px 20px 20px;
  }
}
</style>
