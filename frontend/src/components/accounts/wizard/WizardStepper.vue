<script setup lang="ts">
/** 电脑上向导顶部的步骤条：做完的绿色打勾，当前的品牌色加一圈浅色，后面的空心；手机上改用顶栏的「1 / 5」和进度条 */
defineProps<{
  steps: string[]
  current: number
}>()
</script>

<template>
  <ol class="m-0 flex list-none items-start p-0" :aria-label="`添加账号共 ${steps.length} 步`">
    <li v-for="(label, index) in steps" :key="label" class="flex items-start" :class="{ 'flex-1': index < steps.length - 1 }">
      <span class="w-[76px] flex flex-col items-center gap-1.5" :aria-current="index === current ? 'step' : undefined">
        <span
          class="num size-7 flex items-center justify-center rounded-full text-[13px] font-semibold"
          :class="index < current ? 'bg-success text-white' : index === current ? 'step-now' : 'step-later'"
        >
          <span v-if="index < current" class="i-lucide-check text-sm" aria-hidden="true" />
          <template v-else>{{ index + 1 }}</template>
          <span v-if="index < current" class="sr-only">（已完成）</span>
        </span>
        <span
          class="whitespace-nowrap text-[12.5px]"
          :class="index < current ? 'text-success-ink' : index === current ? 'text-brand-600 font-semibold' : 'text-muted'"
        >{{ label }}</span>
      </span>
      <span
        v-if="index < steps.length - 1"
        class="mt-[13px] h-0.5 flex-1 rounded-sm"
        :class="index < current ? 'bg-success' : 'bg-line'"
        aria-hidden="true"
      />
    </li>
  </ol>
</template>

<style scoped>
.step-now {
  background: var(--brand-btn);
  color: #fff;
  box-shadow: 0 0 0 4px var(--brand-soft);
}

.step-later {
  border: 1.5px solid var(--border-strong);
  background: var(--bg-elevated);
  color: var(--text-muted);
}
</style>
