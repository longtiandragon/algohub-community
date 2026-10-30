<script setup lang="ts">
import { useMobileTopBar } from '@/composables/useMobileTopBar'

/** 手机上把按钮送到顶栏右侧（如总览的「同步」）；电脑、平板上什么都不显示。 */
const { target, inTopbar } = useMobileTopBar()
</script>

<template>
  <Teleport v-if="inTopbar" :to="target!">
    <slot />
  </Teleport>
</template>
