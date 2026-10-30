<script setup lang="ts">
import { reactive, ref, watch } from 'vue'
import { ElMessage } from 'element-plus'
import { get, notifyError, post } from '@/api/client'
import RadioCards from '@/components/ui/RadioCards.vue'
import ResponsiveDialog from '@/components/ui/ResponsiveDialog.vue'
import { useAuthStore } from '@/stores/auth'
import type { Activity } from './types'

/** 管理员发布 / 编辑训练活动：标题、说明、题单、起止时间、面向哪所学校、是否立即发布。 */
const visible = defineModel<boolean>({ required: true })
const props = defineProps<{ activity: Activity | null }>()
const emit = defineEmits<{ saved: [] }>()

const auth = useAuthStore()
const saving = ref(false)
const lists = ref<{ id: number; title: string; code: string }[]>([])
const schools = ref<{ id: number; name: string }[]>([])
const form = reactive({
  title: '',
  description: '',
  listId: null as number | null,
  range: null as [string, string] | null,
  published: false,
  scope: 'SCHOOL' as 'SCHOOL' | 'PUBLIC',
  schoolId: null as number | null
})
const SCOPES = [
  { value: 'SCHOOL' as const, label: '指定学校', description: '其他学校的同学看不到' },
  { value: 'PUBLIC' as const, label: '全站', description: '所有学校都能参加' }
]

let optionsLoaded = false
async function loadOptions() {
  if (optionsLoaded) return
  try {
    const [data, allSchools] = await Promise.all([
      get<{ id: number; title: string; code: string }[] | { items?: { id: number; title: string; code: string }[] }>('/admin/problem-lists'),
      get<{ id: number; name: string }[]>('/schools')
    ])
    lists.value = (Array.isArray(data) ? data : data.items) ?? []
    schools.value = allSchools
    optionsLoaded = true
  } catch {
    lists.value = []
  }
}

/** 新建活动默认面向管理员自己的学校；没有学校时默认第一所 */
function defaultSchoolId() {
  return auth.me?.memberships?.find((m) => m.status === 'APPROVED')?.schoolId ?? schools.value[0]?.id ?? null
}

watch(visible, async (open) => {
  if (!open) return
  await loadOptions()
  const a = props.activity
  form.title = a?.title ?? ''
  form.description = a?.description ?? ''
  form.listId = a?.listId ?? null
  form.range = a ? [a.startAt, a.endAt] : null
  form.published = a?.published ?? false
  form.scope = a?.scope ?? 'SCHOOL'
  form.schoolId = a?.schoolId ?? defaultSchoolId()
})

async function save() {
  if (!form.title.trim()) {
    ElMessage.warning('先填活动标题')
    return
  }
  if (!form.range) {
    ElMessage.warning('先选活动的起止时间')
    return
  }
  saving.value = true
  try {
    const body = {
      title: form.title.trim(),
      description: form.description.trim() || null,
      listId: form.listId,
      startAt: new Date(form.range[0]).toISOString(),
      endAt: new Date(form.range[1]).toISOString(),
      published: form.published,
      scope: form.scope,
      schoolId: form.scope === 'SCHOOL' ? form.schoolId : null
    }
    await post(props.activity ? `/admin/activities/${props.activity.id}` : '/admin/activities', body)
    visible.value = false
    ElMessage.success(props.activity ? '活动已更新' : '活动已发布')
    emit('saved')
  } catch (e) {
    notifyError(e)
  } finally {
    saving.value = false
  }
}
</script>

<template>
  <ResponsiveDialog v-model="visible" :title="activity ? '编辑活动' : '发布活动'" :width="560">
    <el-form label-position="top" @submit.prevent="save">
      <el-form-item label="标题" required>
        <el-input v-model="form.title" maxlength="128" placeholder="例如：周末训练赛 #1" data-test="activity-title" />
      </el-form-item>
      <el-form-item label="说明">
        <el-input v-model="form.description" type="textarea" :rows="3" maxlength="1000" show-word-limit placeholder="训练目标、参与方式、注意事项" />
      </el-form-item>
      <el-form-item label="题单">
        <el-select v-model="form.listId" clearable placeholder="选一个题单" class="w-full">
          <el-option v-for="l in lists" :key="l.id" :label="l.title" :value="l.id" />
        </el-select>
      </el-form-item>
      <el-form-item label="起止时间" required>
        <el-date-picker
          v-model="form.range"
          type="datetimerange"
          value-format="YYYY-MM-DDTHH:mm:ss"
          start-placeholder="开始"
          end-placeholder="结束"
          class="!w-full"
        />
      </el-form-item>
      <el-form-item label="面向">
        <div class="w-full flex flex-col gap-2">
          <RadioCards v-model="form.scope" :options="SCOPES" label="面向" :columns="2" />
          <el-select v-if="form.scope === 'SCHOOL'" v-model="form.schoolId" placeholder="选择学校" class="w-full" aria-label="学校">
            <el-option v-for="school in schools" :key="school.id" :label="school.name" :value="school.id" />
          </el-select>
          <span class="text-xs text-muted">活动榜始终只列看的人自己学校的同学。</span>
        </div>
      </el-form-item>
      <el-form-item>
        <div class="w-full flex items-center justify-between gap-3">
          <span class="flex flex-col"><span class="text-sm text-ink">立即发布</span><span class="text-xs text-muted">没发布的活动只有管理员看得到</span></span>
          <el-switch v-model="form.published" aria-label="立即发布" />
        </div>
      </el-form-item>
    </el-form>
    <template #footer>
      <el-button @click="visible = false">取消</el-button>
      <el-button type="primary" :loading="saving" data-test="activity-save" @click="save">保存</el-button>
    </template>
  </ResponsiveDialog>
</template>
