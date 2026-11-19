<script setup lang="ts">
import { computed, onBeforeUnmount, ref, watch } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { ApiError, notifyError } from '@/api/client'
import { grantCohortLeader, loadLeaderGrants, revokeCohortLeader, type CohortGrant } from '@/api/cohortLeaders'
import SchoolStudentPicker from '@/components/ui/SchoolStudentPicker.vue'
import type { DirectoryStudent } from '@/api/studentDirectory'
import SectionCard from '@/components/ui/SectionCard.vue'

const props = defineProps<{ schoolId: number }>()
const grants = ref<CohortGrant[]>([])
const picked = ref<DirectoryStudent[]>([])
const selected = computed(() => picked.value[0]?.userId)
const year = ref<number>()
const loading = ref(false), saving = ref(false)
const error = ref('')
let generation = 0, listRequest = 0
function failed(e: unknown) {
  if (e instanceof ApiError && e.kind === 'FORBIDDEN') {
    listRequest++
    grants.value = []; picked.value = []; year.value = undefined
    loading.value = false
  }
  notifyError(e)
}

async function load() {
  const token = generation, request = ++listRequest, school = props.schoolId
  loading.value = true
  error.value = ''
  try {
    const data = await loadLeaderGrants(school)
    if (token === generation && request === listRequest) grants.value = data
  } catch (e) {
    if (token !== generation || request !== listRequest) return
    error.value = '负责人名单暂时读取失败，请重试'
    failed(e)
  } finally { if (token === generation && request === listRequest) loading.value = false }
}
watch(() => props.schoolId, () => {
  generation++
  grants.value = []; picked.value = []; year.value = undefined
  saving.value = false
  void load()
}, { immediate: true })
watch(picked, value => { year.value = value[0]?.year ?? undefined })
onBeforeUnmount(() => { generation++; listRequest++ })

async function assign() {
  if (saving.value || !selected.value || !year.value) return
  const token = generation, school = props.schoolId, user = selected.value, targetYear = year.value
  saving.value = true; listRequest++
  try {
    await grantCohortLeader(school, user, targetYear)
    if (token !== generation) return
    picked.value = []; year.value = undefined
    ElMessage.success('年级负责人已任命')
    await load()
  } catch (e) {
    if (token === generation) {
      failed(e)
    }
  } finally { if (token === generation) saving.value = false }
}
async function revoke(grant: CohortGrant) {
  if (saving.value) return
  const token = generation, school = props.schoolId
  saving.value = true
  try {
    try { await ElMessageBox.confirm('撤销 ' + grant.displayName + ' 对 ' + grant.enrollmentYear + ' 级的管理权限？', '撤销负责人', { type: 'warning' }) }
    catch { return }
    if (token !== generation) return
    listRequest++
    await revokeCohortLeader(school, grant.id)
    if (token !== generation) return
    ElMessage.success('已撤销该年级的管理权限')
    await load()
  } catch (e) { if (token === generation) failed(e) }
  finally { if (token === generation) saving.value = false }
}
</script>

<template>
  <SectionCard class="cohort-grants" title="年级负责人" description="委派本校已审核学生，协助审核指定年级的成员、维护班级和学号、查看学习档案。">
    <div class="flex flex-col gap-4">
      <el-form label-position="top" @submit.prevent="assign">
        <div class="flex flex-wrap items-end gap-3">
          <el-form-item label="负责人" class="mb-0 min-w-0 flex-1">
            <SchoolStudentPicker :key="schoolId" v-model="picked" :school-id="schoolId" :disabled="saving" restriction="LEADER" />
          </el-form-item>
          <el-form-item label="负责的入学年级" class="mb-0"><el-input-number v-model="year" :disabled="saving || !selected" :min="2000" :max="2100" :precision="0" /></el-form-item>
          <el-button type="primary" :loading="saving" :disabled="!selected || !year" @click="assign">任命负责人</el-button>
        </div>
        <p class="mb-0 text-xs text-muted">默认填入本人入学年级，可指定其他本校年级。一届可以有多名负责人，每项授权可单独撤销。</p>
      </el-form>
      <p v-if="error" role="alert" class="m-0 text-sm text-danger-ink">{{ error }} <el-button text :disabled="saving" @click="load">重试</el-button></p>
      <div v-loading="loading" class="min-h-20">
        <el-empty v-if="!grants.length && !loading && !error" description="本校还没有年级负责人" />
        <div v-for="g in grants" :key="g.id" class="flex flex-wrap items-center justify-between gap-3 border-b border-line py-3">
          <div class="min-w-0"><strong class="text-ink">{{ g.displayName }}</strong><p class="mb-0 mt-1 break-words text-sm text-muted">{{ g.enrollmentYear }} 级负责人 · {{ g.username }}</p></div>
          <el-button :disabled="saving" type="danger" plain @click="revoke(g)">撤销</el-button>
        </div>
      </div>
    </div>
  </SectionCard>
</template>

<style scoped>
@media (max-width: 767px) {
  .cohort-grants :deep(.el-button),
  .cohort-grants :deep(.el-input__wrapper),
  .cohort-grants :deep(.el-select__wrapper) { min-height: 44px; }
}
</style>
