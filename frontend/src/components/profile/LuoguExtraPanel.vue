<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { get, notifyError } from '@/api/client'
import InfoTip from '@/components/ui/InfoTip.vue'
import { beijingTime, dayTime } from '@/utils/statistics'

/**
 * 洛谷额外评测题：浏览器同步读到了、但不在洛谷公开已通过题单里的题（多是比赛题、自建题）。
 * 已合并到普通通过题统计；一页 20 道，前端分页。
 */
interface ExtraSolve {
  problemKey: string
  title: string | null
  difficulty: number | null
  acAt: string | null
  handle: string
}

const props = defineProps<{ userId?: number }>()
const rows = ref<ExtraSolve[]>([])
const loaded = ref(false)
const page = ref(1)
const size = 20
const visible = computed(() => rows.value.slice((page.value - 1) * size, page.value * size))
onMounted(async () => {
  try {
    rows.value = await get<ExtraSolve[]>(props.userId ? `/accounts/luogu-extra/${props.userId}` : '/accounts/luogu-extra')
  } catch (e) {
    notifyError(e)
  } finally {
    loaded.value = true
  }
})
</script>

<template>
  <section class="flex flex-col gap-3 border border-line rounded-card border-solid bg-surface p-4 md:(px-[22px] py-5)" aria-label="洛谷额外评测题目" data-test="luogu-extra">
    <div class="flex items-center justify-between gap-3">
      <h2 class="m-0 flex items-center gap-1 text-base text-ink font-semibold">
        洛谷额外评测题
        <InfoTip title="洛谷额外评测题">浏览器同步读到了、但不在洛谷公开已通过题单里的题，多是比赛题或自建题。已合并到普通通过题统计。</InfoTip>
      </h2>
      <span v-if="rows.length" class="num text-[12.5px] text-muted">{{ rows.length }} 道</span>
    </div>
    <p v-if="loaded && !rows.length" class="m-0 text-[13px] text-muted">没有额外的题：评测记录里的题都在公开题单里。</p>
    <ul v-else-if="rows.length" class="m-0 list-none p-0">
      <li v-for="row in visible" :key="`${row.handle}-${row.problemKey}`" class="row">
        <a :href="`https://www.luogu.com.cn/problem/${encodeURIComponent(row.problemKey)}`" target="_blank" rel="noopener noreferrer" class="mono code text-brand-600">{{ row.problemKey }}</a>
        <span class="title text-ink">{{ row.title || row.problemKey }}</span>
        <time v-if="row.acAt" class="time" :datetime="row.acAt" :title="beijingTime(row.acAt)">{{ dayTime(row.acAt) }}</time>
      </li>
    </ul>
    <el-pagination
      v-if="rows.length > size"
      v-model:current-page="page"
      :page-size="size"
      :total="rows.length"
      layout="prev, pager, next"
      small
      class="self-end"
    />
  </section>
</template>

<style scoped>
.row {
  display: flex;
  align-items: center;
  gap: 12px;
  min-height: 40px;
  border-top: 1px solid var(--border-soft);
}

.code {
  flex-shrink: 0;
  width: 84px;
  overflow: hidden;
  font-size: 12.5px;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.title {
  flex: 1;
  min-width: 0;
  overflow: hidden;
  font-size: 13.5px;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.time {
  flex-shrink: 0;
  color: var(--text-muted);
  font-size: 12.5px;
}
</style>
