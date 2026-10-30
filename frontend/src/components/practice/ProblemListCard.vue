<script setup lang="ts">
import { computed } from 'vue'
import PlatformLogo from '@/components/PlatformLogo.vue'
import StarIcon from '@/components/contest/StarIcon.vue'
import { platformName } from '@/utils/platformBrand'
import type { ProblemList } from './types'

/**
 * 题单卡片：平台、作者、一个标签（推荐 / 自动生成 / 分享范围）→ 标题 → 两行说明 → 进度条 → 收藏数和「继续练」。
 * 进度按已同步的通过记录算；题单空着时不画进度条，写「还没有题目」。
 */
const props = defineProps<{
  list: ProblemList
  mine: boolean
  canEdit: boolean
  showCode: boolean
  favoritePending: boolean
  management?: boolean
}>()
defineEmits<{ favorite: []; remove: [] }>()

const total = computed(() => props.list.itemCount ?? 0)
const done = computed(() => Math.min(props.list.solvedCount ?? 0, total.value))
const percent = computed(() => (total.value > 0 ? Math.round((done.value / total.value) * 100) : 0))
const finished = computed(() => total.value > 0 && done.value >= total.value)
const detail = computed(() => `${props.management ? '/admin/problem-lists' : '/problem-lists'}/${props.list.id}`)

const cta = computed(() => {
  if (props.management) return '管理'
  if (total.value === 0) return '查看'
  if (finished.value) return '回顾'
  return done.value === 0 ? '开始练' : '继续练'
})

const author = computed(() => {
  if (props.mine) return '我'
  return props.list.ownerId === null ? '管理员整理' : (props.list.ownerName ?? '同学')
})

/** 一张卡只挂一个标签：系统题单写「推荐」，规则生成的写「自动生成」，同学和自己的写分享范围 */
const tag = computed<{ text: string; tone: string; tip?: string } | null>(() => {
  const list = props.list
  if (list.source === 'GENERATED') {
    return { text: '自动生成', tone: 'bg-surface-2 text-body', tip: list.generatedRule || '按规则从已同步题库自动筛选，不是人工挑的' }
  }
  if (list.ownerId === null) return { text: '推荐', tone: 'bg-success-soft text-success-ink' }
  if (list.shareScope === 'PRIVATE') return { text: '仅自己', tone: 'bg-surface-2 text-body' }
  if (list.shareScope === 'SCHOOL') return { text: '本校可见', tone: 'bg-info-soft text-info-ink' }
  return { text: '全站公开', tone: 'bg-info-soft text-info-ink' }
})
</script>

<template>
  <article class="list-card flex flex-col gap-2.5 border border-line rounded-card border-solid bg-surface p-4 md:p-[18px]" :data-list="list.id">
    <div class="flex items-center gap-2 text-[12.5px] text-muted">
      <PlatformLogo v-if="list.platform" :platform="list.platform" :size="20" :title="platformName(list.platform)" />
      <span v-else class="i-lucide-layers text-base text-faint" aria-hidden="true" />
      <span class="min-w-0 truncate">
        <span v-if="list.platform" class="sr-only">{{ platformName(list.platform) }} · </span>{{ author }}
      </span>
      <span class="flex-1" />
      <el-tooltip v-if="tag?.tip" :content="tag.tip" placement="top">
        <span class="list-tag cursor-help" :class="tag.tone" tabindex="0">{{ tag.text }}</span>
      </el-tooltip>
      <span v-else-if="tag" class="list-tag" :class="tag.tone">{{ tag.text }}</span>
    </div>

    <RouterLink :to="detail" class="list-title text-base text-ink font-semibold leading-[1.45] [overflow-wrap:anywhere] hover:text-brand-600">
      {{ list.title }}
    </RouterLink>
    <p class="list-desc m-0 text-[13px] text-muted leading-[1.6]">{{ list.description || '这个题单还没有写说明。' }}</p>

    <div v-if="total > 0" class="flex items-center gap-2.5">
      <span
        class="h-2 flex-1 overflow-hidden rounded bg-surface-2"
        role="progressbar"
        :aria-valuenow="done"
        aria-valuemin="0"
        :aria-valuemax="total"
        :aria-label="`${list.title}：已通过 ${done} / ${total} 题`"
      >
        <span class="block h-full rounded" :class="finished ? 'bg-success' : 'bg-brand'" :style="{ width: `${percent}%` }" />
      </span>
      <span class="num text-[13px] text-ink font-semibold">{{ done }} / {{ total }}</span>
    </div>
    <p v-else class="m-0 text-[13px] text-muted">还没有题目</p>

    <div class="mt-auto flex items-center gap-2 border-t border-line-soft border-t-solid pt-2.5">
      <button
        v-if="!management"
        type="button"
        class="fav-btn"
        :class="{ 'is-on': list.favorited }"
        :disabled="favoritePending"
        :aria-pressed="Boolean(list.favorited)"
        :aria-label="`${list.favorited ? '取消收藏' : '收藏'}「${list.title}」，${list.favoriteCount ?? 0} 人收藏`"
        @click="$emit('favorite')"
      >
        <StarIcon :on="Boolean(list.favorited)" :size="15" />
        <span class="num">{{ list.favoriteCount ?? 0 }}</span>
      </button>
      <!-- 题单代码是管理用的标识，同学不需要看 -->
      <span v-if="showCode" class="min-w-0 truncate text-xs text-muted font-mono">{{ list.code }}</span>
      <span v-if="management && !list.visible" class="text-xs text-warning-ink">已隐藏</span>
      <span class="flex-1" />
      <button v-if="canEdit" type="button" class="icon-btn" :aria-label="`删除「${list.title}」`" @click="$emit('remove')">
        <span class="i-lucide-trash-2 text-base" aria-hidden="true" />
      </button>
      <RouterLink :to="detail" class="cta" :aria-label="`${cta}「${list.title}」`">{{ cta }}</RouterLink>
    </div>
  </article>
</template>

<style scoped>
.list-card {
  transition: border-color 0.15s ease, box-shadow 0.15s ease;
}

.list-card:hover {
  border-color: var(--border-strong);
  box-shadow: var(--shadow-sm);
}

.list-tag {
  display: inline-flex;
  flex-shrink: 0;
  align-items: center;
  height: 20px;
  padding: 0 7px;
  border-radius: 6px;
  font-size: 11.5px;
  font-weight: 600;
}

.list-desc {
  display: -webkit-box;
  min-height: calc(2 * 1.6em);
  overflow: hidden;
  -webkit-line-clamp: 2;
  line-clamp: 2;
  -webkit-box-orient: vertical;
  overflow-wrap: anywhere;
}

.fav-btn {
  display: inline-flex;
  align-items: center;
  gap: 5px;
  height: 32px;
  margin-left: -6px;
  padding: 0 6px;
  border: 0;
  border-radius: 8px;
  background: transparent;
  color: var(--text-muted);
  font: inherit;
  font-size: 13px;
  cursor: pointer;
}

.fav-btn:hover {
  background: var(--surface-hover);
}

.fav-btn.is-on {
  color: var(--text);
}

.icon-btn {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  width: 32px;
  height: 32px;
  border: 0;
  border-radius: 8px;
  background: transparent;
  color: var(--text-muted);
  cursor: pointer;
}

.icon-btn:hover {
  background: var(--danger-soft);
  color: var(--danger-ink);
}

.cta {
  display: inline-flex;
  align-items: center;
  height: 32px;
  padding: 0 12px;
  border-radius: 8px;
  background: var(--brand-soft);
  color: var(--brand-600);
  font-size: 13px;
  font-weight: 600;
  text-decoration: none;
}

.cta:hover {
  background: var(--brand-btn);
  color: #fff;
}

.fav-btn:focus-visible,
.icon-btn:focus-visible,
.cta:focus-visible {
  outline: 2px solid var(--brand-500);
  outline-offset: 2px;
}
</style>
