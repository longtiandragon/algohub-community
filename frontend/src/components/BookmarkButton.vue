<script setup lang="ts">
import { computed, ref } from 'vue'
import { ElMessage } from 'element-plus'
import { del, notifyError, post } from '@/api/client'

type Status = 'LATER' | 'REVIEW' | 'DONE'

const props = defineProps<{
  problemId: number
  /** 当前状态；未收藏传 null。 */
  modelValue: Status | null
  /** 已经通过的题默认建议「待复习」，没通过的默认「稍后再做」。 */
  accepted?: boolean
  /**
   * 未收藏时按钮上的文字。题单详情页本身已经有一个「收藏题单」按钮，
   * 那里要传「标记此题」把两者区分开，不然同一页两个「收藏」指的是不同东西。
   */
  emptyLabel?: string
  /** 长列表里只显示星标，不重复二十遍文字 */
  iconOnly?: boolean
}>()

const emit = defineEmits<{ 'update:modelValue': [Status | null] }>()

const busy = ref(false)

const LABELS: Record<Status, string> = {
  LATER: '稍后再做',
  REVIEW: '待复习',
  DONE: '已过关'
}

const suggested = computed<Status>(() => (props.accepted ? 'REVIEW' : 'LATER'))
const label = computed(() =>
  props.modelValue ? LABELS[props.modelValue] : (props.emptyLabel ?? '收藏')
)

async function mark(status: Status) {
  if (busy.value) return
  busy.value = true
  try {
    await post('/bookmarks', { problemId: props.problemId, status })
    emit('update:modelValue', status)
    ElMessage.success(`已标记为「${LABELS[status]}」`)
  } catch (e) {
    notifyError(e)
  } finally {
    busy.value = false
  }
}

async function clear() {
  if (busy.value) return
  busy.value = true
  try {
    await del(`/bookmarks/${props.problemId}`)
    emit('update:modelValue', null)
    ElMessage.success('已取消收藏，通过记录不受影响')
  } catch (e) {
    notifyError(e)
  } finally {
    busy.value = false
  }
}

function onCommand(command: Status | 'CLEAR') {
  if (command === 'CLEAR') clear()
  else mark(command)
}
</script>

<template>
  <el-dropdown trigger="click" @command="onCommand">
    <el-button
      link
      size="small"
      :loading="busy"
      :type="modelValue ? 'primary' : 'default'"
      class="mark-btn"
      :class="{ 'is-icon': iconOnly }"
      :title="iconOnly ? (modelValue ? label : '收藏这道题') : undefined"
    >
      <template v-if="iconOnly">{{ modelValue ? '★' : '☆' }}</template>
      <template v-else>{{ modelValue ? '★' : '☆' }} {{ label }}</template>
    </el-button>
    <template #dropdown>
      <el-dropdown-menu>
        <el-dropdown-item command="LATER" :disabled="modelValue === 'LATER'">
          稍后再做<span v-if="suggested === 'LATER'" class="mark-hint">（推荐）</span>
        </el-dropdown-item>
        <el-dropdown-item command="REVIEW" :disabled="modelValue === 'REVIEW'">
          待复习<span v-if="suggested === 'REVIEW'" class="mark-hint">（推荐）</span>
        </el-dropdown-item>
        <el-dropdown-item command="DONE" :disabled="modelValue === 'DONE'">
          自己认为过关
        </el-dropdown-item>
        <el-dropdown-item v-if="modelValue" command="CLEAR" divided>取消收藏</el-dropdown-item>
      </el-dropdown-menu>
    </template>
  </el-dropdown>
</template>

<style scoped>
.mark-btn {
  font-size: 12.5px;
  white-space: nowrap;
}

.mark-btn.is-icon {
  font-size: 15px;
  padding: 0;
  min-height: 20px;
}

.mark-hint {
  margin-left: 2px;
  font-size: 12px;
  opacity: 0.65;
}
</style>
