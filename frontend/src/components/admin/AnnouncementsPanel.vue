<script setup lang="ts">
import { computed, onMounted, ref, watch } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { get, post, del, notifyError } from '@/api/client'
import AnnouncementContent from '@/components/home/AnnouncementContent.vue'
import { dayTime } from '@/utils/statistics'
import SectionCard from '@/components/ui/SectionCard.vue'

interface Notice { version?: string | null; id: number; title: string; content: string; published: boolean; updatedAt: string; publishedAt?: string; items?: string[]; kind: string; url: string | null }
const items = ref<Notice[]>([])
const loading = ref(false)
const saving = ref(false)
const open = ref(false)
const id = ref<number | null>(null)
const form = ref({ version: '', title: '', content: '', published: false, kind: 'UPDATE', url: '', items: [''] })
const query = ref(''), kind = ref(''), status = ref(''), page = ref(1)
const filtered = computed(() => items.value.filter(i => (!kind.value || i.kind === kind.value) && (!status.value || String(i.published) === status.value) && `${i.title} ${i.content}`.toLowerCase().includes(query.value.trim().toLowerCase())))
const paged = computed(() => filtered.value.slice((page.value-1)*10,page.value*10))
watch([query,kind,status],()=>{page.value=1})
async function load() {
  loading.value = true
  try { items.value = await get<Notice[]>('/admin/announcements'); page.value=Math.min(page.value,Math.max(1,Math.ceil(filtered.value.length/10))) } catch (e) { notifyError(e) }
  finally { loading.value = false }
}
function edit(item?: Notice) {
  id.value = item?.id ?? null
  form.value = { version: item?.version ?? '', title: item?.title ?? '', content: item?.content ?? '', published: item?.published ?? false, kind: item?.kind ?? 'UPDATE', url: item?.url ?? '', items: item?.items?.length ? [...item.items] : [''] }
  open.value = true
}
async function save() {
  if (!form.value.title.trim()) { ElMessage.warning('请填写标题'); return }
  if (form.value.kind === 'UPDATE') {
    if (!form.value.items.length || form.value.items.some(item => !item.trim())) { ElMessage.warning('请填写每条更新内容，或删除空白条目'); return }
    if (form.value.items.join('').length > 10000) { ElMessage.warning('更新内容总长度不能超过 10000 字'); return }
  } else if (!form.value.content.trim()) { ElMessage.warning('请填写正文或资源说明'); return }
  saving.value = true
  try {
    await post(id.value ? `/admin/announcements/${id.value}` : '/admin/announcements', {
      ...form.value,
      // 更新公告的正文由服务端生成；旧正文含换行，可能比条目总字数更长。
      content: form.value.kind === 'UPDATE' ? '' : form.value.content,
      items: form.value.kind === 'UPDATE' ? form.value.items : [],
    })
    ElMessage.success(form.value.published ? '公告已发布' : '草稿已保存')
    open.value = false
    await load()
  } catch (e) { notifyError(e) } finally { saving.value = false }
}
async function remove(item: Notice) {
  try { await ElMessageBox.confirm(`删除「${item.title}」后无法恢复。`, '删除公告', { type: 'warning' }) } catch { return }
  try { await del(`/admin/announcements/${item.id}`); ElMessage.success('已删除'); await load() } catch (e) { notifyError(e) }
}
const kindLabel = (value: string) => (({ UPDATE: '更新公告', TEXT: '文字公告', LINK: '资源链接' } as Record<string,string>)[value] ?? value)
const dynamicResource = (url: string | null) => !!url && (url.startsWith('https://c16h22o4.github.io/') || url.startsWith('https://hei-maom.github.io/xcpcrating/'))
const previewItems = computed(() => form.value.items.map((item, index) => item.trim() || `第 ${index + 1} 条更新内容`))
function move(index: number, offset: number) {
  const next = index + offset
  if (next < 0 || next >= form.value.items.length) return
  const item = form.value.items.splice(index, 1)[0]!
  form.value.items.splice(next, 0, item)
}
onMounted(load)
</script>

<template>
  <SectionCard title="公告与资源管理" description="由具备内容管理权限的平台管理员发布。更新公告逐条填写，自动排版与记录首次发布日期；草稿仅后台可见。">
    <template #actions><el-button type="primary" @click="edit()">新建内容</el-button></template>
    <div class="mb-4 flex flex-wrap gap-2">
      <el-input v-model="query" class="!w-52" clearable placeholder="搜索标题或正文" aria-label="搜索公告与资源" />
      <el-select v-model="kind" class="!w-36" aria-label="内容类型" placeholder="全部类型"><el-option value="" label="全部类型" /><el-option value="UPDATE" label="更新公告" /><el-option value="TEXT" label="文字公告" /><el-option value="LINK" label="资源链接" /></el-select>
      <el-select v-model="status" class="!w-36" aria-label="发布状态" placeholder="全部状态"><el-option value="" label="全部状态" /><el-option value="true" label="已发布" /><el-option value="false" label="草稿 / 已下架" /></el-select>
    </div>
    <p v-if="loading" role="status">正在读取公告…</p>
    <p v-else-if="!filtered.length" class="text-muted">暂无符合条件的内容。</p>
    <div v-else class="flex flex-col gap-3">
      <article v-for="item in paged" :key="item.id" class="notice">
        <div class="flex flex-wrap items-center gap-2">
          <h3 class="m-0 flex-1 text-base">{{ item.title }}</h3>
          <el-tag v-if="item.version">{{ item.version }}</el-tag>
          <el-tag type="info">{{ kindLabel(item.kind) }}</el-tag>
          <el-tag :type="item.published ? 'success' : 'info'">{{ item.published ? '已发布' : '草稿' }}</el-tag>
          <el-button size="small" @click="edit(item)">查看 / 编辑</el-button>
          <el-button size="small" type="danger" plain @click="remove(item)">删除</el-button>
        </div>
        <div class="my-3"><AnnouncementContent :kind="item.kind" :content="item.content" :items="item.items" /></div>
        <p v-if="item.kind === 'LINK'" class="break-all text-xs text-muted">{{ dynamicResource(item.url) ? '按用户已审核的学校自动生成链接；未绑定学校时隐藏。' : item.url }}</p>
        <time class="text-xs text-muted">{{ item.publishedAt ? `首次发布于 ${dayTime(item.publishedAt)}` : '尚未发布' }} · 编辑于 {{ dayTime(item.updatedAt) }}</time>
      </article>
    </div>
    <el-pagination v-if="filtered.length>10" v-model:current-page="page" :page-size="10" :total="filtered.length" layout="total, prev, pager, next" class="mt-4" />
    <el-dialog v-model="open" :title="id ? '编辑公告或资源' : '新建公告或资源'" width="min(1000px, 94vw)" :close-on-click-modal="false">
      <div class="editor-layout">
        <el-form label-position="top" :disabled="saving" @submit.prevent="save">
          <el-form-item label="内容类型"><el-radio-group v-model="form.kind" :disabled="dynamicResource(form.url)"><el-radio value="UPDATE">更新公告</el-radio><el-radio value="TEXT">文字公告</el-radio><el-radio value="LINK">资源链接</el-radio></el-radio-group></el-form-item>
          <el-form-item label="标题" required><el-input v-model="form.title" aria-label="公告标题" placeholder="例如：10 月功能更新" maxlength="120" show-word-limit /></el-form-item>
          <el-form-item label="版本号（选填）"><el-input v-model="form.version" aria-label="公告版本号" placeholder="例如：v2026.10.30" maxlength="32" /></el-form-item>
          <el-form-item v-if="form.kind==='LINK'" label="链接地址（http / https）" required><el-input v-model="form.url" :disabled="dynamicResource(form.url)" aria-label="资源链接" maxlength="512" placeholder="https://" /><p v-if="dynamicResource(form.url)" class="text-xs text-muted">本校资源会自动使用读者所属学校，无需填写校名。</p></el-form-item>
          <div v-if="form.kind==='UPDATE'">
            <div class="mb-3 flex items-center justify-between"><span class="font-semibold text-ink">更新内容 <span class="text-xs text-muted">{{ form.items.length }} / 30 条</span></span><span class="text-xs text-muted">每条自动编号</span></div>
            <div v-for="(_, index) in form.items" :key="index" class="entry-editor">
              <div class="mb-2 flex items-center justify-between gap-2"><label :for="`update-item-${index}`" class="text-xs font-semibold text-muted">第 {{ index + 1 }} 条</label><div class="flex gap-2">
                <button type="button" class="entry-action" :aria-label="`上移第 ${index+1} 条`" :disabled="saving || index===0" @click="move(index,-1)">上移</button>
                <button type="button" class="entry-action" :aria-label="`下移第 ${index+1} 条`" :disabled="saving || index===form.items.length-1" @click="move(index,1)">下移</button>
                <button type="button" class="entry-action" :aria-label="`删除第 ${index+1} 条`" :disabled="saving || form.items.length===1" @click="form.items.splice(index,1)">删除</button>
              </div></div>
              <el-input :id="`update-item-${index}`" v-model="form.items[index]" :aria-label="`第 ${index+1} 条更新内容`" type="textarea" :autosize="{minRows:2,maxRows:8}" maxlength="1000" show-word-limit placeholder="说明新增功能、改进或修复了什么" />
            </div>
            <el-button :disabled="saving || form.items.length>=30" class="w-full" @click="form.items.push('')">＋ 添加一条更新</el-button>
          </div>
          <el-form-item v-else :label="form.kind==='LINK'?'资源说明':'正文'" required><el-input v-model="form.content" aria-label="正文或资源说明" type="textarea" :rows="form.kind==='LINK'?3:8" maxlength="10000" show-word-limit /></el-form-item>
          <el-form-item label="发布状态" class="mt-5"><el-switch v-model="form.published" active-text="发布给所有用户" inactive-text="保存为草稿" /></el-form-item>
          <p class="text-xs text-muted">发布日期在首次发布时自动生成，后续编辑不会改变发布日期。</p>
        </el-form>
        <aside class="notice-preview" aria-label="公告实时预览">
          <div class="mb-5 flex items-center justify-between text-xs text-muted"><span>读者视角 · 实时预览</span><span>{{ kindLabel(form.kind) }}</span></div>
          <el-tag v-if="form.version" class="mb-2">{{ form.version }}</el-tag>
          <h3 class="m-0 mb-2 text-xl font-semibold text-ink break-words">{{ form.title.trim() || '公告标题' }}</h3>
          <p class="mb-6 mt-0 text-xs text-muted">{{ items.find(item=>item.id===id)?.publishedAt ? dayTime(items.find(item=>item.id===id)!.publishedAt!) : '首次发布时自动显示日期' }}</p>
          <AnnouncementContent :kind="form.kind" :content="form.content || '在左侧填写内容，这里会显示最终排版。'" :items="previewItems" />
          <p v-if="form.kind==='LINK'" class="mt-4 text-xs text-muted break-all">{{ dynamicResource(form.url) ? '按用户学校生成查询链接' : form.url }}</p>
        </aside>
      </div>
      <template #footer><el-button :disabled="saving" @click="open = false">取消</el-button><el-button type="primary" :loading="saving" @click="save">{{ form.published ? '保存并发布' : '保存草稿' }}</el-button></template>
    </el-dialog>
  </SectionCard>
</template>
<style scoped>
.notice { padding: 16px; border: 1px solid var(--border); border-radius: var(--radius); background: var(--bg-subtle); }
.editor-layout { display: grid; grid-template-columns: minmax(0,1.1fr) minmax(0,1fr); gap: 28px; }
.entry-editor { margin-bottom: 12px; }
.entry-action { border: 0; background: none; color: var(--brand-600); cursor: pointer; font-size: 12px; padding: 4px; }
.entry-action:disabled { opacity: .35; cursor: default; }
.notice-preview { min-width: 0; align-self: start; padding: 24px; border: 1px solid var(--border); border-radius: 12px; background: var(--bg-subtle); }
@media (max-width: 760px) { .editor-layout { grid-template-columns: minmax(0,1fr); gap: 20px; } .notice-preview { padding: 18px; } }
</style>
