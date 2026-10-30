<script setup lang="ts">
import { computed, reactive, ref, watch } from 'vue'
import { ElMessage } from 'element-plus'
import { get, notifyError, post } from '@/api/client'
import RadioCards from '@/components/ui/RadioCards.vue'
import ResponsiveDialog from '@/components/ui/ResponsiveDialog.vue'
import { LIST_PLATFORM_OPTIONS, SHARE_SCOPE_OPTIONS, trimmed, type ProblemList, type ShareScope } from './types'

/**
 * 按规则生成：从已同步题库里按平台、难度区间、分类筛一份，按平台公开通过人数排序。
 * 分类选项来自「练习较少的分类」接口；题库没同步时接口取不到，退化成手填。
 */
const visible = defineModel<boolean>({ required: true })
const props = defineProps<{ management?: boolean }>()
const scopeOptions = computed(() => props.management ? SHARE_SCOPE_OPTIONS.filter(o => o.value !== 'PRIVATE') : SHARE_SCOPE_OPTIONS)
const emit = defineEmits<{ created: [] }>()

interface WeakCategoryResponse {
  items: { category: string; catalogCount: number; solvedCount: number }[]
  note: string
}
interface GenerateResult {
  list: ProblemList
  added: number
  rule: string
  note: string
}

const saving = ref(false)
const categories = ref<string[]>([])
const categoryLoading = ref(false)
const categoryFallback = ref(false)
const form = reactive({
  title: '',
  platform: 'CODEFORCES',
  minDifficulty: 800,
  maxDifficulty: 1200,
  size: 30,
  tagCategory: '',
  shareScope: 'PRIVATE' as ShareScope,
  schoolId: undefined as number | undefined
})

async function loadCategories() {
  categoryLoading.value = true
  try {
    const data = await get<WeakCategoryResponse>('/recommendations/weak-categories', { platform: form.platform, limit: 8 })
    categories.value = data.items.map((item) => item.category)
    categoryFallback.value = categories.value.length === 0
  } catch {
    // 题库没同步时该接口返回 404，这里静默退化成手填，不弹错误
    categories.value = []
    categoryFallback.value = true
  } finally {
    categoryLoading.value = false
  }
}

watch(visible, (open) => {
  if (!open) return
  form.title = ''
  form.tagCategory = ''
  form.shareScope = props.management ? 'PUBLIC' : 'PRIVATE'
  form.schoolId = undefined
  void loadCategories()
})

// 换平台后可选分类会变，重新拉一次；已选分类清空，免得带上别的平台的分类
watch(
  () => form.platform,
  () => {
    if (!visible.value) return
    form.tagCategory = ''
    void loadCategories()
  }
)

async function submit() {
  if (form.minDifficulty > form.maxDifficulty) {
    ElMessage.warning('难度下限不能大于上限')
    return
  }
  saving.value = true
  try {
    const result = await post<GenerateResult>(`${props.management ? '/admin/problem-lists' : '/problem-lists'}/generate`, {
      title: trimmed(form.title),
      platform: form.platform,
      minDifficulty: form.minDifficulty,
      maxDifficulty: form.maxDifficulty,
      size: form.size,
      tagCategory: trimmed(form.tagCategory),
      shareScope: form.shareScope,
      ...(props.management ? { schoolId: form.schoolId } : {})
    })
    visible.value = false
    ElMessage.success(`已生成题单，收录 ${result.added} 题`)
    emit('created')
  } catch (e) {
    notifyError(e)
  } finally {
    saving.value = false
  }
}
</script>

<template>
  <ResponsiveDialog v-model="visible" title="按规则生成题单" :width="520">
    <el-form label-position="top" @submit.prevent="submit">
      <el-form-item label="标题">
        <el-input v-model="form.title" maxlength="80" placeholder="不填就按平台和难度自动命名" />
      </el-form-item>
      <div class="grid grid-cols-1 gap-x-3 md:grid-cols-2">
        <el-form-item label="平台">
          <el-select v-model="form.platform" class="w-full">
            <el-option v-for="p in LIST_PLATFORM_OPTIONS" :key="p.value" :label="p.label" :value="p.value" />
          </el-select>
        </el-form-item>
        <el-form-item label="分类">
          <el-input v-if="categoryFallback" v-model="form.tagCategory" maxlength="30" placeholder="不填表示不限，例如：动态规划" />
          <el-select v-else v-model="form.tagCategory" :loading="categoryLoading" class="w-full">
            <el-option label="不限分类" value="" />
            <el-option v-for="c in categories" :key="c" :label="c" :value="c" />
          </el-select>
        </el-form-item>
      </div>
      <el-form-item label="难度区间">
        <div class="flex flex-wrap items-center gap-2">
          <el-input-number v-model="form.minDifficulty" :min="0" :max="5000" :step="100" controls-position="right" aria-label="难度下限" />
          <span class="text-muted">至</span>
          <el-input-number v-model="form.maxDifficulty" :min="0" :max="5000" :step="100" controls-position="right" aria-label="难度上限" />
        </div>
      </el-form-item>
      <el-form-item label="题目数量">
        <el-input-number v-model="form.size" :min="1" :max="200" :step="10" controls-position="right" aria-label="题目数量" />
      </el-form-item>
      <el-form-item label="谁能看到">
        <RadioCards v-model="form.shareScope" :options="scopeOptions" label="谁能看到" class="w-full" />
      </el-form-item>
      <el-form-item v-if="management && form.shareScope === 'SCHOOL'" label="学校编号" required>
        <el-input-number v-model="form.schoolId" :min="1" :precision="0" />
      </el-form-item>
    </el-form>
    <p class="m-0 text-[13px] text-muted leading-5">
      从已同步题库里按规则筛，按平台公开通过人数从多到少排，不是人工挑的；题库同步结果变了，题目也会跟着变。
    </p>
    <template #footer>
      <el-button @click="visible = false">取消</el-button>
      <el-button type="primary" :loading="saving" @click="submit">生成</el-button>
    </template>
  </ResponsiveDialog>
</template>
