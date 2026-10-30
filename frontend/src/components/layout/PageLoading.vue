<script setup lang="ts">
import Skeleton from '@/components/ui/Skeleton.vue'
defineProps<{ title?: string }>()
</script>

<template>
  <div class="page" aria-busy="true" data-test="navigation-loading">
    <div role="status" class="flex items-center gap-3">
      <h1 class="m-0 text-2xl font-semibold text-ink">{{ title || '加载页面' }}</h1>
      <span class="text-sm text-muted">正在加载…</span>
    </div>
    <div class="grid grid-cols-2 gap-4 lg:grid-cols-4" aria-hidden="true">
      <Skeleton v-for="n in 4" :key="n" class="h-24" />
    </div>
    <div class="rounded-card border border-solid border-line bg-surface p-5" aria-hidden="true">
      <Skeleton class="mb-6 h-5 w-1/3" />
      <Skeleton v-for="n in 6" :key="n" class="mb-4 h-8 w-full" />
    </div>
  </div>
</template>
