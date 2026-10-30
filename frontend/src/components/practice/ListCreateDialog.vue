<script setup lang="ts">
import { computed, reactive, ref, watch } from 'vue'
import { ElMessage } from 'element-plus'
import { notifyError, post } from '@/api/client'
import RadioCards from '@/components/ui/RadioCards.vue'
import ResponsiveDialog from '@/components/ui/ResponsiveDialog.vue'
import { LIST_PLATFORM_OPTIONS, SHARE_SCOPE_OPTIONS, trimmed, type ProblemList, type ShareScope } from './types'

/** 新建题单：题单编号由后端自动生成，不需要用户填；题目建好后在详情页里加。 */
const visible = defineModel<boolean>({ required: true })
const props = defineProps<{ management?: boolean }>()
const scopeOptions = computed(() => props.management ? SHARE_SCOPE_OPTIONS.filter(o => o.value !== 'PRIVATE') : SHARE_SCOPE_OPTIONS)
const emit = defineEmits<{ created: [] }>()

const saving = ref(false)
const form = reactive({ title: '', description: '', category: '', platform: '', shareScope: 'PRIVATE' as ShareScope, schoolId: undefined as number | undefined })

watch(visible, (open) => {
  if (!open) return
  Object.assign(form, { title: '', description: '', category: '', platform: '', shareScope: props.management ? 'PUBLIC' : 'PRIVATE', schoolId: undefined })
})

async function submit() {
  if (!form.title.trim()) {
    ElMessage.warning('先填题单标题')
    return
  }
  saving.value = true
  try {
    await post<ProblemList>(props.management ? '/admin/problem-lists' : '/problem-lists', {
      title: form.title.trim(),
      description: trimmed(form.description),
      category: trimmed(form.category),
      platform: trimmed(form.platform),
      shareScope: form.shareScope,
      ...(props.management ? { schoolId: form.schoolId } : {})
    })
    visible.value = false
    ElMessage.success('题单已创建')
    emit('created')
  } catch (e) {
    notifyError(e)
  } finally {
    saving.value = false
  }
}
</script>

<template>
  <ResponsiveDialog v-model="visible" title="新建题单" :width="520">
    <el-form label-position="top" @submit.prevent="submit">
      <el-form-item label="标题" required>
        <el-input v-model="form.title" maxlength="80" placeholder="例如：寒假动态规划打卡" data-test="create-title" />
      </el-form-item>
      <el-form-item label="说明">
        <el-input v-model="form.description" type="textarea" :rows="3" maxlength="300" placeholder="写清楚这个题单想练什么，方便以后回看" />
      </el-form-item>
      <div class="grid grid-cols-1 gap-x-3 md:grid-cols-2">
        <el-form-item label="分组">
          <el-input v-model="form.category" maxlength="30" placeholder="不填就放进「其他题单」" />
        </el-form-item>
        <el-form-item label="平台">
          <el-select v-model="form.platform" placeholder="不限平台" clearable class="w-full">
            <el-option v-for="p in LIST_PLATFORM_OPTIONS" :key="p.value" :label="p.label" :value="p.value" />
          </el-select>
        </el-form-item>
      </div>
      <el-form-item label="谁能看到">
        <RadioCards v-model="form.shareScope" :options="scopeOptions" label="谁能看到" class="w-full" />
      </el-form-item>
      <el-form-item v-if="management && form.shareScope === 'SCHOOL'" label="学校编号" required>
        <el-input-number v-model="form.schoolId" :min="1" :precision="0" />
      </el-form-item>
    </el-form>
    <p class="m-0 text-[13px] text-muted leading-5">建好后在题单详情里加题；以后可以随时改分享范围。</p>
    <template #footer>
      <el-button @click="visible = false">取消</el-button>
      <el-button type="primary" :loading="saving" data-test="create-submit" @click="submit">创建</el-button>
    </template>
  </ResponsiveDialog>
</template>
