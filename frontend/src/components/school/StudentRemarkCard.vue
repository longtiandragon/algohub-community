<script setup lang="ts">
import { inject } from 'vue'
import { remarksKey } from '@/composables/useStudentRemarks'
import ResponsiveDialog from '@/components/ui/ResponsiveDialog.vue'
const {card,draft,saving,opening,save,close} = inject(remarksKey)!
</script>
<template>
  <ResponsiveDialog :model-value="!!card" title="学生卡片" @update:model-value="open => {if(!open) close()}">
    <template v-if="card"><h3 class="m-0">{{card.name}}</h3>
      <p v-if="opening" class="text-muted">正在读取学生资料…</p>
      <p v-else class="text-muted">{{card.year ? card.year+' 级 · ' : ''}}{{card.className || '未填写班级'}} · 学号 {{card.studentNo || '未填写'}}</p>
      <label for="private-student-remark">私人备注名 <small class="text-muted">仅你可见</small></label>
      <el-input id="private-student-remark" v-model="draft" :disabled="opening || saving" maxlength="64" clearable placeholder="输入备注名，清空即可移除" @keyup.enter="save" />
      <RouterLink :to="'/users/'+card.userId" class="mt-3 inline-block text-brand-600">查看学生档案</RouterLink>
    </template>
    <template #footer><el-button :disabled="saving" @click="close">取消</el-button><el-button type="primary" :loading="saving" :disabled="opening" @click="save">保存备注</el-button></template>
  </ResponsiveDialog>
</template>
