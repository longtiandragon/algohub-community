<script setup lang="ts">
import { onMounted, ref } from 'vue'
import { get } from '@/api/client'
import SectionCard from '@/components/ui/SectionCard.vue'
import Skeleton from '@/components/ui/Skeleton.vue'
import { useAuthStore } from '@/stores/auth'
import UserAvatar from '@/components/ui/UserAvatar.vue'
import { avatarTone } from '@/utils/avatar'
import { problemCode } from '@/utils/problemLabel'
import { relativeTime } from '@/utils/statistics'

/** 校内动态（最近几条同学的通过记录）：自己的那条写「我」、底色浅一点。单独加载、单独报错。 */
interface FeedItem {
  following?: boolean
  id: number
  userId: number
  displayName: string
  avatarUrl: string | null
  platform: string
  problemKey: string
  title: string | null
  acAt: string
  url: string | null
}

withDefaults(defineProps<{ limit?: number; title?: string }>(), { limit: 5, title: '公开动态' })

const auth = useAuthStore()
const items = ref<FeedItem[]>([])
const loading = ref(true)
const error = ref('')

async function load() {
  loading.value = true
  error.value = ''
  try {
    items.value = (await get<{ items: FeedItem[] }>('/activity/feed', { limit: 5, scope: 'PUBLIC' })).items
  } catch (e) {
    error.value = e instanceof Error ? e.message : '动态读取失败'
  } finally {
    loading.value = false
  }
}
onMounted(load)

const mine = (item: FeedItem) => item.userId === auth.me?.id
</script>

<template>
  <SectionCard :title="title">
    <template #actions><RouterLink to="/activity">更多动态 →</RouterLink></template>
    <div v-if="loading" class="flex flex-col gap-4" aria-busy="true">
      <div v-for="n in limit" :key="n" class="flex items-center gap-3">
        <Skeleton class="size-[30px] rounded-full" />
        <Skeleton class="h-3.5 flex-1" />
      </div>
    </div>
    <div v-else-if="error" class="flex flex-col items-start gap-2">
      <p class="m-0 text-[13px] text-danger-ink">{{ error }}</p>
      <el-button size="small" @click="load">重试</el-button>
    </div>
    <p v-else-if="!items.length" class="m-0 text-[13px] text-muted">同学们最近还没有新的通过记录。</p>
    <ul v-else class="m-0 list-none p-0">
      <li v-for="item in items.slice(0, limit)" :key="item.id" class="feed-row" :class="{ 'is-mine': mine(item) }">
        <UserAvatar
          :src="item.avatarUrl"
          :name="item.displayName"
          class="size-[30px] flex shrink-0 items-center justify-center rounded-full text-[12.5px] font-semibold"
          :class="mine(item) ? 'bg-brand-btn text-white' : avatarTone(item.userId)"
        />
        <span class="min-w-0 flex-1 truncate text-[13.5px] text-body">
          <strong class="text-ink font-semibold">{{ mine(item) ? '我' : item.displayName }}</strong><span v-if="item.following" class="mx-1 rounded bg-brand-soft px-1.5 py-0.5 text-[11px] text-brand-600">已关注</span> 通过了
          <span class="mono text-[12.5px] text-brand-600">{{ problemCode(item).code }}</span>
          {{ problemCode(item).name }}
        </span>
        <span class="shrink-0 whitespace-nowrap text-xs text-muted">{{ relativeTime(item.acAt) }}</span>
      </li>
    </ul>
  </SectionCard>
</template>

<style scoped>
.feed-row {
  display: flex;
  align-items: center;
  gap: 12px;
  padding: 10px 8px;
  border-top: 1px solid var(--border-soft);
}

.feed-row:first-child {
  border-top: 0;
}

.feed-row.is-mine {
  border-radius: 8px;
  background: color-mix(in srgb, var(--brand-soft) 60%, var(--bg-elevated));
}

.feed-row.is-mine + .feed-row {
  border-top-color: transparent;
}
</style>
