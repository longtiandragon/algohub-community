<script setup lang="ts">
import { reactive } from 'vue'
import { ElMessage } from 'element-plus'
import { del, notifyError, post } from '@/api/client'
import type { NavResource } from '@/api/types'
import ResponsiveList from '@/components/ui/ResponsiveList.vue'
import SectionCard from '@/components/ui/SectionCard.vue'
import StatusBadge from '@/components/ui/StatusBadge.vue'
import type { ListColumn } from '@/components/ui/types'
import { httpUrl } from '@/utils/url'

/** 导航维护：新增、编辑、删除导航资源（分类、标题、链接、说明、排序、启用）。改完通知页面重新读。 */
defineProps<{ items: NavResource[]; loading?: boolean }>()
const emit = defineEmits<{ changed: [] }>()

const EMPTY: NavResource = { id: 0, category: '', title: '', url: '', description: '', sortOrder: 100, enabled: true }
const form = reactive<NavResource>({ ...EMPTY })

const state = reactive({ saving: false })
async function save() {
  if (state.saving) return
  state.saving = true
  try {
    if (form.id) await post(`/admin/nav/${form.id}`, form)
    else await post('/admin/nav', form)
    ElMessage.success('已保存')
    Object.assign(form, EMPTY)
    emit('changed')
  } catch (e) {
    notifyError(e)
  } finally {
    state.saving = false
  }
}
function edit(row: NavResource) {
  Object.assign(form, row)
}
async function remove(id: number) {
  try {
    await del(`/admin/nav/${id}`)
    emit('changed')
  } catch (e) {
    notifyError(e)
  }
}
const columns: ListColumn[] = [
  { key: 'category', label: '分类', width: 120 },
  { key: 'title', label: '标题', minWidth: 150 },
  { key: 'url', label: '链接', minWidth: 250 },
  { key: 'sortOrder', label: '排序', width: 80, align: 'right' },
  { key: 'enabled', label: '状态', width: 90 },
  { key: 'actions', label: '操作', width: 130 }
]
</script>

<template>
  <div class="flex flex-col gap-4" data-test="admin-nav">
    <SectionCard :title="form.id ? '编辑导航项' : '新增导航项'">
      <el-form label-position="top" class="grid grid-cols-1 gap-x-3 md:grid-cols-2 xl:grid-cols-3" @submit.prevent="save">
        <el-form-item label="分类"><el-input v-model="form.category" /></el-form-item>
        <el-form-item label="标题"><el-input v-model="form.title" /></el-form-item>
        <el-form-item label="链接"><el-input v-model="form.url" placeholder="https://" /></el-form-item>
        <el-form-item label="说明" class="md:col-span-2"><el-input v-model="form.description" /></el-form-item>
        <el-form-item label="排序"><el-input-number v-model="form.sortOrder" :min="1" :max="999" class="!w-full" /></el-form-item>
        <el-form-item label="启用"><el-switch v-model="form.enabled" /></el-form-item>
        <div class="flex items-end justify-end gap-2 pb-[18px] md:col-span-2 xl:col-span-2">
          <el-button type="primary" native-type="submit" :loading="state.saving">{{ form.id ? '更新' : '新增' }}</el-button>
          <el-button v-if="form.id" @click="Object.assign(form, EMPTY)">取消编辑</el-button>
        </div>
      </el-form>
    </SectionCard>
    <SectionCard title="导航列表" :description="`共 ${items.length} 条`">
      <ResponsiveList :items="items" :columns="columns" row-key="id" :loading="loading" empty-text="还没有导航项">
        <template #cell-url="{ row }"><a v-if="httpUrl(row.url)" :href="httpUrl(row.url)" target="_blank" rel="noopener noreferrer" class="line-clamp-1 break-all text-[13px]">{{ row.url }}</a><span v-else class="line-clamp-1 break-all text-[13px] text-danger-ink">{{ row.url }}（不是 http 链接）</span></template>
        <template #cell-enabled="{ row }"><StatusBadge variant="label" :tone="row.enabled ? 'success' : 'neutral'">{{ row.enabled ? '启用' : '停用' }}</StatusBadge></template>
        <template #cell-actions="{ row }">
          <el-button size="small" text @click="edit(row as NavResource)">编辑</el-button>
          <el-button size="small" text type="danger" @click="remove(row.id)">删除</el-button>
        </template>
        <template #card="{ row }">
          <div class="flex flex-col gap-1.5">
            <div class="flex items-center gap-2">
              <span class="flex-1 text-sm text-ink">{{ row.title }} <span class="text-xs text-muted">· {{ row.category }}</span></span>
              <StatusBadge variant="label" :tone="row.enabled ? 'success' : 'neutral'">{{ row.enabled ? '启用' : '停用' }}</StatusBadge>
            </div>
            <a v-if="httpUrl(row.url)" :href="httpUrl(row.url)" target="_blank" rel="noopener noreferrer" class="break-all text-xs">{{ row.url }}</a>
            <span v-else class="break-all text-xs text-danger-ink">{{ row.url }}（不是 http 链接）</span>
            <div class="flex gap-2">
              <el-button size="small" @click="edit(row as NavResource)">编辑</el-button>
              <el-button size="small" type="danger" plain @click="remove(row.id)">删除</el-button>
            </div>
          </div>
        </template>
      </ResponsiveList>
    </SectionCard>
  </div>
</template>
