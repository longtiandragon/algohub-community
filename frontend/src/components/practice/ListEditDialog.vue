<script setup lang="ts">
import { computed, reactive, ref, watch } from 'vue'
import { ElMessage } from 'element-plus'
import { notifyError, post } from '@/api/client'
import { useAuthStore } from '@/stores/auth'
import RadioCards from '@/components/ui/RadioCards.vue'
import ResponsiveDialog from '@/components/ui/ResponsiveDialog.vue'
import { SHARE_SCOPE_OPTIONS, trimmed, type ProblemList, type ShareScope } from './types'

/** 编辑题单：只改标题、说明和分享范围；分组与排序由管理后台维护。 */
const visible = defineModel<boolean>({ required: true })
const props = defineProps<{ list: ProblemList | null; management?: boolean }>()
const auth = useAuthStore()
const scopeOptions = computed(() => props.management && props.list?.ownerId === null ? SHARE_SCOPE_OPTIONS.filter(o => o.value !== 'PRIVATE') : SHARE_SCOPE_OPTIONS)
const emit = defineEmits<{ saved: [] }>()

const saving = ref(false)
const form = reactive({ title: '', description: '', shareScope: 'PRIVATE' as ShareScope, visible: true, schoolId: undefined as number | undefined })

watch(visible, (open) => {
  if (!open || !props.list) return
  form.title = props.list.title
  form.description = props.list.description ?? ''
  form.shareScope = props.list.shareScope
  form.visible = props.list.visible
  form.schoolId = props.list.schoolId ?? undefined
})

async function submit() {
  if (!props.list) return
  if (!props.management && props.list.ownerId !== auth.me?.id) return
  if (!form.title.trim()) {
    ElMessage.warning('先填题单标题')
    return
  }
  saving.value = true
  try {
    await post<ProblemList>(`${props.management ? '/admin/problem-lists' : '/problem-lists'}/${props.list.id}`, {
      title: form.title.trim(),
      description: trimmed(form.description),
      shareScope: form.shareScope,
      ...(props.management ? { visible: form.visible, schoolId: form.schoolId } : {})
    })
    visible.value = false
    ElMessage.success('题单已更新')
    emit('saved')
  } catch (e) {
    notifyError(e)
  } finally {
    saving.value = false
  }
}
</script>

<template>
  <ResponsiveDialog v-model="visible" title="编辑题单" :width="520">
    <el-form label-position="top" @submit.prevent="submit">
      <el-form-item label="标题" required>
        <el-input v-model="form.title" maxlength="80" data-test="edit-title" />
      </el-form-item>
      <el-form-item label="说明">
        <el-input v-model="form.description" type="textarea" :rows="3" maxlength="300" />
      </el-form-item>
      <el-form-item label="谁能看到">
        <RadioCards v-model="form.shareScope" :options="scopeOptions" label="谁能看到" class="w-full" />
      </el-form-item>
      <el-form-item v-if="management && list?.ownerId === null && form.shareScope === 'SCHOOL'" label="学校编号" required>
        <el-input-number v-model="form.schoolId" :min="1" :precision="0" />
      </el-form-item>
      <el-form-item v-if="management" label="显示题单">
        <el-switch v-model="form.visible" />
      </el-form-item>
    </el-form>
    <p class="m-0 text-[13px] text-muted leading-5">改成「仅自己」后，收藏过这个题单的同学就看不到它了。</p>
    <template #footer>
      <el-button @click="visible = false">取消</el-button>
      <el-button type="primary" :loading="saving" data-test="edit-submit" @click="submit">保存</el-button>
    </template>
  </ResponsiveDialog>
</template>
