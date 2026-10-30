<script setup lang="ts">
import { ref, watch } from 'vue'
import { displayAvatarUrl, initialOf } from '@/utils/avatar'

/**
 * 用户头像：有头像地址（本人从已绑定的 OJ 里选的）就显示图片，没有或图片加载失败就显示名字的第一个字（字标）。
 * 图片直接从 OJ 的图片域名加载：不带来源页、懒加载。尺寸、圆角、底色都由外面传进来的 class 决定，
 * 两种情况用同一套 class，字标在加载失败时原地顶上。
 */
const props = defineProps<{
  src?: string | null
  name?: string | null
}>()

const failed = ref(false)
watch(
  () => props.src,
  () => {
    failed.value = false
  }
)
</script>

<template>
  <img
    v-if="src && !failed"
    :src="displayAvatarUrl(src)"
    alt=""
    referrerpolicy="no-referrer"
    loading="lazy"
    decoding="async"
    class="object-cover"
    @error="failed = true"
  >
  <span v-else aria-hidden="true"><slot>{{ initialOf(name) }}</slot></span>
</template>
