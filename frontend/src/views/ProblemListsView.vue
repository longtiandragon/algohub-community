<script setup lang="ts">
import { computed, ref, watch } from 'vue'
import { useRoute } from 'vue-router'
import { ElMessage, ElMessageBox } from 'element-plus'
import { del, get, notifyError, post } from '@/api/client'
import { useAuthStore } from '@/stores/auth'
import EmptyState from '@/components/ui/EmptyState.vue'
import InfoTip from '@/components/ui/InfoTip.vue'
import PageHeader from '@/components/ui/PageHeader.vue'
import PageTabs from '@/components/ui/PageTabs.vue'
import Skeleton from '@/components/ui/Skeleton.vue'
import ListCreateDialog from '@/components/practice/ListCreateDialog.vue'
import ListGenerateDialog from '@/components/practice/ListGenerateDialog.vue'
import ProblemListCard from '@/components/practice/ProblemListCard.vue'
import type { ProblemList } from '@/components/practice/types'

type ScopeKey = 'SYSTEM' | 'MINE' | 'FAVORITE' | 'ALL'

/** 标签按使用频率排：推荐（系统题单）/ 我的 / 我收藏的 / 全部；?tab= 记住当前标签 */
const TABS: { key: string; label: string; scope: ScopeKey; empty: { title: string; description: string; action?: string } }[] = [
  {
    key: 'recommended',
    label: '推荐',
    scope: 'SYSTEM',
    empty: { title: '还没有推荐题单', description: '推荐题单由管理员在管理后台维护，设为可见后出现在这里。' }
  },
  {
    key: 'mine',
    label: '我的',
    scope: 'MINE',
    empty: { title: '还没有自己的题单', description: '自己建的题单默认只有你能看到，随时可以分享给本校同学。', action: '新建题单' }
  },
  {
    key: 'favorite',
    label: '我收藏的',
    scope: 'FAVORITE',
    empty: { title: '还没有收藏题单', description: '在题单卡片上点星标收藏，之后在这里就能找到。' }
  },
  {
    key: 'all',
    label: '全部',
    scope: 'ALL',
    empty: { title: '还没有能看到的题单', description: '可以自己建一个，或者按规则从已同步的题库里筛一份。', action: '新建题单' }
  }
]
const OTHER_GROUP = '其他题单'

const route = useRoute()
const auth = useAuthStore()
const props = defineProps<{ management?: boolean }>()
const management = computed(() => props.management === true && auth.isAdmin)
const apiBase = computed(() => management.value ? '/admin/problem-lists' : '/problem-lists')

const tab = computed(() => TABS.find((t) => t.key === route.query.tab) ?? TABS[0])
const tabLinks = computed(() => TABS.map((t) => ({ key: t.key, label: t.label, to: { query: { ...route.query, tab: t.key } } })))

const lists = ref<ProblemList[]>([])
const loading = ref(false)
const loaded = ref(false)
const favoritePending = ref<number | null>(null)
const createVisible = ref(false)
const generateVisible = ref(false)

/** 按 category 分组；后端已按 sortOrder 排好，这里只切分不重排，没分组的放最后 */
const groups = computed(() => {
  const index = new Map<string, ProblemList[]>()
  for (const list of lists.value) {
    const key = list.category?.trim() || OTHER_GROUP
    if (!index.has(key)) index.set(key, [])
    index.get(key)!.push(list)
  }
  const result = [...index.entries()].map(([label, items]) => ({ label, lists: items }))
  return [...result.filter((g) => g.label !== OTHER_GROUP), ...result.filter((g) => g.label === OTHER_GROUP)]
})

/** 普通界面只能修改自己的题单；系统题单在专门的管理界面维护。 */
function isMine(list: ProblemList): boolean {
  return list.ownerId !== null && auth.me?.id !== undefined && list.ownerId === auth.me.id
}

let requestId = 0
async function load() {
  const id = ++requestId
  loading.value = true
  try {
    const data = await get<ProblemList[]>(apiBase.value, management.value ? undefined : { scope: tab.value.scope })
    if (id !== requestId) return
    lists.value = data
    loaded.value = true
  } catch (e) {
    if (id === requestId) notifyError(e)
  } finally {
    if (id === requestId) loading.value = false
  }
}

watch(
  () => [management.value, tab.value.scope],
  () => {
    loaded.value = false
    void load()
  },
  { immediate: true }
)

async function toggleFavorite(list: ProblemList) {
  const next = !list.favorited
  favoritePending.value = list.id
  try {
    if (next) await post(`/problem-lists/${list.id}/favorite`)
    else await del(`/problem-lists/${list.id}/favorite`)
    list.favorited = next
    list.favoriteCount = Math.max(0, (list.favoriteCount ?? 0) + (next ? 1 : -1))
    // 「我收藏的」下取消收藏，卡片应当消失
    if (tab.value.scope === 'FAVORITE' && !next) lists.value = lists.value.filter((l) => l.id !== list.id)
  } catch (e) {
    notifyError(e)
  } finally {
    favoritePending.value = null
  }
}

async function removeList(list: ProblemList) {
  if (!management.value && !isMine(list)) return
  try {
    await ElMessageBox.confirm(`删除后「${list.title}」和里面的题目都会消失，收藏过它的同学也看不到了。删除后不能恢复。`, '删除题单', {
      type: 'warning',
      confirmButtonText: '删除',
      cancelButtonText: '取消',
      confirmButtonClass: 'el-button--danger'
    })
  } catch {
    return
  }
  try {
    await del(`${apiBase.value}/${list.id}`)
    ElMessage.success('题单已删除')
    await load()
  } catch (e) {
    notifyError(e)
  }
}

const RULE_TIPS = [
  '进度按你已同步的通过记录算：同步到的通过记录里有这道题就算过了，不用手动打勾；还没同步的记录不算。',
  '标「自动生成」的题单是按规则从已同步题库里筛出来的，不是人工挑的，题目会随题库同步结果变化。'
]
</script>

<template>
  <div class="flex flex-col gap-5" data-test="page">
    <PageHeader :title="management ? '题单管理' : '题单训练'" class="!mb-0">
      <template #description>
        {{ management ? '维护系统与用户题单，包含隐藏和私有题单' : '成套地练；进度按你已同步的通过记录自动计算，不用手动打勾' }}
        <!-- 手机上 ⓘ 放在顶栏 -->
        <span class="hidden md:inline">
          <InfoTip title="进度怎么算" label="进度怎么算" class="ml-0.5 align-[-3px]">
            <p v-for="(tip, i) in RULE_TIPS" :key="i" class="m-0" :class="{ 'mt-1.5': i }">{{ tip }}</p>
          </InfoTip>
        </span>
      </template>
      <template #actions>
        <el-button @click="generateVisible = true">按规则生成</el-button>
        <el-button type="primary" data-test="create-list" @click="createVisible = true">新建题单</el-button>
      </template>
      <template #mobile-actions>
        <InfoTip title="进度怎么算" label="进度怎么算" topbar>
          <p v-for="(tip, i) in RULE_TIPS" :key="i" class="m-0" :class="{ 'mt-1.5': i }">{{ tip }}</p>
        </InfoTip>
        <el-dropdown trigger="click" placement="bottom-end" @command="(c: string) => (c === 'create' ? (createVisible = true) : (generateVisible = true))">
          <button type="button" class="topbar-add" aria-label="新建题单">
            <span class="i-lucide-plus text-xl" aria-hidden="true" />
          </button>
          <template #dropdown>
            <el-dropdown-menu>
              <el-dropdown-item command="create">新建题单</el-dropdown-item>
              <el-dropdown-item command="generate">按规则生成</el-dropdown-item>
            </el-dropdown-menu>
          </template>
        </el-dropdown>
      </template>
    </PageHeader>

    <PageTabs v-if="!management" :tabs="tabLinks" :active="tab.key" label="题单范围" />

    <div v-if="!loaded" class="grid grid-cols-1 gap-4 md:grid-cols-2 xl:grid-cols-3" aria-busy="true" aria-label="正在加载题单">
      <div v-for="i in 3" :key="i" class="flex flex-col gap-3 border border-line rounded-card border-solid bg-surface p-[18px]">
        <Skeleton class="h-5 w-1/3" />
        <Skeleton class="h-5 w-3/4" />
        <Skeleton class="h-10 w-full" />
        <Skeleton class="h-2 w-full" />
      </div>
    </div>

    <EmptyState
      v-else-if="!lists.length"
      icon="i-lucide-list-checks"
      :title="tab.empty.title"
      :description="tab.empty.description"
      :action="tab.empty.action"
      @action="createVisible = true"
    />

    <template v-else>
      <section v-for="group in groups" :key="group.label" class="flex flex-col gap-3" :aria-label="group.label" :class="{ 'opacity-60': loading }">
        <div class="flex items-center gap-3">
          <h2 class="m-0 shrink-0 text-base text-ink font-semibold">{{ group.label }}</h2>
          <span class="h-px flex-1 bg-line" aria-hidden="true" />
          <span class="shrink-0 text-[12.5px] text-muted">{{ group.lists.length }} 个题单</span>
        </div>
        <div class="grid grid-cols-1 gap-3 md:grid-cols-2 md:gap-4 xl:grid-cols-3">
          <ProblemListCard
            v-for="list in group.lists"
            :key="list.id"
            :list="list"
            :mine="isMine(list)"
            :can-edit="management || isMine(list)"
            :management="management"
            :show-code="auth.isAdmin"
            :favorite-pending="favoritePending === list.id"
            @favorite="toggleFavorite(list)"
            @remove="removeList(list)"
          />
        </div>
      </section>
    </template>

    <ListCreateDialog v-model="createVisible" :management="management" @created="load" />
    <ListGenerateDialog v-model="generateVisible" :management="management" @created="load" />
  </div>
</template>
