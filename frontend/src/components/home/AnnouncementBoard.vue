<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { get } from '@/api/client'
import AnnouncementContent from '@/components/home/AnnouncementContent.vue'
import EmptyState from '@/components/ui/EmptyState.vue'
import SectionCard from '@/components/ui/SectionCard.vue'
import Skeleton from '@/components/ui/Skeleton.vue'
import { dayTime } from '@/utils/statistics'
import { httpUrl } from '@/utils/url'

/**
 * 公告与资源：管理员在后台发布的文字公告（TEXT）和资源链接（LINK），同一份数据三种样子，每处单独加载：
 * - notice（首页问候下面）：有文字公告时一条公告条（最新一条 + 条数，点进公告页看全文），没有就不占地方；
 *   读不到时只留一行小字和「重试」。
 * - resources（首页最下面）：常用资源小卡片，电脑和平板带一行说明，手机两列只写标题；没有资源时整块不显示。
 *   资源是固定的几个链接，放在数据下面，不和每周的数据抢首屏。
 * - page（/announcements）：公告列表（正文保留换行）+ 资源网格（说明写全，附网址）。
 * 外链只放行 http(s)，都在新标签页打开；时间按北京时间。
 */
defineProps<{ variant: 'notice' | 'resources' | 'page' }>()

const notices = ref<{ version?: string | null; id: number; title: string; content: string; updatedAt: string; publishedAt?: string; items?: string[]; kind: string; url: string | null }[]>([])
const loading = ref(true)
const error = ref(false)
const texts = computed(() => notices.value.filter(n => n.kind !== 'LINK'))
const links = computed(() => notices.value.filter(n => n.kind === 'LINK' && httpUrl(n.url ?? '')))
async function load() {
  error.value = false
  loading.value = true
  try { notices.value = await get('/announcements') } catch { error.value = true } finally { loading.value = false }
}
onMounted(load)

/** 接口按首次发布时间倒序，第一条就是最新的 */
const latest = computed(() => texts.value[0] ?? null)

/** 资源没有图标字段：按标题里的词挑一个，认不出的用链接图标 */
const RESOURCE_ICONS: [RegExp, string][] = [
  [/群|交流|社区|论坛/, 'i-lucide-messages-square'],
  [/wiki|知识|教程|文档|题解|模板/i, 'i-lucide-book-open'],
  [/获奖|奖项/, 'i-lucide-award'],
  [/竞赛|比赛|赛事|[XIC]CPC|rating/i, 'i-lucide-trophy']
]
const iconOf = (title: string) => RESOURCE_ICONS.find(([pattern]) => pattern.test(title))?.[1] ?? 'i-lucide-link'

/** 资源卡片上写网址（去掉 www.），点之前知道会去哪个站 */
function hostOf(url: string | null) {
  try {
    return new URL(httpUrl(url) ?? '').hostname.replace(/^www\./, '')
  } catch {
    return ''
  }
}
</script>

<template>
  <!-- 首页问候下面：最新一条公告 -->
  <template v-if="variant === 'notice'">
    <p v-if="error" class="m-0 text-[13px] text-muted" data-test="home-notice-error">
      公告暂时读不到 · <button type="button" class="text-button" @click="load">重试</button>
    </p>
    <RouterLink v-else-if="latest" to="/announcements" class="notice-bar" data-test="home-notice">
      <span class="flex size-8 shrink-0 items-center justify-center rounded-full bg-brand-btn text-white md:size-9" aria-hidden="true">
        <span class="i-lucide-megaphone text-[17px]" />
      </span>
      <span class="min-w-0 flex flex-1 flex-col gap-0.5 md:(flex-row items-baseline gap-3)">
        <span class="sr-only">公告：</span><span v-if="latest.version" class="text-xs text-brand-600">{{ latest.version }}</span>
        <span class="truncate text-sm text-ink font-semibold leading-5 md:text-[14.5px]">{{ latest.title }}</span>
        <span class="num shrink-0 text-xs text-muted leading-[18px] md:text-[13px]">
          {{ dayTime(latest.publishedAt ?? latest.updatedAt) }}<template v-if="texts.length > 1"> · 共 {{ texts.length }} 条公告</template>
        </span>
      </span>
      <span class="flex shrink-0 items-center gap-0.5 text-[13px] text-brand-600 font-semibold">
        <span class="hidden md:inline">{{ texts.length > 1 ? '查看全部' : '查看' }}</span>
        <span class="i-lucide-chevron-right text-base" aria-hidden="true" />
      </span>
    </RouterLink>
  </template>

  <!-- 首页最下面：常用资源 -->
  <SectionCard v-else-if="variant === 'resources' && (loading || error || links.length)" title="常用资源" data-test="home-resources">
    <template #actions><RouterLink to="/announcements">全部 →</RouterLink></template>
    <div v-if="error" class="flex flex-col items-start gap-2">
      <p class="m-0 text-[13px] text-danger-ink">常用资源暂时读不到</p>
      <el-button size="small" @click="load">重试</el-button>
    </div>
    <ul v-else class="m-0 grid grid-cols-2 list-none gap-2 p-0 md:gap-3 xl:grid-cols-4" :aria-busy="loading ? 'true' : undefined">
      <template v-if="loading">
        <li v-for="n in 4" :key="n" class="resource-tile">
          <Skeleton class="size-8 shrink-0 rounded-control md:size-9" />
          <span class="flex flex-1 flex-col gap-1.5"><Skeleton class="h-3.5 w-4/5" /><Skeleton class="hidden h-3 w-3/5 md:block" /></span>
        </li>
      </template>
      <template v-else>
        <li v-for="link in links" :key="link.id">
          <a :href="httpUrl(link.url)" target="_blank" rel="noopener noreferrer" class="resource-tile is-link" :title="link.content">
            <span class="flex size-8 shrink-0 items-center justify-center rounded-control bg-brand-soft text-brand md:size-9" aria-hidden="true">
              <span :class="iconOf(link.title)" class="text-[17px] md:text-lg" />
            </span>
            <span class="min-w-0 flex flex-1 flex-col gap-0.5">
              <span class="line-clamp-2 text-[13px] text-ink font-semibold leading-[18px] md:(line-clamp-1 text-sm leading-5)">{{ link.title }}</span>
              <span class="hidden text-[12.5px] text-muted leading-[18px] md:(block truncate)">{{ link.content }}</span>
            </span>
            <span class="hidden shrink-0 md:flex" aria-hidden="true"><span class="i-lucide-arrow-up-right text-[15px] text-faint" /></span>
            <span class="sr-only">（在新标签页打开）</span>
          </a>
        </li>
      </template>
    </ul>
  </SectionCard>

  <!-- 公告页 -->
  <div v-else-if="variant === 'page'" class="flex flex-col gap-4 md:gap-6" data-test="announcements">
    <SectionCard v-if="loading" title="公告" aria-busy="true">
      <div class="flex flex-col gap-3">
        <Skeleton class="h-4 w-2/5" />
        <Skeleton class="h-3.5 w-full" />
        <Skeleton class="h-3.5 w-4/5" />
      </div>
    </SectionCard>
    <EmptyState v-else-if="error" icon="i-lucide-cloud-off" title="公告与资源暂时读不到" description="可能是网络不稳，稍后再试一次。" action="重试" @action="load" />
    <EmptyState v-else-if="!texts.length && !links.length" icon="i-lucide-megaphone" title="暂时没有公告和资源" description="平台管理员发布的通知会出现在这里，首页也会显示最新一条。" />
    <template v-else>
      <SectionCard title="公告" :description="texts.length > 1 ? `共 ${texts.length} 条，新的在上面` : undefined" :flush="texts.length > 0">
        <ol v-if="texts.length" class="m-0 list-none p-0">
          <li v-for="notice in texts" :key="notice.id" class="notice-item">
            <article>
              <div class="flex flex-col gap-1 md:(flex-row items-baseline justify-between gap-4)">
                <el-tag v-if="notice.version" size="small">{{ notice.version }}</el-tag>
                <h3 class="m-0 text-[15px] text-ink font-semibold leading-6 [overflow-wrap:anywhere]">{{ notice.title }}</h3>
                <time :datetime="notice.publishedAt ?? notice.updatedAt" class="num shrink-0 text-[12.5px] text-muted">{{ dayTime(notice.publishedAt ?? notice.updatedAt) }}</time>
              </div>
              <div class="mt-4"><AnnouncementContent :kind="notice.kind" :content="notice.content" :items="notice.items" /></div>
            </article>
          </li>
        </ol>
        <!-- 只有资源、没有公告：一行说明就够，不用大块空状态把资源挤下去 -->
        <p v-else class="m-0 flex items-center gap-2 text-[13.5px] text-muted leading-[22px]">
          <span class="i-lucide-megaphone shrink-0 text-base text-faint" aria-hidden="true" />暂时没有公告，平台管理员发布的通知会出现在这里。
        </p>
      </SectionCard>

      <SectionCard v-if="links.length" title="常用资源" description="点开会在新标签页打开">
        <ul class="m-0 grid grid-cols-1 list-none gap-3 p-0 md:grid-cols-2">
          <li v-for="link in links" :key="link.id">
            <a :href="httpUrl(link.url)" target="_blank" rel="noopener noreferrer" class="resource-card">
              <span class="flex size-10 shrink-0 items-center justify-center rounded-control bg-brand-soft text-brand" aria-hidden="true">
                <span :class="iconOf(link.title)" class="text-xl" />
              </span>
              <span class="min-w-0 flex flex-1 flex-col gap-1">
                <span class="text-sm text-ink font-semibold leading-5 [overflow-wrap:anywhere]">
                  {{ link.title }}<span class="i-lucide-arrow-up-right ml-1 text-[15px] text-faint" aria-hidden="true" />
                </span>
                <span class="whitespace-pre-wrap text-[13px] text-muted leading-5 [overflow-wrap:anywhere]">{{ link.content }}</span>
                <span class="mono truncate text-xs text-muted">{{ hostOf(link.url) }}</span>
              </span>
              <span class="sr-only">（在新标签页打开）</span>
            </a>
          </li>
        </ul>
      </SectionCard>
    </template>
  </div>
</template>

<style scoped>
.notice-bar {
  display: flex;
  align-items: center;
  gap: 12px;
  padding: 10px 12px;
  border: 1px solid color-mix(in srgb, var(--brand-500) 24%, var(--border));
  border-radius: var(--radius);
  background: color-mix(in srgb, var(--brand-soft) 70%, var(--bg-elevated));
  color: inherit;
  text-decoration: none;
  transition: border-color 0.15s ease;
}

.notice-bar:hover {
  border-color: var(--brand-400);
  text-decoration: none;
}

.resource-tile {
  display: flex;
  align-items: center;
  gap: 10px;
  height: 100%;
  box-sizing: border-box;
  padding: 10px 12px;
  border: 1px solid var(--border);
  border-radius: var(--radius);
  background: var(--bg-elevated);
  color: inherit;
  text-decoration: none;
}

.resource-tile.is-link {
  transition: border-color 0.15s ease, background-color 0.15s ease;
}

.resource-tile.is-link:hover,
.resource-card:hover {
  border-color: var(--brand-400);
  background: color-mix(in srgb, var(--brand-soft) 40%, var(--bg-elevated));
  text-decoration: none;
}

.resource-card {
  display: flex;
  gap: 12px;
  height: 100%;
  box-sizing: border-box;
  padding: 14px 16px;
  border: 1px solid var(--border);
  border-radius: 12px;
  color: inherit;
  text-decoration: none;
  transition: border-color 0.15s ease, background-color 0.15s ease;
}

.notice-item {
  padding: 14px 16px;
}

.notice-item + .notice-item {
  border-top: 1px solid var(--border-soft);
}

.text-button {
  padding: 0;
  border: 0;
  background: none;
  color: var(--brand-600);
  font: inherit;
  cursor: pointer;
}

.notice-bar:focus-visible,
.resource-tile:focus-visible,
.resource-card:focus-visible,
.text-button:focus-visible {
  outline: 2px solid var(--brand-500);
  outline-offset: 2px;
}

@media (min-width: 768px) {
  .notice-bar {
    padding: 12px 16px;
  }

  .resource-tile {
    gap: 12px;
    padding: 12px 14px;
  }

  .notice-item {
    padding: 16px 20px;
  }
}

@media (prefers-reduced-motion: reduce) {
  .notice-bar,
  .resource-tile.is-link,
  .resource-card {
    transition: none;
  }
}
</style>
