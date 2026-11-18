<script setup lang="ts">
import { computed, onBeforeUnmount, ref, watch } from 'vue'
import { ElMessage } from 'element-plus'
import { notifyError } from '@/api/client'
import { previewContest, publishContest, weekMonday, type ContestPreview, type Targets } from '@/api/weeklyTraining'
import { platformName } from '@/utils/platformBrand'
import ResponsiveDialog from '@/components/ui/ResponsiveDialog.vue'

const props = defineProps<{ school: number }>()
const emit = defineEmits<{ close: []; published: [] }>()
const url = ref(''), date = ref(weekMonday()), preview = ref<ContestPreview>(), targets = ref<Targets>({})
const busy = ref(false), saving = ref(false)
let request = 0
onBeforeUnmount(() => { request++ })
watch(url, () => { request++; busy.value = false; preview.value = undefined; targets.value = {} }, { flush: 'sync' })
const selected = computed(() => Object.entries(targets.value))
const valid = computed(() => preview.value && date.value && selected.value.length > 0 &&
  selected.value.every(([, n]) => Number.isInteger(n) && n > 0 && n <= preview.value!.meta.tasks.length))

async function identify() {
  if (!url.value.trim() || busy.value || saving.value) return
  const token = ++request
  busy.value = true
  try {
    const data = await previewContest(props.school, url.value.trim())
    if (token !== request) return
    preview.value = data
    targets.value = Object.fromEntries(data.years.filter(y => y in data.targets).map(y => [y, data.targets[y]]))
  } catch (error) {
    if (token === request) notifyError(error)
  } finally {
    if (token === request) busy.value = false
  }
}
function toggle(year: number, checked: boolean) {
  if (checked) targets.value[year] = 1
  else delete targets.value[year]
}
async function publish() {
  if (!valid.value || saving.value) return
  const token = request
  saving.value = true
  try {
    await publishContest(props.school, url.value.trim(), { ...targets.value }, weekMonday(date.value))
    if (token === request) { ElMessage.success('训练赛已发布'); emit('published') }
  } catch (error) {
    if (token === request) notifyError(error)
  } finally {
    if (token === request) saving.value = false
  }
}
</script>
<template>
  <ResponsiveDialog :model-value="true" title="布置训练赛" @update:model-value="open => { if (!open && !saving) emit('close') }">
    <form @submit.prevent="preview ? publish() : identify()">
      <label for="weekly-contest-url" class="mb-2 block font-semibold">比赛链接</label>
      <el-input id="weekly-contest-url" v-model="url" :disabled="saving" placeholder="粘贴 Codeforces、AtCoder 或牛客比赛链接" />
      <div v-if="preview" class="mt-4">
        <div class="rounded-lg bg-surface-2 p-3">
          <strong>{{ preview.meta.title }}</strong>
          <div class="mt-1 text-sm text-muted">{{ platformName(preview.meta.platform) }} · {{ preview.meta.category }} · {{ preview.meta.tasks.length }} 题</div>
        </div>
        <label for="weekly-date" class="mb-2 mt-4 block font-semibold">训练周</label>
        <el-date-picker id="weekly-date" v-model="date" :disabled="saving" type="date" value-format="YYYY-MM-DD" :clearable="false" aria-label="选择训练周内的一天" />
        <div v-if="date" class="mt-1 text-sm text-muted">{{ weekMonday(date) }} 周一开始</div>
        <fieldset class="m-0 mt-4 border-0 p-0">
          <legend class="font-semibold">各年级任选题数</legend>
          <p v-if="!preview.years.length" class="text-muted">暂无可布置的年级</p>
          <div v-for="year in preview.years" :key="year" class="flex min-h-11 items-center justify-between gap-2 border-b border-line">
            <el-checkbox :model-value="year in targets" :disabled="saving" @change="value => toggle(year, !!value)">{{ year }} 级</el-checkbox>
            <el-input-number v-if="year in targets" v-model="targets[year]" :disabled="saving" :aria-label="year + ' 级目标题数'" :min="1" :max="preview.meta.tasks.length" :step="1" step-strictly />
          </div>
        </fieldset>
      </div>
    </form>
    <template #footer>
      <el-button :disabled="saving" @click="emit('close')">取消</el-button>
      <el-button v-if="!preview" type="primary" :loading="busy" :disabled="!url.trim()" @click="identify">识别比赛</el-button>
      <el-button v-else type="primary" :loading="saving" :disabled="!valid" @click="publish">发布训练赛</el-button>
    </template>
  </ResponsiveDialog>
</template>
