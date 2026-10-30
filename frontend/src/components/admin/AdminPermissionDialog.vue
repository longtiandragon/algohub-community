<script setup lang="ts">
import { computed, ref, watch } from 'vue'
import { ElMessage } from 'element-plus'
import { notifyError } from '@/api/client'
import { permissionCatalog, saveAdmin, type AdminPermission } from '@/api/adminUsers'
import RadioCards from '@/components/ui/RadioCards.vue'
import ResponsiveDialog from '@/components/ui/ResponsiveDialog.vue'

/**
 * 设为平台管理员 / 调整权限：全部权限（超级管理员），或者只勾选几项。用户管理的详情和「管理员与权限」共用。
 * 新管理员还没有密码时提醒一句：管理员从单独的入口用密码登录，要先通过登录邮箱验证设置密码。
 */
const props = defineProps<{
  modelValue: boolean
  user: { id: number; displayName: string; username: string; hasPassword?: boolean } | null
  /** 已经是管理员时的权限（null 为全部）；undefined 表示这是把学生设为平台管理员 */
  permissions?: AdminPermission[] | null
}>()
const emit = defineEmits<{ 'update:modelValue': [value: boolean]; saved: [] }>()

const promoting = computed(() => props.permissions === undefined)
const scope = ref<'full' | 'custom'>('full')
const picked = ref<AdminPermission[]>([])
const saving = ref(false)
const catalog = computed(() => permissionCatalog())

watch(
  () => props.modelValue,
  (open) => {
    if (!open) return
    scope.value = props.permissions && props.permissions.length ? 'custom' : 'full'
    picked.value = props.permissions ? [...props.permissions] : []
  },
  { immediate: true }
)

const SCOPES = [
  { value: 'full' as const, label: '全部权限', icon: 'i-lucide-shield-check', description: '能做所有管理操作，包括添加、取消其他平台管理员和学校管理员' },
  { value: 'custom' as const, label: '部分权限', icon: 'i-lucide-list-checks', description: '只开放勾选的几项，比如只管学校和成员' }
]

function toggle(code: AdminPermission, on: boolean) {
  picked.value = on ? [...new Set([...picked.value, code])] : picked.value.filter((c) => c !== code)
}

async function save() {
  if (!props.user || saving.value) return
  if (scope.value === 'custom' && !picked.value.length) {
    ElMessage.warning('至少勾选一项权限')
    return
  }
  saving.value = true
  try {
    // 按目录顺序提交，便于对比
    const permissions = scope.value === 'full' ? null : catalog.value.map((p) => p.code).filter((c) => picked.value.includes(c))
    await saveAdmin(props.user.id, 'ADMIN', permissions)
    ElMessage.success(promoting.value ? `已把 ${props.user.displayName} 设为平台管理员` : '权限已更新')
    emit('saved')
    emit('update:modelValue', false)
  } catch (e) {
    notifyError(e)
  } finally {
    saving.value = false
  }
}
</script>

<template>
  <ResponsiveDialog
    :model-value="modelValue"
    :title="promoting ? '设为平台管理员' : '调整权限'"
    :width="560"
    @update:model-value="emit('update:modelValue', $event)"
  >
    <div v-if="user" class="flex flex-col gap-4" data-test="admin-permission-dialog">
      <p class="m-0 text-[13.5px] text-body leading-[1.6]">
        <span class="font-semibold text-ink">{{ user.displayName }}</span>
        <span class="ml-1.5 text-xs text-muted">{{ user.username }}</span>
      </p>
      <RadioCards v-model="scope" :options="SCOPES" :columns="2" label="权限范围" />
      <fieldset v-if="scope === 'custom'" class="m-0 border-0 p-0">
        <legend class="mb-2 p-0 text-[13px] text-muted">勾选要开放的权限</legend>
        <div class="grid grid-cols-1 gap-2 md:grid-cols-2">
          <el-checkbox
            v-for="item in catalog"
            :key="item.code"
            class="perm"
            :class="{ 'is-active': picked.includes(item.code) }"
            :model-value="picked.includes(item.code)"
            :data-test="`perm-${item.code}`"
            @update:model-value="toggle(item.code, Boolean($event))"
          >
            <span class="text-[13.5px] text-ink font-semibold leading-5">{{ item.name }}</span>
            <span class="text-xs text-muted leading-[1.5]">{{ item.description }}</span>
          </el-checkbox>
        </div>
      </fieldset>
      <p v-if="promoting && user.hasPassword !== true" class="note m-0">
        <span class="i-lucide-key-round mt-0.5 shrink-0 text-base" aria-hidden="true" />
        <span>
          平台管理员从单独的管理员入口用密码登录。{{ user.hasPassword === false ? `${user.displayName} 还没有密码：` : `如果 ${user.displayName} 还没有设置过密码，` }}请他用平时的方式登录后，到「设置与安全 → 登录邮箱」通过邮箱验证设置密码。
        </span>
      </p>
    </div>
    <template #footer>
      <el-button @click="emit('update:modelValue', false)">取消</el-button>
      <el-button type="primary" :loading="saving" data-test="admin-permission-save" @click="save">{{ promoting ? '设为平台管理员' : '保存' }}</el-button>
    </template>
  </ResponsiveDialog>
</template>

<style scoped>
/* 整张卡片就是一个复选框：勾选框在左上，右边两行字 */
.perm.el-checkbox {
  display: flex;
  align-items: flex-start;
  height: auto;
  margin: 0;
  padding: 10px 12px;
  border: 1px solid var(--border);
  border-radius: 12px;
  white-space: normal;
}

.perm.el-checkbox:hover {
  border-color: var(--border-strong);
}

.perm.el-checkbox.is-active {
  border-color: color-mix(in srgb, var(--brand-500) 45%, var(--border));
  background: color-mix(in srgb, var(--brand-soft) 45%, var(--bg-elevated));
}

.perm :deep(.el-checkbox__input) {
  margin-top: 3px;
}

.perm :deep(.el-checkbox__label) {
  display: flex;
  flex-direction: column;
  gap: 2px;
  min-width: 0;
  padding-left: 10px;
}

.note {
  display: flex;
  gap: 8px;
  padding: 10px 12px;
  border-radius: 12px;
  background: var(--info-soft);
  color: var(--info-ink);
  font-size: 13px;
  line-height: 1.6;
}
</style>
