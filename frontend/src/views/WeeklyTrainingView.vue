<script setup lang="ts">
import { computed, onBeforeUnmount, onMounted, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { notifyError } from '@/api/client'
import { loadContests, loadSchools, type TrainingSchool, type WeeklyContest } from '@/api/weeklyTraining'
import { shiftDate } from '@/utils/statistics'
import PageHeader from '@/components/ui/PageHeader.vue'
import WeeklyProgress from '@/components/weekly/WeeklyProgress.vue'
import WeeklyPublisher from '@/components/weekly/WeeklyPublisher.vue'

const route = useRoute(), router = useRouter()
const schools = ref<TrainingSchool[]>([]), schoolId = ref<number>(), contests = ref<WeeklyContest[]>([])
const school = computed(() => schools.value.find(s => s.id === schoolId.value))
const loading = ref(true), error = ref(''), publisher = ref(false)
let request = 0, disposed = false
onBeforeUnmount(() => { disposed = true; request++ })

async function initialize() {
  loading.value = true; error.value = ''
  try {
    const data = await loadSchools()
    if (disposed) return
    schools.value = data
    schoolId.value = data.find(s => s.id === Number(route.query.school))?.id ?? data[0]?.id
    if (!schoolId.value) loading.value = false
  } catch (e) {
    if (!disposed) { loading.value = false; error.value = '学校读取失败'; notifyError(e) }
  }
}
async function refresh() {
  const id = schoolId.value
  const token = ++request
  if (!id) return
  contests.value = []; error.value = ''; loading.value = true
  try {
    const list = await loadContests(id)
    if (request === token) contests.value = list
  } catch (e) {
    if (request === token) { error.value = '训练赛读取失败'; notifyError(e) }
  } finally { if (request === token) loading.value = false }
}
onMounted(initialize)
watch(schoolId, () => { publisher.value = false; void refresh() }, { flush: 'sync' })
function selectSchool(id: number) {
  schoolId.value = id
  void router.replace({ query: { ...route.query, school: String(id) } })
}
const weeks = computed(() => {
  const groups = new Map<string, WeeklyContest[]>()
  for (const contest of contests.value) {
    const group = groups.get(contest.weekStart) ?? []
    group.push(contest); groups.set(contest.weekStart, group)
  }
  return Array.from(groups).sort(([a], [b]) => b.localeCompare(a))
})
</script>
<template>
  <div class="page mx-auto max-w-[1100px]">
    <PageHeader title="每周训练赛" :description="school?.name">
      <template #actions>
        <el-button v-if="school" aria-label="刷新完成情况" title="刷新完成情况" :disabled="loading" @click="refresh"><i class="i-lucide-refresh-cw" aria-hidden="true" /></el-button>
        <el-button v-if="school?.manage" type="primary" @click="publisher = true">布置训练赛</el-button>
      </template>
    </PageHeader>
    <el-select v-if="schools.length > 1" :model-value="schoolId" aria-label="选择学校" class="mb-4 max-w-full !w-72" @change="selectSchool">
      <el-option v-for="s in schools" :key="s.id" :value="s.id" :label="s.name" />
    </el-select>
    <div v-if="error" role="alert">{{ error }} <el-button text @click="schoolId ? refresh() : initialize()">重试</el-button></div>
    <div v-else-if="loading" v-loading="true" class="min-h-32" aria-label="正在读取训练赛" />
    <el-empty v-else-if="!contests.length" :description="school ? '本校暂未布置训练赛' : '暂无可查看的学校'" />
    <section v-for="[week, items] in weeks" :key="week" class="mb-6">
      <h2 class="mb-2 mt-0 flex items-center gap-2 text-sm text-muted">
        <i class="i-lucide-calendar-days" aria-hidden="true" /> {{ week }} 至 {{ shiftDate(week, 6) }}
      </h2>
      <div class="grid gap-3"><WeeklyProgress v-for="contest in items" :key="contest.id" :contest="contest" :school="schoolId!" /></div>
    </section>
    <WeeklyPublisher v-if="publisher && school?.manage" :key="school.id" :school="school.id" @close="publisher = false" @published="publisher = false; refresh()" />
  </div>
</template>
