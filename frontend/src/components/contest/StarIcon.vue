<script setup lang="ts">
/** 收藏星：收藏了是实心琥珀色，没收藏是描边（颜色跟着文字）。只是图形，按钮自己写读屏文字。 */
withDefaults(defineProps<{ on: boolean; size?: number }>(), { size: 21 })
</script>

<template>
  <svg :width="size" :height="size" viewBox="0 0 24 24" aria-hidden="true" class="shrink-0">
    <path
      d="M12 2l3.09 6.26L22 9.27l-5 4.87 1.18 6.88L12 17.77l-6.18 3.25L7 14.14 2 9.27l6.91-1.01L12 2z"
      :class="on ? 'on' : 'off'"
    />
  </svg>
</template>

<style scoped>
path {
  stroke-linejoin: round;
}

.on {
  fill: #fbbf24;
  stroke: #d97706;
  stroke-width: 1.6;
}

.off {
  fill: none;
  stroke: currentColor;
  stroke-width: 1.8;
}
</style>
