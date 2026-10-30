<script setup lang="ts">
import InfoTip from '@/components/ui/InfoTip.vue'
import Skeleton from '@/components/ui/Skeleton.vue'

/** 电脑右栏「近 7 天校内」：在练同学、通过题数（全校或同届）。 */
defineProps<{
  summary: { activeUsers?: number; totalEvents?: number; note?: string } | null
  /** 同届时是入学年级，全校时为空 */
  scopeLabel?: string
  year: number | null
}>()
</script>

<template>
  <section class="side-card" data-test="campus-week">
    <div class="flex items-center justify-between gap-2">
      <h2 class="m-0 flex items-center gap-1 text-[15px] text-ink font-semibold">
        近 7 天 · {{ scopeLabel || (year ? `${year} 级` : '校内') }}
        <InfoTip title="怎么算">{{ summary?.note || '近 7 天、本校参与校内展示的同学的首次通过记录。' }}按「首次通过」去重，同一题重复提交只算一次；账号同步后才计入。</InfoTip>
      </h2>
    </div>
    <div v-if="!summary" class="grid grid-cols-2 gap-3">
      <Skeleton class="h-14" />
      <Skeleton class="h-14" />
    </div>
    <dl v-else class="m-0 grid grid-cols-2 gap-3">
      <div class="tile">
        <dt class="text-[12.5px] text-muted">在练同学</dt>
        <dd class="m-0 flex items-baseline gap-1"><span class="num text-2xl text-ink font-semibold">{{ summary.activeUsers ?? 0 }}</span><span class="text-[12.5px] text-muted">位</span></dd>
      </div>
      <div class="tile">
        <dt class="text-[12.5px] text-muted">通过题数</dt>
        <dd class="m-0 flex items-baseline gap-1"><span class="num text-2xl text-ink font-semibold">{{ summary.totalEvents ?? 0 }}</span><span class="text-[12.5px] text-muted">题</span></dd>
      </div>
    </dl>
  </section>
</template>

<style scoped>
.side-card {
  display: flex;
  flex-direction: column;
  gap: 12px;
  padding: 18px 20px;
  border: 1px solid var(--border);
  border-radius: var(--radius);
  background: var(--bg-elevated);
}

.tile {
  display: flex;
  flex-direction: column;
  gap: 2px;
  padding: 10px 12px;
  border-radius: 12px;
  background: var(--bg);
}
</style>
