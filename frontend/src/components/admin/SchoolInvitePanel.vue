<script setup lang="ts">
import { onMounted, ref } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { get, notifyError, post, del } from '@/api/client'
import SectionCard from '@/components/ui/SectionCard.vue'

/**
 * 学校邀请码：同学选学校时填对码直接加入，不填码的进下面的待审核。
 * 码发到班群；传出去了就换一个，旧码立刻失效，已经加入的人不受影响。
 */
interface SchoolInvite {
  id: number
  name: string
  inviteCode: string | null
}

const schools = ref<SchoolInvite[]>([])
const loading = ref(true)
const busy = ref<number | null>(null)
const saving = ref(false)
const dialog = ref(false)
const editing = ref<number | null>(null)
const name = ref('')
function edit(school?: SchoolInvite) {
  editing.value = school?.id ?? null
  name.value = school?.name ?? ''
  dialog.value = true
}
async function save() {
  if (!name.value.trim()) { ElMessage.warning('请填写学校名称'); return }
  saving.value = true
  try {
    await post(editing.value ? `/admin/schools/${editing.value}` : '/admin/schools', { name: name.value })
    dialog.value = false
    ElMessage.success('学校已保存')
    await load()
  } catch (e) { notifyError(e) } finally { saving.value = false }
}
async function remove(school: SchoolInvite, inviteOnly: boolean) {
  try {
    await ElMessageBox.confirm(inviteOnly ? '停用后旧邀请码不能再入校，已有成员不受影响。' : '只能删除没有成员和教学数据的学校，删除后无法恢复。', inviteOnly ? '停用邀请码' : '删除学校', { type: 'warning' })
  } catch { return }
  busy.value = school.id
  try {
    await del(`/admin/schools/${school.id}${inviteOnly ? '/invite-code' : ''}`)
    ElMessage.success(inviteOnly ? '邀请码已停用' : '学校已删除')
    await load()
  } catch (e) { notifyError(e) } finally { busy.value = null }
}

async function load() {
  loading.value = true
  try {
    schools.value = await get<SchoolInvite[]>('/admin/schools')
  } catch (e) {
    notifyError(e)
  } finally {
    loading.value = false
  }
}

async function copy(code: string) {
  try {
    await navigator.clipboard.writeText(code)
    ElMessage.success('邀请码已复制')
  } catch {
    ElMessage.warning('没能复制，请手动选中邀请码复制')
  }
}

async function regenerate(school: SchoolInvite) {
  if (busy.value !== null) return
  try {
    await ElMessageBox.confirm(`换码后「${school.name}」的旧邀请码立刻失效，已经加入的同学不受影响。`, '更换邀请码', {
      confirmButtonText: '更换',
      cancelButtonText: '取消',
      type: 'warning'
    })
  } catch {
    return
  }
  busy.value = school.id
  try {
    const updated = await post<SchoolInvite>(`/admin/schools/${school.id}/invite-code`)
    school.inviteCode = updated.inviteCode
    ElMessage.success('已更换邀请码')
  } catch (e) {
    notifyError(e)
  } finally {
    busy.value = null
  }
}

onMounted(load)
</script>

<template>
  <SectionCard title="学校与邀请码" description="管理员创建学校后生成邀请码，复制并发给同学。填对码直接入校；不填码的在“学生与认证”中审核。" data-test="admin-school-invites">
    <template #actions><el-button type="primary" @click="edit()">新建学校</el-button></template>
    <p v-if="loading" class="m-0 text-[13px] text-muted" role="status">正在读取…</p>
    <p v-else-if="!schools.length" class="m-0 text-[13px] text-muted">还没有学校。</p>
    <ul v-else class="m-0 flex flex-col gap-2 p-0 list-none">
      <li v-for="school in schools" :key="school.id" class="flex flex-wrap items-center gap-x-3 gap-y-1.5">
        <span class="min-w-0 flex-1 text-sm text-ink">{{ school.name }}</span>
        <span class="mono select-all text-[13.5px] text-ink">{{ school.inviteCode || '未设置' }}</span>
        <el-button size="small" text @click="edit(school)">编辑学校</el-button>
        <el-button v-if="school.inviteCode" size="small" text @click="copy(school.inviteCode)">复制</el-button>
        <el-button size="small" text type="warning" :loading="busy === school.id" :disabled="busy !== null && busy !== school.id" @click="regenerate(school)">
          {{ school.inviteCode ? '换一个' : '生成' }}
        </el-button>
        <el-button v-if="school.inviteCode" size="small" text type="warning" :disabled="busy !== null" @click="remove(school, true)">停用邀请码</el-button>
        <el-button size="small" text type="danger" :disabled="busy !== null" @click="remove(school, false)">删除学校</el-button>
      </li>
    </ul>
    <el-dialog v-model="dialog" :title="editing ? '编辑学校' : '新建学校'" width="min(480px, 94vw)" :close-on-click-modal="false">
      <el-form label-position="top" @submit.prevent="save"><el-form-item label="学校名称" required><el-input v-model="name" maxlength="128" show-word-limit /></el-form-item></el-form>
      <template #footer><el-button :disabled="saving" @click="dialog = false">取消</el-button><el-button type="primary" :loading="saving" @click="save">保存</el-button></template>
    </el-dialog>
  </SectionCard>
</template>
