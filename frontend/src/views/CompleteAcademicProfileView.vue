<script setup lang="ts">
import { computed, reactive, ref } from 'vue'
import { useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import { post, notifyError } from '@/api/client'
import type { Me } from '@/api/types'
import OnboardingLayout from '@/components/onboarding/OnboardingLayout.vue'
import { useAuthStore } from '@/stores/auth'
import { useEnrollmentYears } from '@/composables/useEnrollmentYears'

const auth = useAuthStore()
const router = useRouter()
const original = { enrollmentYear: auth.me?.enrollmentYear ?? null, className: auth.me?.className ?? '', studentNo: auth.me?.studentNo ?? '' }
const form = reactive({ ...original })
const saving = ref(false)
const { years, error, reload } = useEnrollmentYears()
const yearOptions = computed(() => [...new Set([...years.value, ...(original.enrollmentYear ? [original.enrollmentYear] : [])])].sort((a, b) => b - a))
const membership = computed(() => auth.approvedMembership ?? auth.me?.memberships.find(m => m.status === 'PENDING'))
async function submit() {
  if (saving.value) return
  if (!form.enrollmentYear || !form.className.trim() || !form.studentNo.trim()) { ElMessage.warning('请补齐年级、班级和学号'); return }
  saving.value = true
  try {
    auth.me = await post<Me>('/me/complete-academic-profile', { ...form, className: form.className.trim(), studentNo: form.studentNo.trim() })
    ElMessage.success('资料已补齐')
    await router.replace(auth.approvedMembership ? '/' : '/ranking')
  } catch (e) { notifyError(e) }
  finally { saving.value = false }
}
</script>

<template>
  <OnboardingLayout icon="i-lucide-user-round" title="补全资料" description="班级和学号现为必填项，请补齐缺失的信息。已有学校、账号和刷题记录都会保留。">
    <p class="mt-0 text-sm text-muted">{{ auth.me?.displayName }}<template v-if="membership"> · {{ membership.schoolName }}</template></p>
    <p v-if="auth.me?.nicknameNeedsReview" class="text-sm text-muted">当前是系统生成的昵称，补全资料后可到设置中修改。</p>
    <form id="complete-academic-form" class="flex flex-col gap-4" @submit.prevent="submit">
      <div class="flex flex-col gap-1.5">
        <label for="repair-year">入学年级</label>
        <select id="repair-year" v-model="form.enrollmentYear" required :disabled="original.enrollmentYear !== null" class="auth-input">
          <option :value="null" disabled>请选择入学年级</option>
          <option v-for="year in yearOptions" :key="year" :value="year">{{ year }} 级</option>
        </select>
        <p v-if="error" role="alert" class="text-sm text-danger-ink">年级加载失败 <button type="button" @click="reload">重试</button></p>
      </div>
      <div class="flex flex-col gap-1.5">
        <label for="repair-class">班级</label>
        <input id="repair-class" v-model="form.className" required maxlength="64" :readonly="!!original.className.trim()" class="auth-input" placeholder="如24计应2班" />
      </div>
      <div class="flex flex-col gap-1.5">
        <label for="repair-student">学号（默认不公开）</label>
        <input id="repair-student" v-model="form.studentNo" required maxlength="32" :readonly="!!original.studentNo.trim()" class="auth-input" placeholder="填写你的学号" />
      </div>
      <p class="m-0 text-sm text-muted">已填写的信息在此保留；需要更正时，可在补全后前往设置修改。</p>
    </form>
    <template #footer><button type="submit" form="complete-academic-form" class="auth-button is-primary" :disabled="saving">{{ saving ? '正在保存…' : '保存并继续' }}</button></template>
  </OnboardingLayout>
</template>
