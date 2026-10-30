<script setup lang="ts">
import { computed, ref, watch } from 'vue'
import { platformBrand, platformLogoUrl } from '@/utils/platformBrand'

const props = withDefaults(
  defineProps<{
    platform: string | null | undefined
    /** 角标边长（像素）。列表里 18，卡片标题 22，绑定页 28。 */
    size?: number
    /** 无障碍用的平台名；不传就用平台标识 */
    title?: string
  }>(),
  { size: 18 }
)

const brand = computed(() => platformBrand(props.platform))
const logo = computed(() => platformLogoUrl(props.platform))

/** 图片加载失败（文件被删、路径改了）就退回自绘字标，不留一个破图。 */
const broken = ref(false)
watch(() => props.platform, () => (broken.value = false))

const showImage = computed(() => Boolean(logo.value) && !broken.value)

/** 两个字符时按边长收一点字号，免得撑出圆角外 */
const fontSize = computed(() => {
  const chars = Array.from(brand.value.mark).length
  return Math.round(props.size * (chars > 1 ? 0.42 : 0.56))
})

const radius = computed(() => `${Math.max(4, Math.round(props.size * 0.28))}px`)
</script>

<template>
  <img
    v-if="showImage"
    class="pf-logo pf-logo--img"
    :src="logo!"
    :style="{ width: `${size}px`, height: `${size}px`, borderRadius: radius }"
    :alt="title ?? platform ?? ''"
    :title="title ?? platform ?? ''"
    loading="lazy"
    decoding="async"
    @error="broken = true"
  />
  <span
    v-else
    class="pf-logo pf-logo--mark"
    :style="{
      width: `${size}px`,
      height: `${size}px`,
      borderRadius: radius,
      background: brand.color,
      color: brand.ink ?? '#fff',
      fontSize: `${fontSize}px`
    }"
    :title="title ?? platform ?? ''"
    :aria-label="title ?? platform ?? ''"
    role="img"
  >
    {{ brand.mark }}
  </span>
</template>

<style scoped>
.pf-logo {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  flex: 0 0 auto;
  line-height: 1;
  user-select: none;
}

.pf-logo--mark {
  font-weight: 700;
  letter-spacing: -0.02em;
  /* 深色底下也要和背景分得开 */
  box-shadow: inset 0 0 0 1px rgb(0 0 0 / 8%);
}

/*
 * 各站图标多为透明底、按浅色背景设计（洛谷、牛客的图形尤其淡），
 * 深色主题下直接贴会糊成一团，所以垫一层近白的底再放图。
 */
.pf-logo--img {
  object-fit: contain;
  background: #fff;
  padding: 1px;
  box-shadow: inset 0 0 0 1px rgb(0 0 0 / 10%);
}
</style>
