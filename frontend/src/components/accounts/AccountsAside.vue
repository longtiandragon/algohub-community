<script setup lang="ts">
import { computed } from 'vue'
import type { PlatformCapability } from '@/api/types'
import PlatformLogo from '@/components/PlatformLogo.vue'
import StatusBadge from '@/components/ui/StatusBadge.vue'
import { bindingGuides } from '@/utils/bindingGuides'

/**
 * 平台账号页右栏（窄屏排在最后）：三种绑定方式的说明、支持的平台。
 * 平台分组按 /platforms/capabilities 生成：有归属验证方式的「可验证」，能同步但不能验证的「个人关注」。
 * 点平台直接去添加。
 */
const props = defineProps<{
  capabilities: PlatformCapability[]
  admin?: boolean
}>()

const emit = defineEmits<{ add: [platform: string] }>()

const watchOnly = computed(() => props.capabilities.filter((c) => c.syncSupported && !c.ownershipProof).map((c) => c.displayName))

const platforms = computed(() =>
  props.capabilities
    .filter((c) => c.syncSupported)
    .map((c) => {
      if (c.ownershipProof) {
        return { c, tone: 'success' as const, tag: bindingGuides[c.platform]?.nickname ? '可验证 · 按昵称找' : '可验证' }
      }
      return { c, tone: 'neutral' as const, tag: c.requiresCredential ? '个人关注 · 需登录态' : '个人关注' }
    })
)
</script>

<template>
  <div class="grid grid-cols-1 items-start gap-4 md:grid-cols-2 xl:grid-cols-1" data-test="accounts-aside">
    <section class="flex flex-col gap-3.5 border border-line rounded-card border-solid bg-surface px-4 py-4 md:(px-5 py-[18px])">
      <h2 class="m-0 text-[15px] text-ink font-semibold leading-6">三种绑定方式</h2>
      <div class="flex flex-col items-start gap-1.5">
        <StatusBadge variant="label" tone="success">正式绑定</StatusBadge>
        <p class="m-0 text-[13px] text-body leading-relaxed">在平台个人资料里贴过验证串，证明是你本人；进入校内排行和动态。</p>
      </div>
      <div class="flex flex-col items-start gap-1.5">
        <StatusBadge variant="label" tone="warning">待认领</StatusBadge>
        <p class="m-0 text-[13px] text-body leading-relaxed">还没验证；记录只在你自己的档案里，验证后原地升级。</p>
      </div>
      <div class="flex flex-col items-start gap-1.5">
        <StatusBadge variant="label" tone="neutral">个人关注</StatusBadge>
        <p class="m-0 text-[13px] text-body leading-relaxed">
          平台暂不支持验证<template v-if="watchOnly.length">（{{ watchOnly.join('、') }}）</template>；只在自己的档案里。
        </p>
      </div>
      <p class="m-0 border-t border-t-solid border-line-soft pt-3 text-[12.5px] text-muted leading-relaxed">
        保存登录态只用来同步，不能证明账号是你的。<template v-if="admin">管理员权限也不会合并其他本站账号的刷题档案。</template>
      </p>
    </section>

    <section class="flex flex-col border border-line rounded-card border-solid bg-surface px-4 py-4 md:(px-5 py-[18px])">
      <h2 class="m-0 mb-2 text-[15px] text-ink font-semibold leading-6">支持的平台</h2>
      <ul class="m-0 list-none p-0">
        <li v-for="{ c, tone, tag } in platforms" :key="c.platform">
          <button
            type="button"
            class="platform-row"
            :aria-label="`${c.displayName}，${tag}，添加账号`"
            @click="emit('add', c.platform)"
          >
            <PlatformLogo :platform="c.platform" :size="24" />
            <span class="min-w-0 flex-1 truncate text-left text-[13.5px] text-ink">{{ c.displayName }}</span>
            <StatusBadge variant="label" :tone="tone">{{ tag }}</StatusBadge>
          </button>
        </li>
      </ul>
    </section>
  </div>
</template>

<style scoped>
.platform-row {
  display: flex;
  align-items: center;
  gap: 10px;
  width: 100%;
  min-height: 40px;
  margin: 0 -8px;
  padding: 0 8px;
  box-sizing: content-box;
  border: 0;
  border-radius: 8px;
  background: transparent;
  font: inherit;
}

.platform-row {
  cursor: pointer;
}

.platform-row:hover {
  background: var(--surface-hover);
}

.platform-row:focus-visible {
  outline: 2px solid var(--brand-500);
  outline-offset: -2px;
}
</style>
