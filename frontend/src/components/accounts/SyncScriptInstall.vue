<script setup lang="ts">
import { computed, onBeforeUnmount, onMounted, ref } from 'vue'
import SectionCard from '@/components/ui/SectionCard.vue'

const props = defineProps<{ platform: 'luogu' | 'qoj' | 'leetcode' }>()
const scriptUrl = `/userscripts/algohub-${props.platform}-sync.user.js`
const attribute = `data-algohub-sync-${props.platform}`
const installed = ref('')
const latest = ref('')
const loading = ref(true)
const openedInstaller = ref(false)
let observer: MutationObserver | undefined

function version(value: unknown) {
  return typeof value === 'string' && /^\d+\.\d+\.\d+$/.test(value) ? value : ''
}
function readInstalled() {
  installed.value = version(document.documentElement.getAttribute(attribute))
}
async function checkLatest() {
  loading.value = true
  try {
    const response = await fetch('/userscripts/versions.json', { cache: 'no-store', signal: AbortSignal.timeout(8000) })
    latest.value = response.ok ? version((await response.json())[props.platform]) : ''
  } catch {
    latest.value = ''
  } finally {
    loading.value = false
  }
}
onMounted(() => {
  observer = new MutationObserver(readInstalled)
  observer.observe(document.documentElement, { attributes: true, attributeFilter: [attribute] })
  readInstalled()
  void checkLatest()
})
onBeforeUnmount(() => observer?.disconnect())

const comparison = computed(() => installed.value && latest.value
  ? installed.value.localeCompare(latest.value, 'en', { numeric: true }) : null)
const needsUpdate = computed(() => comparison.value !== null && comparison.value < 0)
const message = computed(() => {
  if (loading.value) return '正在检查脚本版本…'
  if (!latest.value) return '暂时无法获取最新版本。'
  if (!installed.value) return `最新版 v${latest.value}。未检测到本机版本，已装过旧脚本的用户请先更新一次。`
  if (needsUpdate.value) return `需要更新 · 已安装 v${installed.value}，最新版 v${latest.value}`
  if (comparison.value === 0) return `已是最新版 · v${installed.value}`
  return `已安装 v${installed.value}，高于发布版 v${latest.value}，无需更新。`
})
</script>

<template>
  <SectionCard title="1. 同步脚本" description="首次安装前，请先启用浏览器扩展 Tampermonkey。">
    <div class="flex flex-wrap items-center gap-3">
      <span role="status" class="text-[13px]" :class="needsUpdate ? 'font-semibold text-warning-ink' : 'text-body'">{{ message }}</span>
      <el-button v-if="!loading && (comparison === null || needsUpdate)" tag="a" :href="scriptUrl"
        target="_blank" rel="noopener noreferrer" :type="needsUpdate ? 'primary' : 'default'" @click="openedInstaller = true">
        {{ needsUpdate ? '立即更新' : '安装或更新脚本' }}
      </el-button>
      <el-button v-if="!loading && !latest" @click="checkLatest">重新检查</el-button>
    </div>
    <p v-if="openedInstaller" class="mb-0 mt-2 text-[12.5px] text-muted">在 Tampermonkey 中确认后，刷新本页即可查看已安装版本。</p>
  </SectionCard>
</template>
