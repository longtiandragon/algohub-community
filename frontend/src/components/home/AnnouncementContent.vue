<script setup lang="ts">
defineProps<{ kind: string; content?: string; items?: string[] }>()
</script>

<template>
  <ol v-if="kind === 'UPDATE'" class="update-list">
    <li v-for="(item, index) in items ?? []" :key="index">
      <span class="update-number" aria-hidden="true">{{ String(index + 1).padStart(2, '0') }}</span>
      <p>{{ item }}</p>
    </li>
  </ol>
  <p v-else class="m-0 whitespace-pre-wrap text-sm text-body leading-[1.75] [overflow-wrap:anywhere]">{{ content }}</p>
</template>

<style scoped>
.update-list { margin: 0; padding: 0; list-style: none; display: grid; gap: 14px; }
.update-list li { display: flex; align-items: flex-start; gap: 12px; }
.update-number { flex-shrink: 0; color: var(--brand-600); background: var(--brand-soft); border-radius: 6px; padding: 2px 6px; font: 12px/22px var(--font-mono); }
.update-list p { margin: 0; color: var(--text); font-size: 14px; line-height: 1.85; white-space: pre-wrap; overflow-wrap: anywhere; min-width: 0; }
</style>
