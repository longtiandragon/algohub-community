<script setup lang="ts">
import { computed, reactive, ref, watch } from 'vue'
import { ElMessage } from 'element-plus'
import { notifyError, post } from '@/api/client'
import RadioCards from '@/components/ui/RadioCards.vue'
import ResponsiveDialog from '@/components/ui/ResponsiveDialog.vue'
import { problemCodeBare } from '@/utils/problemLabel'
import { campusDate, shiftDate } from '@/utils/statistics'
import type { BookmarkRow, BookmarkStatus } from './types'

/** 编辑一条收藏：我的标记、复习日期（带「明天 / 3 天后 / 一周后」快捷）、笔记。 */
const visible = defineModel<boolean>({ required: true })
const props = defineProps<{ row: BookmarkRow | null }>()
const emit = defineEmits<{ saved: [] }>()

const STATUSES: { value: BookmarkStatus; label: string; description: string }[] = [
  { value: 'REVIEW', label: '待复习', description: '做过了，过几天再做一遍' },
  { value: 'LATER', label: '稍后再做', description: '还没做，先存着' },
  { value: 'DONE', label: '已过关', description: '不用再看了；不会变成通过记录' }
]
const QUICK = [
  { label: '明天', days: 1 },
  { label: '3 天后', days: 3 },
  { label: '一周后', days: 7 }
]

const saving = ref(false)
const form = reactive({ status: 'LATER' as BookmarkStatus, note: '', remindOn: null as string | null })
const title = computed(() => {
  if (!props.row) return ''
  const { code, name } = problemCodeBare(props.row)
  return `${code} ${name}`.trim()
})

watch(visible, (open) => {
  if (!open || !props.row) return
  form.status = props.row.status
  form.note = props.row.note ?? ''
  form.remindOn = props.row.remindOn?.slice(0, 10) ?? null
})

function quick(days: number) {
  form.remindOn = shiftDate(campusDate(), days)
}

async function save() {
  if (!props.row) return
  saving.value = true
  try {
    await post('/bookmarks', { problemId: props.row.problemId, status: form.status, note: form.note.trim() || null, remindOn: form.remindOn || null })
    visible.value = false
    ElMessage.success('已保存')
    emit('saved')
  } catch (e) {
    notifyError(e)
  } finally {
    saving.value = false
  }
}
</script>

<template>
  <ResponsiveDialog v-model="visible" title="编辑收藏" :width="560">
    <p class="mb-3 mt-0 text-sm text-ink font-semibold">{{ title }}</p>
    <el-form label-position="top" @submit.prevent="save">
      <el-form-item label="我的标记">
        <RadioCards v-model="form.status" :options="STATUSES" label="我的标记" class="w-full" />
      </el-form-item>
      <el-form-item label="复习日期">
        <div class="w-full flex flex-wrap items-center gap-2">
          <el-date-picker v-model="form.remindOn" type="date" value-format="YYYY-MM-DD" placeholder="不设" class="!w-[160px]" aria-label="复习日期" data-test="remind-date" />
          <button v-for="q in QUICK" :key="q.days" type="button" class="filter-chip" data-test="remind-quick" @click="quick(q.days)">{{ q.label }}</button>
          <button v-if="form.remindOn" type="button" class="filter-chip" @click="form.remindOn = null">不设</button>
        </div>
      </el-form-item>
      <el-form-item label="笔记">
        <el-input v-model="form.note" type="textarea" :rows="4" maxlength="500" show-word-limit placeholder="卡在哪、用了什么思路，只有你自己能看到" data-test="note" />
      </el-form-item>
    </el-form>
    <template #footer>
      <el-button @click="visible = false">取消</el-button>
      <el-button type="primary" :loading="saving" data-test="bookmark-save" @click="save">保存</el-button>
    </template>
  </ResponsiveDialog>
</template>
