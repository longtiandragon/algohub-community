<script setup lang="ts">
/**
 * 加载占位：和真实内容同形状的灰块，尺寸和圆角由用的地方给类名（如 class="h-3 w-2/5"）。
 * 读屏软件忽略它；外层写 aria-busy="true" 说明这一块在加载。
 */
</script>

<template>
  <span class="skeleton block rounded-md" aria-hidden="true" />
</template>

<style scoped>
.skeleton {
  background-color: var(--skeleton);
  background-image: linear-gradient(90deg, transparent 0, color-mix(in srgb, var(--bg-elevated) 55%, transparent) 50%, transparent 100%);
  background-size: 200% 100%;
  animation: skeleton-shine 1.4s ease-in-out infinite;
}

@keyframes skeleton-shine {
  from {
    background-position: 150% 0;
  }
  to {
    background-position: -50% 0;
  }
}

@media (prefers-reduced-motion: reduce) {
  .skeleton {
    animation: none;
    background-image: none;
  }
}
</style>
