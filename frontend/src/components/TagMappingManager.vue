<script setup lang="ts">
import { platformName } from '@/utils/platformBrand'
import { computed, onMounted, ref } from 'vue'
import { ElMessage } from 'element-plus'
import { get, post, del, notifyError } from '@/api/client'

interface TagRow { platform: string; tagRaw: string; category: string | null; problemCount: number }
const rows = ref<TagRow[]>([])
const loading = ref(false)
const saving = ref(false)
const query = ref('')
const platform = ref('')
const unmappedOnly = ref(false)
const edit = ref<TagRow | null>(null)
const category = ref('')
const platforms = computed(() => [...new Set(rows.value.map(r => r.platform))])
const categories = computed(() => [...new Set(rows.value.map(r => r.category).filter((v): v is string => !!v))].sort())
const filtered = computed(() => rows.value.filter(r =>
  (!platform.value || r.platform === platform.value) &&
  (!unmappedOnly.value || !r.category || r.category === 'UNMAPPED') &&
  `${r.tagRaw} ${r.category ?? ''}`.toLowerCase().includes(query.value.trim().toLowerCase())
))
const unmappedCount = computed(() => rows.value.filter(r => !r.category || r.category === 'UNMAPPED').length)
async function load() {
  loading.value = true
  try { rows.value = await get<TagRow[]>('/admin/tag-mappings') }
  catch (e) { notifyError(e) }
  finally { loading.value = false }
}
function open(row: TagRow) { edit.value = { ...row }; category.value = row.category ?? '' }
async function save() {
  if (!edit.value || !category.value.trim()) return
  saving.value = true
  try {
    await post('/admin/tag-mappings', { platform: edit.value.platform, tagRaw: edit.value.tagRaw, category: category.value.trim() })
    edit.value = null
    ElMessage.success('分类已保存，重新打开个人档案即可查看更新')
    await load()
  } catch (e) { notifyError(e) }
  finally { saving.value = false }
}
async function remove() {
  if (!edit.value || saving.value) return
  saving.value = true
  try {
    const params = new URLSearchParams({ platform: edit.value.platform, tagRaw: edit.value.tagRaw })
    await del(`/admin/tag-mappings?${params}`)
    edit.value = null
    ElMessage.success('已取消归类，题目的原始标签保持不变')
    await load()
  } catch (e) { notifyError(e) }
  finally { saving.value = false }
}
onMounted(load)
</script>

<template>
  <section v-loading="loading">
    <div class="stat-grid">
      <div class="stat-card">
        <div class="stat-label">原始标签总数</div>
        <div class="stat-value">{{ rows.length }}</div>
        <div class="stat-foot">来自各平台题目的原始标签</div>
      </div>
      <div class="stat-card">
        <div class="stat-label">未归类</div>
        <div class="stat-value">{{ unmappedCount }}</div>
        <div class="stat-foot">尚未并入统一分类</div>
      </div>
      <div class="stat-card">
        <div class="stat-label">统一分类数</div>
        <div class="stat-value">{{ categories.filter(c => c !== 'UNMAPPED').length }}</div>
        <div class="stat-foot">当前已建立的分类</div>
      </div>
    </div>

    <div class="card">
      <div class="card-head">
        <span class="card-title">标签归类</span>
        <span class="badge-soft">{{ filtered.length }} 个标签</span>
      </div>

      <p class="muted">
        将原始标签归入统一分类；同一题命中同一分类只计一次。保留原始标签，不改变通过题数。打开归类窗口可取消已有归类。
      </p>

      <div class="filter-bar">
        <el-input
          v-model="query"
          placeholder="搜索标签或分类"
          clearable
          aria-label="搜索标签或分类"
          class="filter-input"
        />
        <el-select
          v-model="platform"
          placeholder="全部平台"
          clearable
          aria-label="标签平台"
          class="filter-select"
        >
          <el-option v-for="p in platforms" :key="p" :value="p" :label="p" />
        </el-select>
        <el-checkbox v-model="unmappedOnly">只看未归类</el-checkbox>
        <el-button @click="load">刷新</el-button>
      </div>

      <div class="table-scroll">
        <el-table :data="filtered" size="small" max-height="560" empty-text="没有匹配的标签">
          <el-table-column label="平台" min-width="140"><template #default="{ row }">{{ platformName(row.platform) }}</template></el-table-column>
          <el-table-column prop="tagRaw" label="原始标签" min-width="200" show-overflow-tooltip />
          <el-table-column label="统一分类" min-width="140">
            <template #default="{ row }">
              <el-tag v-if="row.category && row.category !== 'UNMAPPED'" size="small" type="success">
                {{ row.category }}
              </el-tag>
              <span v-else class="muted">未归类</span>
            </template>
          </el-table-column>
          <el-table-column prop="problemCount" label="已收录题目数" width="130" />
          <el-table-column label="操作" width="90" fixed="right">
            <template #default="{ row }"><el-button text type="primary" @click="open(row as TagRow)">归类</el-button></template>
          </el-table-column>
        </el-table>
      </div>
    </div>

    <el-dialog :model-value="!!edit" title="标签归类" width="min(440px, 92vw)" :close-on-click-modal="!saving" :show-close="!saving" :close-on-press-escape="!saving" @close="edit = null">
      <p class="dialog-subject">{{ edit?.platform }} · {{ edit?.tagRaw }}</p>
      <el-select v-model="category" filterable allow-create default-first-option placeholder="选择或输入统一分类" aria-label="统一分类" style="width: 100%">
        <el-option label="未归类" value="UNMAPPED" />
        <el-option v-for="c in categories.filter(c => c !== 'UNMAPPED')" :key="c" :label="c" :value="c" />
      </el-select>
      <p class="muted" style="margin-bottom: 0">归类只影响分类统计口径，不会修改原始标签，也不改变通过题数。</p>
      <template #footer>
        <el-button v-if="edit?.category" type="danger" plain :disabled="saving" @click="remove">取消归类</el-button>
        <el-button :disabled="saving" @click="edit = null">取消</el-button>
        <el-button type="primary" :loading="saving" :disabled="!category.trim() || category.trim().length > 64" @click="save">保存分类</el-button>
      </template>
    </el-dialog>
  </section>
</template>

<style scoped>
.filter-bar {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  gap: 10px;
  margin: 12px 0 14px;
  padding-top: 12px;
  border-top: 1px solid var(--border);
}

.filter-input {
  width: 220px;
  max-width: 100%;
}

.filter-select {
  width: 160px;
  max-width: 100%;
}

.dialog-subject {
  margin: 0 0 12px;
  font-weight: 600;
  color: var(--text-strong);
  word-break: break-all;
}

@media (max-width: 520px) {
  .filter-input,
  .filter-select {
    width: 100%;
  }
}
</style>
