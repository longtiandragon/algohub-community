<script setup lang="ts">
import { computed } from 'vue'

/** 本周目标的圆环：品牌色走到完成的比例，达成后换成绿色。只是图形，数字由旁边的文字写。 */
const props = withDefaults(defineProps<{ solved: number; target: number | null; size?: number; stroke?: number }>(), { size: 30, stroke: 5 })

const radius = computed(() => (props.size - props.stroke) / 2)
const length = computed(() => 2 * Math.PI * radius.value)
const ratio = computed(() => (props.target ? Math.min(1, props.solved / props.target) : 0))
</script>

<template>
  <svg :width="size" :height="size" :viewBox="`0 0 ${size} ${size}`" class="shrink-0" aria-hidden="true">
    <circle :cx="size / 2" :cy="size / 2" :r="radius" class="track" :stroke-width="stroke" />
    <circle
      v-if="ratio > 0"
      :cx="size / 2"
      :cy="size / 2"
      :r="radius"
      class="fill"
      :class="{ done: ratio >= 1 }"
      :stroke-width="stroke"
      :stroke-dasharray="`${length * ratio} ${length}`"
      :transform="`rotate(-90 ${size / 2} ${size / 2})`"
    />
  </svg>
</template>

<style scoped>
circle {
  fill: none;
}

.track {
  stroke: color-mix(in srgb, var(--brand-500) 22%, transparent);
}

.fill {
  stroke: var(--brand-500);
  stroke-linecap: round;
}

.fill.done {
  stroke: var(--success);
}
</style>
