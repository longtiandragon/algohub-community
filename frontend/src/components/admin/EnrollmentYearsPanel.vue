<script setup lang="ts">
import { onMounted, ref } from 'vue'
import { ElMessage } from 'element-plus'
import { get, post, notifyError } from '@/api/client'
import SectionCard from '@/components/ui/SectionCard.vue'
interface Setting { year: number; monthlyTarget: number; enabled: boolean }
const rows = ref<Setting[]>([])
const year = ref(new Date().getFullYear() + 1)
const monthlyTarget = ref(0)
const busy = ref(false)
async function load() { try { rows.value = await get('/admin/enrollment-years') } catch (e) { notifyError(e) } }
async function save(row: Setting) {
  busy.value = true
  try { rows.value = await post('/admin/enrollment-years', row); ElMessage.success('年级配置已更新') }
  catch (e) { notifyError(e); await load() } finally { busy.value = false }
}
async function add() {
  if (rows.value.some(r => r.year === year.value)) { ElMessage.warning('该年级已存在，请在列表中修改'); return }
  await save({ year: year.value, monthlyTarget: monthlyTarget.value, enabled: true })
}
onMounted(load)
</script>
<template>
  <SectionCard title="年级与默认目标" description="开放的年级用于注册、入校、资料设置和排行筛选。停止开放只移除选项，已有成员和历史记录继续保留。">
    <div class="space-y-4">
      <div v-for="row in rows" :key="row.year" class="flex flex-wrap items-center gap-3 rounded-lg bg-surface-2 p-3">
        <strong class="min-w-20 text-ink">{{ row.year }}级</strong>
        <el-switch v-model="row.enabled" active-text="开放选择" :disabled="busy" :aria-label="`${row.year}级开放选择`" />
        <label :for="`target-${row.year}`" class="text-sm">默认月目标</label>
        <el-input-number :id="`target-${row.year}`" v-model="row.monthlyTarget" :min="0" :max="10000" :precision="0" :disabled="busy" />
        <el-button :disabled="busy" @click="save(row)">保存{{ row.year }}级</el-button>
      </div>
      <p class="text-sm text-muted">0表示无默认要求。修改默认目标立即影响未被管理员单独指定月要求的成员；停止开放不会取消原年级的目标。</p>
      <el-form label-position="top" class="pt-4" style="border-top: 1px solid var(--border)" @submit.prevent="add">
        <div class="flex flex-wrap gap-4">
          <el-form-item label="新增入学年级"><el-input-number v-model="year" :min="2000" :max="2100" :precision="0" aria-label="新增入学年级" /></el-form-item>
          <el-form-item label="默认月目标（题）"><el-input-number v-model="monthlyTarget" :min="0" :max="10000" :precision="0" aria-label="新增年级默认月目标" /></el-form-item>
        </div>
        <el-button native-type="submit" type="primary" :loading="busy">新增并开放</el-button>
      </el-form>
    </div>
  </SectionCard>
</template>
