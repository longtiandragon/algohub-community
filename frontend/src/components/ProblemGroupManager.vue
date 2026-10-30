<script setup lang="ts">
import { platformName } from '@/utils/platformBrand'
import { onMounted, reactive, ref } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { del, get, notifyError, post } from '@/api/client'
import type { PlatformCapability } from '@/api/types'
import { httpUrl } from '@/utils/url'

interface ProblemSearchItem {
  id: number
  platform: string
  problemKey: string
  title: string | null
  url: string | null
  difficultyLabel: string | null
  groupId: number | null
  groupKey: string | null
}

interface GroupMember {
  problemId: number
  platform: string
  problemKey: string
  title: string | null
  url: string | null
  relation: string
  source: string
}

interface Group {
  id: number
  canonicalKey: string
  title: string | null
  note: string | null
  memberCount: number | null
}

interface GroupDetail extends Group {
  members: GroupMember[]
}

const groups = ref<Group[]>([])
const capabilities = ref<PlatformCapability[]>([])
const coverage = ref<{ groupCount: number; mappedProblemCount: number } | null>(null)
const detail = ref<GroupDetail | null>(null)
const searchResults = ref<ProblemSearchItem[]>([])
const loading = ref(false)
const searching = ref(false)

const search = reactive({ platform: '', keyword: '' })
const createForm = reactive({ canonicalKey: '', title: '', note: '' })
const relation = ref('SAME_PROBLEM')

const relationLabels: Record<string, string> = {
  SAME_PROBLEM: '同一题目',
  MIRROR: '搬运 / 镜像',
  TRANSLATED: '翻译版本'
}

async function load() {
  loading.value = true
  try {
    const [g, c, caps] = await Promise.all([
      get<Group[]>('/admin/problem-groups'),
      get<{ groupCount: number; mappedProblemCount: number }>('/admin/problem-groups/coverage'),
      get<PlatformCapability[]>('/platforms/capabilities')
    ])
    groups.value = g
    coverage.value = c
    capabilities.value = caps
  } catch (e) {
    notifyError(e)
  } finally {
    loading.value = false
  }
}

onMounted(load)

async function openGroup(id: number) {
  try {
    detail.value = await get<GroupDetail>(`/admin/problem-groups/${id}`)
  } catch (e) {
    notifyError(e)
  }
}

async function createGroup() {
  if (!createForm.canonicalKey.trim()) {
    ElMessage.warning('请填写规范标识，例如 CF/4A')
    return
  }
  try {
    const created = await post<GroupDetail>('/admin/problem-groups', {
      canonicalKey: createForm.canonicalKey.trim(),
      title: createForm.title.trim() || null,
      note: createForm.note.trim() || null,
      problemIds: []
    })
    ElMessage.success('已创建题目组')
    createForm.canonicalKey = ''
    createForm.title = ''
    createForm.note = ''
    detail.value = created
    await load()
  } catch (e) {
    notifyError(e)
  }
}

async function doSearch() {
  searching.value = true
  try {
    searchResults.value = await get<ProblemSearchItem[]>('/admin/problem-groups/problems', {
      platform: search.platform || undefined,
      keyword: search.keyword.trim() || undefined,
      limit: 50
    })
  } catch (e) {
    notifyError(e)
  } finally {
    searching.value = false
  }
}

async function addToGroup(problem: ProblemSearchItem) {
  if (!detail.value) {
    ElMessage.warning('请先选择或创建一个题目组')
    return
  }
  try {
    detail.value = await post<GroupDetail>(`/admin/problem-groups/${detail.value.id}/members`, {
      problemId: problem.id,
      relation: relation.value
    })
    ElMessage.success('已加入题目组')
    await doSearch()
    await load()
  } catch (e) {
    notifyError(e)
  }
}

async function removeMember(problemId: number) {
  try {
    await del(`/admin/problem-groups/members/${problemId}`)
    if (detail.value) {
      await openGroup(detail.value.id)
    }
    await load()
    await doSearch()
    ElMessage.success('已移除映射')
  } catch (e) {
    notifyError(e)
  }
}

async function removeGroup(group: Group) {
  try {
    await ElMessageBox.confirm(
      `删除题目组「${group.canonicalKey}」？只会删除映射关系，不影响题目与通过记录，删除后这些题目重新按各平台分别计数。`,
      '确认删除',
      { type: 'warning' }
    )
    await del(`/admin/problem-groups/${group.id}`)
    if (detail.value?.id === group.id) {
      detail.value = null
    }
    await load()
    ElMessage.success('已删除题目组')
  } catch (e) {
    if (e !== 'cancel') notifyError(e)
  }
}
</script>

<template>
  <div v-loading="loading">
    <div class="card notice-card">
      <div class="card-head">
        <span class="card-title">跨平台题目映射</span>
        <el-button size="small" @click="load">刷新</el-button>
      </div>
      <p class="notice-lead">
        跨平台题目映射用于处理同一来源的复用题与搬运题。映射<strong>只能人工建立</strong>，
        系统不会按题名自动匹配，避免把不同题目合并成一题。
      </p>
      <p class="muted" style="margin-bottom: 0">
        映射只影响个人档案与榜单中「去重后的唯一题量」，不改变各平台原始通过数，也不改写已入库的提交记录。
        未建立映射的题目仍按各平台分别计数，因此总量仍称为「各平台通过数合计」。
      </p>
    </div>

    <div class="stat-grid">
      <div class="stat-card">
        <div class="stat-label">题目组</div>
        <div class="stat-value">{{ coverage?.groupCount ?? 0 }}</div>
        <div class="stat-foot">人工建立的映射组</div>
      </div>
      <div class="stat-card">
        <div class="stat-label">已映射题目</div>
        <div class="stat-value">{{ coverage?.mappedProblemCount ?? 0 }}</div>
        <div class="stat-foot">已并入某个题目组的题目</div>
      </div>
      <div class="stat-card">
        <div class="stat-label">当前题目组</div>
        <div class="stat-value detail-key">{{ detail ? detail.canonicalKey : '—' }}</div>
        <div class="stat-foot">{{ detail ? `${detail.members.length} 道题目` : '未选择' }}</div>
      </div>
    </div>

    <el-row :gutter="16">
      <el-col :xs="24" :md="12">
        <div class="card">
          <div class="card-head"><span class="card-title">新建题目组</span></div>
          <el-form label-position="top">
            <el-form-item label="规范标识">
              <el-input v-model="createForm.canonicalKey" placeholder="例如 CF/4A，用于标识这道题" />
            </el-form-item>
            <el-form-item label="题目名称">
              <el-input v-model="createForm.title" placeholder="选填" />
            </el-form-item>
            <el-form-item label="备注">
              <el-input v-model="createForm.note" placeholder="选填，例如：洛谷搬运题" />
            </el-form-item>
            <el-button type="primary" @click="createGroup">创建</el-button>
          </el-form>
        </div>

        <div class="card">
          <div class="card-head">
            <span class="card-title">已有题目组</span>
            <span class="badge-soft">{{ groups.length }} 组</span>
          </div>
          <div v-if="groups.length" class="table-scroll">
            <el-table :data="groups" size="small" @row-click="(row: Group) => openGroup(row.id)">
              <el-table-column prop="canonicalKey" label="规范标识" min-width="130" />
              <el-table-column prop="title" label="名称" min-width="130" show-overflow-tooltip />
              <el-table-column label="题目数" width="90">
                <template #default="{ row }">{{ row.memberCount ?? 0 }}</template>
              </el-table-column>
              <el-table-column label="操作" width="130" fixed="right">
                <template #default="{ row }">
                  <el-button size="small" text type="primary" @click.stop="openGroup(row.id)">查看</el-button>
                  <el-button size="small" text type="danger" @click.stop="removeGroup(row as Group)">删除</el-button>
                </template>
              </el-table-column>
            </el-table>
          </div>
          <el-empty v-if="!groups.length" description="还没有建立任何映射" :image-size="70" />
        </div>
      </el-col>

      <el-col :xs="24" :md="12">
        <div class="card">
          <div class="card-head">
            <span class="card-title">当前题目组：{{ detail ? detail.canonicalKey : '未选择' }}</span>
            <span v-if="detail" class="badge-soft">{{ detail.members.length }} 道题目</span>
          </div>
          <template v-if="detail">
            <div class="table-scroll">
              <el-table :data="detail.members" size="small" empty-text="该组还没有题目">
                <el-table-column label="平台" width="120"><template #default="{ row }">{{ platformName(row.platform) }}</template></el-table-column>
                <el-table-column label="题目" min-width="180" show-overflow-tooltip>
                  <template #default="{ row }">
                    <a v-if="httpUrl(row.url)" :href="httpUrl(row.url)" target="_blank" rel="noopener noreferrer">
                      {{ row.problemKey }} {{ row.title }}
                    </a>
                    <span v-else>{{ row.problemKey }} {{ row.title }}</span>
                  </template>
                </el-table-column>
                <el-table-column label="关系" width="110">
                  <template #default="{ row }">
                    <el-tag size="small" type="info">{{ relationLabels[row.relation] ?? row.relation }}</el-tag>
                  </template>
                </el-table-column>
                <el-table-column label="操作" width="80" fixed="right">
                  <template #default="{ row }">
                    <el-button size="small" text type="danger" @click="removeMember(row.problemId)">移除</el-button>
                  </template>
                </el-table-column>
              </el-table>
            </div>
            <p class="muted" style="margin-bottom: 0">
              一道题最多属于一个题目组；要改归属需先移除再加入，系统不会静默替换。
            </p>
          </template>
          <el-empty v-else description="先创建或点选一个题目组，再从下方搜索结果加入题目" :image-size="70" />
        </div>

        <div class="card">
          <div class="card-head"><span class="card-title">搜索题目</span></div>
          <el-form :inline="true">
            <el-form-item label="平台">
              <el-select v-model="search.platform" clearable placeholder="全部" style="width: 150px">
                <el-option v-for="c in capabilities" :key="c.platform" :label="c.displayName" :value="c.platform" />
              </el-select>
            </el-form-item>
            <el-form-item label="关键词">
              <el-input v-model="search.keyword" placeholder="题号或标题" style="width: 180px" @keyup.enter="doSearch" />
            </el-form-item>
            <el-form-item label="关系">
              <el-select v-model="relation" style="width: 130px">
                <el-option label="同一题目" value="SAME_PROBLEM" />
                <el-option label="搬运 / 镜像" value="MIRROR" />
                <el-option label="翻译版本" value="TRANSLATED" />
              </el-select>
            </el-form-item>
            <el-form-item>
              <el-button type="primary" :loading="searching" @click="doSearch">搜索</el-button>
            </el-form-item>
          </el-form>
          <div class="table-scroll">
            <el-table :data="searchResults" size="small" empty-text="先输入关键词搜索题目">
              <el-table-column label="平台" width="120"><template #default="{ row }">{{ platformName(row.platform) }}</template></el-table-column>
              <el-table-column label="题目" min-width="180" show-overflow-tooltip>
                <template #default="{ row }">{{ row.problemKey }} {{ row.title }}</template>
              </el-table-column>
              <el-table-column label="当前映射" min-width="120">
                <template #default="{ row }">
                  <el-tag v-if="row.groupKey" size="small" type="success">{{ row.groupKey }}</el-tag>
                  <span v-else class="muted">未映射</span>
                </template>
              </el-table-column>
              <el-table-column label="操作" width="90" fixed="right">
                <template #default="{ row }">
                  <el-button
                    size="small"
                    text
                    type="primary"
                    :disabled="!detail || row.groupId !== null"
                    @click="addToGroup(row as ProblemSearchItem)"
                  >
                    加入
                  </el-button>
                </template>
              </el-table-column>
            </el-table>
          </div>
        </div>
      </el-col>
    </el-row>
  </div>
</template>

<style scoped>
.notice-card {
  border-left: 3px solid var(--brand-500);
  background: linear-gradient(90deg, var(--brand-soft), transparent 240px), var(--bg-elevated);
}

.notice-lead {
  margin: 0 0 8px;
  color: var(--text);
  font-size: 13px;
  line-height: 1.7;
}

/* 当前题目组的标识可能较长，单独收窄字号避免撑破卡片 */
.detail-key {
  font-size: 20px;
  word-break: break-all;
}
</style>
