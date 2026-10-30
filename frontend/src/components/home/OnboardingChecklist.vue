<script setup lang="ts">
import { computed } from 'vue'

/**
 * 新同学的「开始积累你的刷题档案」：① 验证邮箱、选择学校（到总览时已经做完）② 绑定第一个刷题账号
 * ③ 等第一次同步完成 ④ 看看第一份档案。当前一步浅靛蓝底、带说明和按钮；同步成功后总览换成正常的数据页，这张卡片就不出现了。
 */
const props = defineProps<{
  /** 当前在第几步：2 还没绑账号，3 绑了还没同步出数据 */
  step: 2 | 3
  /** 已绑定的账号数 */
  accounts: number
  /** 有账号正在排队或同步 */
  syncing: boolean
  /** 有账号第一次同步就失败了 */
  failed: boolean
}>()

const done = computed(() => props.step - 1)
</script>

<template>
  <section class="checklist" data-test="home-onboarding">
    <div class="flex items-center justify-between gap-3">
      <h2 class="m-0 text-[16.5px] text-ink font-650">开始积累你的刷题档案</h2>
      <span class="num text-[13px] text-brand-600 font-semibold">{{ done }} / 4</span>
    </div>
    <div class="h-1.5 overflow-hidden rounded-full bg-line-soft" role="progressbar" :aria-valuenow="done" aria-valuemin="0" aria-valuemax="4" :aria-label="`已完成 ${done} 步，共 4 步`">
      <span class="block h-full rounded-full bg-brand" :style="{ width: `${done * 25}%` }" />
    </div>

    <ol class="m-0 flex list-none flex-col gap-2 p-0">
      <li class="item">
        <span class="mark is-done" aria-hidden="true"><span class="i-lucide-check text-sm" /></span>
        <span class="flex-1 text-[14.5px] text-muted">完成注册资料</span>
        <span class="text-[12.5px] text-success-ink">已完成</span>
      </li>

      <li v-if="step === 2" class="item is-current" aria-current="step">
        <span class="mark is-current" aria-hidden="true">2</span>
        <span class="flex flex-1 flex-col gap-1.5">
          <span class="text-[15px] text-ink font-semibold">绑定第一个刷题账号</span>
          <span class="text-[13px] text-body leading-[1.6]">填上你的用户名，再到那个平台的个人资料里贴一段验证串，证明是你本人。</span>
          <RouterLink :to="{ path: '/accounts', query: { add: '' } }" class="step-button">去绑定</RouterLink>
        </span>
      </li>
      <li v-else class="item">
        <span class="mark is-done" aria-hidden="true"><span class="i-lucide-check text-sm" /></span>
        <span class="flex-1 text-[14.5px] text-muted">绑定刷题账号</span>
        <span class="num text-[12.5px] text-success-ink">已绑定 {{ accounts }} 个</span>
      </li>

      <li v-if="step === 3" class="item is-current" aria-current="step">
        <span class="mark is-current" aria-hidden="true">3</span>
        <span class="flex flex-1 flex-col gap-1.5">
          <span class="text-[15px] text-ink font-semibold">等第一次同步完成</span>
          <span v-if="failed" class="text-[13px] text-danger-ink leading-[1.6]">有账号第一次同步没成功，看看是什么原因。</span>
          <span v-else class="text-[13px] text-body leading-[1.6]">{{ syncing ? '正在同步' : '已经排上队了' }}，通常 1–3 分钟，可以先随便逛逛。</span>
          <RouterLink v-if="failed" to="/accounts" class="step-button">看原因</RouterLink>
        </span>
      </li>
      <li v-else class="item">
        <span class="mark" aria-hidden="true">3</span>
        <span class="flex flex-col gap-0.5">
          <span class="text-[14.5px] text-ink">等第一次同步完成</span>
          <span class="text-[12.5px] text-muted">通常 1–3 分钟，可以先随便逛逛</span>
        </span>
      </li>

      <li class="item">
        <span class="mark" aria-hidden="true">4</span>
        <span class="flex flex-col gap-0.5">
          <span class="text-[14.5px] text-ink">看看你的第一份档案</span>
          <span class="text-[12.5px] text-muted">每周进步、同届位置，都在这里</span>
        </span>
      </li>
    </ol>
  </section>
</template>

<style scoped>
.checklist {
  display: flex;
  flex-direction: column;
  gap: 14px;
  padding: 18px;
  border: 1px solid color-mix(in srgb, var(--brand-400) 40%, var(--border));
  border-radius: 16px;
  background: var(--bg-elevated);
}

.item {
  display: flex;
  align-items: flex-start;
  gap: 12px;
  padding: 8px 4px;
}

.item:first-child {
  align-items: center;
}

.item.is-current {
  padding: 12px;
  border-radius: 12px;
  background: var(--brand-soft);
}

.mark {
  display: flex;
  flex-shrink: 0;
  align-items: center;
  justify-content: center;
  width: 26px;
  height: 26px;
  border: 1.5px solid var(--border-strong);
  border-radius: 50%;
  color: var(--text-muted);
  font-family: var(--font-num);
  font-size: 13px;
}

.mark.is-done {
  border: 0;
  background: var(--success);
  color: #fff;
}

.mark.is-current {
  border: 0;
  background: var(--brand-btn);
  color: #fff;
  font-weight: 600;
}

.step-button {
  display: inline-flex;
  align-items: center;
  align-self: flex-start;
  height: 40px;
  margin-top: 4px;
  padding: 0 18px;
  border-radius: 10px;
  background: var(--brand-btn);
  color: #fff;
  font-size: 14.5px;
  font-weight: 600;
}

.step-button:hover {
  background: var(--brand-btn-hover);
  color: #fff;
  text-decoration: none;
}

.step-button:focus-visible {
  outline: 2px solid var(--brand-500);
  outline-offset: 2px;
}

@media (min-width: 768px) {
  .checklist {
    padding: 20px 22px;
  }
}
</style>
