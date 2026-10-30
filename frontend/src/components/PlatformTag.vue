<script setup lang="ts">
import PlatformLogo from '@/components/PlatformLogo.vue'

withDefaults(
  defineProps<{
    platform: string | null | undefined
    /** 显示的平台名；不传就只显示角标 */
    label?: string
    size?: number
  }>(),
  { size: 16 }
)
</script>

<template>
  <span class="pf-tag">
    <PlatformLogo :platform="platform" :size="size" :title="label ?? platform ?? ''" />
    <span v-if="label" class="pf-tag-name">{{ label }}</span>
  </span>
</template>

<style scoped>
.pf-tag {
  display: inline-flex;
  align-items: center;
  gap: 5px;
  padding: 2px 8px 2px 3px;
  border: 1px solid var(--border);
  border-radius: 999px;
  background: var(--bg-elevated);
  font-size: 12px;
  line-height: 1.5;
  color: var(--text);
  white-space: nowrap;
}

.pf-tag-name {
  font-weight: 560;
}
</style>
