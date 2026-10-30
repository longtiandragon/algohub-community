<script setup lang="ts">
import { onMounted, ref, computed } from 'vue'
import { useRouter } from 'vue-router'
import { get, notifyError } from '@/api/client'
import OnboardingLayout from '@/components/onboarding/OnboardingLayout.vue'
import { useAuthStore } from '@/stores/auth'
import { useOnboardingDraft } from '@/composables/useOnboardingDraft'
const auth = useAuthStore()
const router = useRouter()
const { draft } = useOnboardingDraft()
const schools = ref<{ id: number; name: string; shortName: string | null }[]>([])
const loading = ref(false)
const error = ref('')
const rejected = computed(() => new Set(auth.me?.memberships.filter(m => m.status === 'REJECTED').map(m => m.schoolId)))
async function load() {
  loading.value = true; error.value = ''
  try { schools.value = await get('/schools') }
  catch (e) { error.value = '学校列表加载失败，请重试'; notifyError(e) }
  finally { loading.value = false }
}
onMounted(load)
function next() {
  if (draft.schoolId && (error.value || rejected.value.has(draft.schoolId))) return
  if (!draft.schoolId) draft.inviteCode = ''
  draft.schoolChosen = true
  void router.push('/complete-profile')
}
</script>
<template>
  <OnboardingLayout :step="2" icon="i-lucide-graduation-cap" title="选择学校" description="学校可以暂不选择，以后也能申请加入。">
    <form class="flex flex-col gap-4" @submit.prevent="next">
      <label class="text-sm text-body" for="onboarding-school">学校（选填）</label>
      <el-select id="onboarding-school" v-model="draft.schoolId" filterable clearable placeholder="暂不加入学校" class="w-full" :loading="loading" @clear="draft.schoolId = null">
        <el-option :value="0" label="暂不加入学校" />
        <el-option v-for="school in schools" :key="school.id" :value="school.id" :label="school.name" :disabled="rejected.has(school.id)" />
      </el-select>
      <p v-if="error" class="m-0 text-sm text-danger-ink" role="alert">{{ error }} <button type="button" class="auth-link" @click="load">重试</button></p>
      <p class="m-0 text-sm text-muted">找不到你的学校？可以先注册，联系管理员添加后再加入。</p>
      <div v-if="draft.schoolId" class="flex flex-col gap-2">
        <label for="school-invite" class="text-sm text-body">学校邀请码（选填）</label>
        <input id="school-invite" v-model="draft.inviteCode" class="auth-input" maxlength="64" autocomplete="off" placeholder="老师或班群发的邀请码" />
        <p class="m-0 text-sm text-muted">有效邀请码可直接加入；没有邀请码也可以注册，学校归属等待管理员审核。</p>
      </div>
    </form>
    <template #footer><button class="auth-button is-primary" type="button" @click="next">下一步：完善资料</button></template>
  </OnboardingLayout>
</template>
