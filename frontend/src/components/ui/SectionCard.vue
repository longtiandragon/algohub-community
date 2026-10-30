<script setup lang="ts">
/**
 * 卡片：标题 + 右侧链接或操作，下面是内容。靠 1px 边框分层，不加阴影；内边距电脑 20、手机 16。
 * flush：内容贴边（表格、列表自己管内边距）。
 */
withDefaults(
  defineProps<{
    title?: string
    description?: string
    flush?: boolean
  }>(),
  { flush: false }
)
</script>

<template>
  <section class="rounded-card border border-solid border-line bg-surface">
    <header
      v-if="title || $slots.title || $slots.actions"
      class="flex items-start justify-between gap-3 px-4 pt-4 md:(px-5 pt-5)"
      :class="{ 'pb-3': flush }"
    >
      <div class="min-w-0">
        <h2 class="m-0 text-base font-semibold leading-6 text-ink">
          <slot name="title">{{ title }}</slot>
        </h2>
        <p v-if="description" class="mb-0 mt-0.5 text-[12.5px] leading-5 text-muted">{{ description }}</p>
      </div>
      <div v-if="$slots.actions" class="flex shrink-0 items-center gap-2 text-[13.5px] leading-6">
        <slot name="actions" />
      </div>
    </header>
    <div :class="flush ? '' : 'p-4 md:p-5'">
      <slot />
    </div>
  </section>
</template>
