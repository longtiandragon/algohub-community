<script setup lang="ts">
import { onBeforeUnmount, ref, watch } from 'vue'
import { get, notifyError } from '@/api/client'
import PageHeader from '@/components/ui/PageHeader.vue'
import SectionCard from '@/components/ui/SectionCard.vue'
import FriendActions from '@/components/social/FriendActions.vue'
interface Person { id:number; displayName:string; following:boolean; mutual:boolean }
const type = ref('FOLLOWING'), items = ref<Person[]>([]), more = ref(false), cursor = ref(0), loading = ref(false)
const counts = ref<{following:number;followers:number} | null>(null)
const error = ref(''), retryAppend = ref(false)
let generation = 0
async function load(append=false) {
  if (append && (loading.value || !more.value)) return
  const token=++generation
  loading.value=true; error.value=''; retryAppend.value=append
  if (!append) { items.value=[]; cursor.value=0; more.value=false }
  try {
    const [data, totals]=await Promise.all([
      get<{items:Person[];hasMore:boolean;nextCursor:number}>('/social/connections',{type:type.value,before:append?cursor.value:0}),
      get<{following:number;followers:number}>('/social/counts')
    ])
    if (token!==generation) return
    counts.value=totals
    items.value=append?[...items.value,...data.items]:data.items;more.value=data.hasMore;cursor.value=data.nextCursor
  } catch(e) {
    if(token===generation) { error.value='关注列表暂时加载失败，请重试'; notifyError(e) }
  } finally {if(token===generation)loading.value=false}
}
watch(type,()=>load(),{immediate:true})
onBeforeUnmount(()=>{generation++})
function retry() { return load(retryAppend.value) }
</script>

<template>
  <div class="page mx-auto max-w-[900px] flex flex-col gap-4">
    <PageHeader title="关注与好友" description="关注同学的练习动态，互相关注后可以发送固定的学习提醒。"><template #actions><RouterLink to="/messages">查看消息 →</RouterLink></template></PageHeader>
    <SectionCard title="我的关系">
      <div class="mb-4 flex flex-wrap gap-6" data-test="follow-counts">
        <span>我关注了 <strong class="num text-brand-600">{{counts?.following ?? '—'}}</strong> 人</span>
        <span>关注我的 <strong class="num text-brand-600">{{counts?.followers ?? '—'}}</strong> 人</span>
      </div>
      <el-radio-group v-model="type" aria-label="关系列表" class="mb-4"><el-radio-button value="FOLLOWING">我的关注</el-radio-button><el-radio-button value="FRIENDS">互相关注</el-radio-button></el-radio-group>
      <p class="text-xs text-muted">关注我的人仅显示数量，不提供名单；互关好友仍可发送固定消息。关注不会改变档案和动态的隐私设置。</p>
      <p v-if="loading && !items.length" role="status">正在加载…</p>
      <el-empty v-else-if="!items.length && !error" :description="type==='FRIENDS'?'还没有互相关注的好友':'还没有关注同学'" />
      <p v-if="error" role="alert" class="text-sm text-muted">{{ error }} <el-button text :disabled="loading" @click="retry">重试</el-button></p>
      <ul class="m-0 list-none p-0"><li v-for="person in items" :key="person.id" class="flex flex-wrap items-center justify-between gap-3 border-b border-line py-4">
        <RouterLink :to="`/users/${person.id}`" class="text-ink font-semibold">{{person.displayName}}</RouterLink>
        <FriendActions :user-id="person.id" :initial="person" @change="load()" />
      </li></ul>
      <el-button v-if="more" :loading="loading" class="mt-4 w-full" @click="load(true)">加载更多</el-button>
    </SectionCard>
  </div>
</template>
