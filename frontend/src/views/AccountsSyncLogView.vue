<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { get, notifyError } from '@/api/client'
import type { SyncTask } from '@/api/types'
import SyncTaskList from '@/components/accounts/SyncTaskList.vue'
import PageHeader from '@/components/ui/PageHeader.vue'

/** 同步记录：手机上从平台账号页进来的二级页面（顶栏左上角返回）；电脑上同样的内容在平台账号页里展开 */
const tasks = ref<SyncTask[]>([])
const loading = ref(true)
let request = 0

async function load() {
  const current = ++request
  loading.value = true
  try {
    const list = await get<SyncTask[]>('/sync/tasks', { limit: 30 })
    if (current === request) tasks.value = list
  } catch (e) {
    if (current === request) notifyError(e)
  } finally {
    if (current === request) loading.value = false
  }
}

onMounted(load)

const description = computed(() => (tasks.value.length ? `最近 ${tasks.value.length} 次同步任务的结果和耗时` : '每次同步任务的结果和耗时'))
</script>

<template>
  <div data-test="page">
    <RouterLink to="/accounts" class="mb-3 hidden items-center gap-1 text-[13.5px] md:inline-flex">
      <span class="i-lucide-chevron-left text-base" aria-hidden="true" />平台账号
    </RouterLink>
    <PageHeader title="同步记录" :description="description">
      <template #actions>
        <el-button :loading="loading" @click="load">刷新</el-button>
      </template>
      <template #mobile-actions>
        <button type="button" class="topbar-icon" :disabled="loading" aria-label="刷新同步记录" @click="load">
          <span class="i-lucide-refresh-cw text-xl" :class="{ 'animate-spin motion-reduce:animate-none': loading }" aria-hidden="true" />
        </button>
      </template>
    </PageHeader>
    <SyncTaskList :tasks="tasks" :loading="loading && !tasks.length" />
  </div>
</template>
