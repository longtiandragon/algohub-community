<script setup lang="ts">
import { computed } from 'vue'

/**
 * 练习分布雷达图（SVG，不用图表库）：最外圈是自己练得最多的方向，只表示方向之间的比例，不是能力评分。
 * 四圈网格、轴线、品牌色的形状和圆点，方向名写在圈外。整张图随容器宽度缩放。
 */
const props = defineProps<{ rows: { name: string; value: number }[] }>()

const W = 420
const H = 330
const CX = W / 2
const CY = H / 2
const R = 118

const max = computed(() => Math.max(1, ...props.rows.map((r) => r.value)))
function point(i: number, r: number): [number, number] {
  const angle = -Math.PI / 2 + (i * 2 * Math.PI) / props.rows.length
  return [Math.round((CX + r * Math.cos(angle)) * 10) / 10, Math.round((CY + r * Math.sin(angle)) * 10) / 10]
}
const ring = (f: number) => props.rows.map((_, i) => point(i, R * f).join(',')).join(' ')
const rings = computed(() => [1, 0.75, 0.5, 0.25].map(ring))
const axes = computed(() => props.rows.map((_, i) => point(i, R)))
const shape = computed(() => props.rows.map((r, i) => point(i, (R * r.value) / max.value)))
const labels = computed(() =>
  props.rows.map((r, i) => {
    const [x, y] = point(i, R + 22)
    // 左半边的字右对齐、右半边左对齐，正上正下居中
    const anchor = Math.abs(x - CX) < 4 ? 'middle' : x < CX ? 'end' : 'start'
    // 圈外空间有限：超过 5 个字的方向名截短，悬停看全名
    const short = Array.from(r.name).length > 5 ? `${Array.from(r.name).slice(0, 4).join('')}…` : r.name
    return { x, y, anchor, name: r.name, short, value: r.value }
  })
)
const summary = computed(() => props.rows.map((r) => `${r.name} ${r.value} 题`).join('，'))
</script>

<template>
  <svg :viewBox="`0 0 ${W} ${H}`" class="radar block h-auto w-full" role="img" :aria-label="`各算法方向通过题数的比例：${summary}`">
    <polygon v-for="(points, i) in rings" :key="i" :points="points" :class="i ? 'grid' : 'grid outer'" />
    <line v-for="([x, y], i) in axes" :key="`a${i}`" :x1="CX" :y1="CY" :x2="x" :y2="y" class="axis" />
    <polygon :points="shape.map((p) => p.join(',')).join(' ')" class="shape" />
    <circle v-for="([x, y], i) in shape" :key="`d${i}`" :cx="x" :cy="y" r="3.5" class="dot" />
    <text v-for="l in labels" :key="l.name" :x="l.x" :y="l.y" :text-anchor="l.anchor" dominant-baseline="middle" class="label">
      <title>{{ l.name }} {{ l.value }} 题</title>{{ l.short }}
    </text>
  </svg>
</template>

<style scoped>
.radar {
  max-width: 440px;
  margin: 0 auto;
}

.grid {
  fill: none;
  stroke: var(--border);
  stroke-width: 1;
}

.grid.outer {
  fill: color-mix(in srgb, var(--bg-subtle) 60%, transparent);
  stroke: var(--border-strong);
}

.axis {
  stroke: var(--border);
  stroke-width: 1;
}

.shape {
  fill: color-mix(in srgb, var(--brand-500) 16%, transparent);
  stroke: var(--brand-500);
  stroke-width: 2;
  stroke-linejoin: round;
}

.dot {
  fill: var(--brand-500);
}

.label {
  fill: var(--text);
  font-size: 13.5px;
}
</style>
