<script setup lang="ts">
/**
 * 有未保存的改动时浮在底部的深色保存条：一句话 + 「放弃」「保存」。
 * 电脑在内容区下方居中；手机在底栏上面。颜色固定（深色底），深浅色主题下一样醒目。
 */
defineProps<{ saving?: boolean; text?: string }>()
const emit = defineEmits<{ discard: []; save: [] }>()
</script>

<template>
  <div class="save-bar" role="region" aria-label="未保存的修改" data-test="save-bar">
    <span class="min-w-0 flex-1 text-sm">{{ text ?? '有未保存的修改' }}</span>
    <button type="button" class="discard" :disabled="saving" @click="emit('discard')">放弃</button>
    <button type="button" class="save" :disabled="saving" @click="emit('save')">
      <span v-if="saving" class="i-lucide-loader-circle mr-1 animate-spin" aria-hidden="true" />保存
    </button>
  </div>
</template>

<style scoped>
.save-bar {
  position: fixed;
  z-index: 30;
  bottom: 24px;
  left: 50%;
  display: flex;
  align-items: center;
  gap: 10px;
  width: min(560px, calc(100vw - 32px));
  box-sizing: border-box;
  padding: 10px 10px 10px 18px;
  border-radius: 14px;
  background: #1e1b4b;
  box-shadow: 0 12px 32px rgba(18, 22, 41, 0.35);
  color: #fff;
  transform: translateX(-50%);
}

/* 电脑、平板：在侧栏右边的内容区居中 */
@media (min-width: 768px) {
  .save-bar {
    left: calc(50% + 36px);
  }
}

@media (min-width: 1024px) {
  .save-bar {
    left: calc(50% + 120px);
  }
}

/* 手机：在底栏上面 */
@media (max-width: 767px) {
  .save-bar {
    bottom: calc(76px + env(safe-area-inset-bottom));
  }
}

button {
  height: 36px;
  padding: 0 14px;
  border: 0;
  border-radius: 10px;
  font: inherit;
  font-size: 14px;
  cursor: pointer;
}

.discard {
  background: transparent;
  color: #c7d2fe;
}

.discard:hover {
  background: rgba(255, 255, 255, 0.08);
}

.save {
  display: inline-flex;
  align-items: center;
  background: #fff;
  color: #312e81;
  font-weight: 600;
}

button:disabled {
  opacity: 0.6;
  cursor: default;
}

button:focus-visible {
  outline: 2px solid #fff;
  outline-offset: 2px;
}
</style>
