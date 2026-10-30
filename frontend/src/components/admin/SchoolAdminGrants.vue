<script setup lang="ts">
import { onMounted, ref } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { get, post, del, notifyError } from '@/api/client'
import SectionCard from '@/components/ui/SectionCard.vue'
type School = { id: number; name: string }
type User = { id: number; username: string; displayName: string }
type Grant = { userId: number; schoolId: number; username: string; displayName: string; schoolName: string }
const schools=ref<School[]>([]), grants=ref<Grant[]>([]), candidates=ref<User[]>([])
const schoolId=ref<number>(), userId=ref<number>(), query=ref(''), busy=ref(false)
async function load() { try { [schools.value,grants.value]=await Promise.all([get<School[]>('/schools'),get<Grant[]>('/admin/school-admins')]) } catch(e){notifyError(e)} }
async function search() { if(!query.value.trim()) return; try{ candidates.value=await get<User[]>('/admin/school-admins/candidates',{q:query.value}); userId.value=undefined }catch(e){notifyError(e)} }
async function save() { if(!schoolId.value || !userId.value) return; busy.value=true; try{await post('/admin/school-admins',{schoolId:schoolId.value,userId:userId.value}); ElMessage.success('已授予学校管理权限'); await load()}catch(e){notifyError(e)}finally{busy.value=false} }
async function revoke(row:Grant) { try{await ElMessageBox.confirm(`撤销 ${row.displayName} 对 ${row.schoolName} 的管理权限？`,'撤销学校管理员',{type:'warning'})}catch{return} try{await del(`/admin/school-admins/${row.userId}/${row.schoolId}`);await load();ElMessage.success('已撤销')}catch(e){notifyError(e)} }
onMounted(load)
</script>
<template>
  <SectionCard title="学校管理员" description="指定学校的成员审核、班级学号维护和校内公告管理。平台管理员权限高于学校管理员。" class="mb-4">
    <div class="flex flex-col gap-3">
      <div class="flex flex-wrap gap-2">
        <el-input v-model="query" placeholder="搜索用户名或昵称" aria-label="搜索学校管理员候选人" class="!w-60" @keyup.enter="search" />
        <el-button @click="search">搜索用户</el-button>
      </div>
      <div class="flex flex-wrap gap-2">
        <el-select v-model="userId" placeholder="选择用户" aria-label="选择学校管理员" class="!w-60"><el-option v-for="u in candidates" :key="u.id" :value="u.id" :label="`${u.displayName}（${u.username}）`" /></el-select>
        <el-select v-model="schoolId" filterable placeholder="选择负责的学校" aria-label="选择负责的学校" class="!w-60"><el-option v-for="s in schools" :key="s.id" :value="s.id" :label="s.name" /></el-select>
        <el-button type="primary" :disabled="!userId || !schoolId" :loading="busy" @click="save">授予权限</el-button>
      </div>
      <p v-if="!grants.length" class="text-muted text-sm">尚未设置学校管理员。</p>
      <div v-for="g in grants" :key="`${g.userId}-${g.schoolId}`" class="flex flex-wrap items-center justify-between gap-2 border-b border-line py-3">
        <div><div class="text-ink font-medium">{{ g.displayName }} <span class="text-muted text-xs">{{ g.username }}</span></div><div class="text-muted text-sm">{{ g.schoolName }}</div></div>
        <el-button type="danger" plain size="small" @click="revoke(g)">撤销权限</el-button>
      </div>
    </div>
  </SectionCard>
</template>
