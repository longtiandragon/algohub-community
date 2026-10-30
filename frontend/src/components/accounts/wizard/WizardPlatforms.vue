<script setup lang="ts">
import { computed, ref } from 'vue'
import type { PlatformCapability } from '@/api/types'
import PlatformLogo from '@/components/PlatformLogo.vue'
import { bindingGuides } from '@/utils/bindingGuides'

/**
 * 向导第一步：选平台。「能验证归属」的是大按钮（进校内排行），其余是列表（只在自己的档案里）：
 * 能同步但不能验证的（个人关注，需要登录态的注明）。分组按 /platforms/capabilities 生成。
 */
const props = defineProps<{ capabilities: PlatformCapability[] }>()
const selected = defineModel<string>({ required: true })

const query = ref('')
const match = (c: PlatformCapability) => {
  const q = query.value.trim().toLowerCase()
  return !q || c.displayName.toLowerCase().includes(q) || c.platform.toLowerCase().includes(q)
}

const verifiable = computed(() => props.capabilities.filter((c) => c.syncSupported && c.ownershipProof && match(c)))
const personal = computed(() =>
  props.capabilities
    .filter((c) => c.syncSupported && !c.ownershipProof && match(c))
    .map((c) => ({ c, hint: c.requiresCredential ? '需要登录态' : '暂不支持验证归属' }))
)
</script>

<template>
  <div class="flex flex-col gap-3.5">
    <label class="relative block">
      <span class="sr-only">搜索平台</span>
      <span class="i-lucide-search absolute left-3.5 top-1/2 text-lg text-faint -translate-y-1/2" aria-hidden="true" />
      <input v-model="query" type="search" class="search-input" placeholder="搜索平台" />
    </label>

    <template v-if="verifiable.length">
      <span class="text-[12.5px] text-body font-semibold">能验证归属 · 会进入校内排行</span>
      <div class="grid grid-cols-2 gap-2.5 md:grid-cols-3">
        <button
          v-for="c in verifiable"
          :key="c.platform"
          type="button"
          class="platform-option"
          :class="{ on: selected === c.platform }"
          :aria-pressed="selected === c.platform"
          @click="selected = c.platform"
        >
          <PlatformLogo :platform="c.platform" :size="32" />
          <span class="min-w-0 flex flex-1 flex-col gap-px text-left">
            <span class="truncate text-[14.5px] text-ink font-semibold">{{ c.displayName }}</span>
            <span v-if="bindingGuides[c.platform]?.hint" class="truncate text-[11.5px] text-muted">{{ bindingGuides[c.platform].hint }}</span>
          </span>
          <span v-if="selected === c.platform" class="check corner" aria-hidden="true"><span class="i-lucide-check text-xs" /></span>
        </button>
      </div>
    </template>

    <template v-if="personal.length">
      <span class="mt-0.5 text-[12.5px] text-body font-semibold">只在自己的档案里显示</span>
      <div class="flex flex-col overflow-hidden border border-line rounded-xl border-solid">
        <button
          v-for="{ c, hint } in personal"
          :key="c.platform"
          type="button"
          class="personal-option"
          :class="{ on: selected === c.platform }"
          :aria-pressed="selected === c.platform"
          @click="selected = c.platform"
        >
          <PlatformLogo :platform="c.platform" :size="26" />
          <span class="min-w-0 flex-1 truncate text-left text-[14.5px] text-ink">{{ c.displayName }}</span>
          <span class="shrink-0 text-xs text-muted">{{ hint }}</span>
          <span v-if="selected === c.platform" class="check" aria-hidden="true"><span class="i-lucide-check text-xs" /></span>
        </button>
      </div>
    </template>

    <p v-if="!verifiable.length && !personal.length" class="m-0 py-6 text-center text-[13px] text-muted">没有找到「{{ query }}」</p>
  </div>
</template>

<style scoped>
.search-input {
  box-sizing: border-box;
  width: 100%;
  height: 44px;
  padding: 0 14px 0 40px;
  border: 1px solid var(--border);
  border-radius: 12px;
  background: var(--bg);
  color: var(--text-strong);
  font: inherit;
  font-size: 16px;
  outline: none;
}

.search-input:focus {
  border-color: var(--brand-500);
  box-shadow: 0 0 0 1px var(--brand-500);
}

@media (min-width: 768px) {
  .search-input {
    height: 40px;
    font-size: 14px;
  }
}

.platform-option {
  position: relative;
  display: flex;
  align-items: center;
  gap: 10px;
  min-width: 0;
  height: 60px;
  padding: 0 12px;
  border: 1px solid var(--border);
  border-radius: 12px;
  background: var(--bg-elevated);
  font: inherit;
  cursor: pointer;
}

.platform-option:hover {
  border-color: var(--border-strong);
}

.platform-option.on {
  padding: 0 11px;
  border: 2px solid var(--brand-400);
  background: var(--brand-soft);
}

.personal-option {
  display: flex;
  align-items: center;
  gap: 12px;
  height: 50px;
  padding: 0 14px;
  border: 0;
  border-top: 1px solid var(--border-soft);
  background: var(--bg-elevated);
  font: inherit;
  cursor: pointer;
}

.personal-option:first-child {
  border-top: 0;
}

.personal-option:hover {
  background: var(--surface-hover);
}

.personal-option.on {
  background: var(--brand-soft);
}

.platform-option:focus-visible,
.personal-option:focus-visible {
  outline: 2px solid var(--brand-500);
  outline-offset: -2px;
}

/* 大按钮上的勾叠在右上角，不占名字的位置 */
.check.corner {
  position: absolute;
  top: 6px;
  right: 6px;
  width: 18px;
  height: 18px;
}

.check {
  display: flex;
  flex-shrink: 0;
  align-items: center;
  justify-content: center;
  width: 20px;
  height: 20px;
  border-radius: 50%;
  background: var(--brand-btn);
  color: #fff;
}
</style>
