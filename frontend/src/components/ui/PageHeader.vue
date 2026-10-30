<script setup lang="ts">
import { computed, useSlots } from 'vue'
import { useMobileTopBar } from '@/composables/useMobileTopBar'

/**
 * 页头：标题 + 一行说明 + 右侧操作。页面顶部先给结论，说明最多一行，口径放进 InfoTip。
 * meta 插槽放标题下的状态胶囊。
 * 手机上在外壳里时，标题已经在顶栏里：这里的 h1 只留给读屏软件；actions 送到顶栏右侧，
 * 顶栏放不下文字按钮时，另写 mobile-actions（通常是图标按钮）。没有顶栏的整页（如组件样板页）照常显示。
 */
const props = defineProps<{
  title: string
  description?: string
}>()

const slots = useSlots()
const { target: topbarActions, inTopbar } = useMobileTopBar()
const hasBody = computed(() => !inTopbar.value || !!props.description || !!slots.description || !!slots.meta)
</script>

<template>
  <header class="flex flex-col gap-3 md:(flex-row items-end justify-between gap-6)" :class="hasBody ? 'mb-5' : 'mb-0'">
    <div class="min-w-0">
      <h1 :class="inTopbar ? 'sr-only' : 'm-0 text-[22px] font-bold leading-[30px] text-ink lg:(text-[26px] leading-[34px])'">{{ title }}</h1>
      <p v-if="description || $slots.description" class="mb-0 text-[13.5px] leading-[22px] text-muted" :class="inTopbar ? 'mt-0' : 'mt-1.5'">
        <slot name="description">{{ description }}</slot>
      </p>
      <div v-if="$slots.meta" class="flex flex-wrap items-center gap-2" :class="inTopbar ? 'mt-2' : 'mt-3'">
        <slot name="meta" />
      </div>
    </div>
    <div v-if="$slots.actions && !inTopbar" class="flex flex-wrap items-center gap-2 md:shrink-0">
      <slot name="actions" />
    </div>
    <Teleport v-if="inTopbar && ($slots['mobile-actions'] || $slots.actions)" :to="topbarActions!">
      <slot name="mobile-actions"><slot name="actions" /></slot>
    </Teleport>
  </header>
</template>
