<script setup lang="ts">
/**
 * 空状态就是引导：图标 + 一句话（+ 一行补充）+ 一个能点的按钮，说清下一步做什么，不写「这不代表……」。
 * 给 to 时按钮是链接；不给 to 时点击发出 action 事件；要自定义按钮用 action 插槽。
 */
withDefaults(
  defineProps<{
    /** lucide 图标类名，如 i-lucide-link */
    icon?: string
    title: string
    description?: string
    /** 按钮文字 */
    action?: string
    to?: string
    bordered?: boolean
  }>(),
  { icon: 'i-lucide-inbox', bordered: true }
)

defineEmits<{ action: [] }>()
</script>

<template>
  <div
    class="flex flex-col items-center gap-2.5 rounded-card px-5 py-[26px] text-center"
    :class="{ 'border border-dashed border-line-strong': bordered }"
  >
    <span class="flex h-12 w-12 items-center justify-center rounded-full bg-brand-soft text-brand" aria-hidden="true">
      <span :class="icon" class="text-[22px]" />
    </span>
    <div class="text-[15px] font-semibold leading-[22px] text-ink">{{ title }}</div>
    <div v-if="description" class="text-[13px] leading-5 text-muted">{{ description }}</div>
    <slot name="action">
      <RouterLink v-if="action && to" v-slot="{ href, navigate }" :to="to" custom>
        <el-button type="primary" tag="a" :href="href" class="mt-1" @click="navigate">{{ action }}</el-button>
      </RouterLink>
      <el-button v-else-if="action" type="primary" class="mt-1" @click="$emit('action')">{{ action }}</el-button>
    </slot>
  </div>
</template>
