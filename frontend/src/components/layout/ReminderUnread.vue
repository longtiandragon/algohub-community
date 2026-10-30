<script setup lang="ts">
import { onMounted, onUnmounted, ref, watch } from 'vue'
import { useRoute } from 'vue-router'
import { get } from '@/api/client'
import { useAuthStore } from '@/stores/auth'

/**
 * 页面顶部的未读提示：系统通知或好友消息未读时出现，点了去「消息」。
 * 每分钟查一次未读数；切回这个标签页、在消息页读过消息（study-inbox-changed）时马上再查。
 * 登录邮箱还没验证时不查（接口会被拦下）；消息页自己列着未读，那里不显示。
 */
const auth = useAuthStore()
const route = useRoute()
const count = ref(0)
let timer: ReturnType<typeof setInterval> | undefined
/** 换了账号或卸载后，还没回来的旧请求不再写入 */
let generation = 0

async function refresh() {
  const version = ++generation
  const id = auth.me?.id
  if (!id || !auth.me?.emailVerified || document.hidden) {
    count.value = 0
    return
  }
  try {
    const data = await get<{ unread: number }>('/reminders/unread')
    if (version === generation && auth.me?.id === id) count.value = data.unread
  } catch {
    // 下次轮询再试，不打断页面
  }
}

watch(() => auth.me?.id, refresh)
onMounted(() => {
  void refresh()
  timer = setInterval(refresh, 60000)
  window.addEventListener('study-inbox-changed', refresh)
  document.addEventListener('visibilitychange', refresh)
})
onUnmounted(() => {
  generation++
  clearInterval(timer)
  window.removeEventListener('study-inbox-changed', refresh)
  document.removeEventListener('visibilitychange', refresh)
})
</script>

<template>
  <RouterLink v-if="count && route.name !== 'messages'" to="/messages" class="unread-bar" aria-live="polite" data-test="reminder-unread">
    <span class="size-8 flex shrink-0 items-center justify-center rounded-full bg-brand-btn text-white" aria-hidden="true">
      <span class="i-lucide-bell text-base" />
    </span>
    <span class="min-w-0 flex flex-1 flex-col">
      <span class="text-sm text-ink font-semibold leading-5"><span class="num">{{ count }}</span> 条消息还没看</span>
      <span class="truncate text-[12.5px] text-muted leading-5">系统提醒、学习周报和好友消息</span>
    </span>
    <span class="shrink-0 text-[13px] text-brand-600 font-semibold">查看 →</span>
  </RouterLink>
</template>

<style scoped>
.unread-bar {
  display: flex;
  align-items: center;
  gap: 12px;
  margin-bottom: 16px;
  padding: 10px 14px;
  border: 1px solid color-mix(in srgb, var(--brand-500) 22%, transparent);
  border-radius: 12px;
  background: var(--brand-soft);
  color: inherit;
  text-decoration: none;
  transition: border-color 0.15s ease;
}

.unread-bar:hover {
  border-color: var(--brand-400);
  text-decoration: none;
}

.unread-bar:focus-visible {
  outline: 2px solid var(--brand-500);
  outline-offset: 2px;
}

@media (prefers-reduced-motion: reduce) {
  .unread-bar {
    transition: none;
  }
}
</style>
