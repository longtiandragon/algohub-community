<script setup lang="ts">
import { ref } from 'vue'
import { useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import { post, notifyError } from '@/api/client'
import type { Me, Membership } from '@/api/types'
import OnboardingLayout from '@/components/onboarding/OnboardingLayout.vue'
import { useAuthStore } from '@/stores/auth'
import { useOnboardingDraft } from '@/composables/useOnboardingDraft'
import { useEnrollmentYears } from '@/composables/useEnrollmentYears'
const auth = useAuthStore()
const router = useRouter()
const { draft, clear } = useOnboardingDraft()
const { years, error: yearError, reload } = useEnrollmentYears()
const saving = ref(false)
async function submit() {
  if (saving.value) return
  if (!draft.displayName.trim() || draft.enrollmentYear == null) { ElMessage.warning('请填写昵称并选择入学年级'); return }
  if (!draft.className.trim() || !draft.studentNo.trim()) { ElMessage.warning('请填写班级和学号'); return }
  saving.value = true
  try {
    const data = { schoolId: draft.schoolId || null, inviteCode: draft.schoolId ? draft.inviteCode.trim() || null : null,
      displayName: draft.displayName.trim(), enrollmentYear: draft.enrollmentYear,
      className: draft.className.trim() || null, studentNo: draft.studentNo.trim() || null }
    if (auth.me?.onboardingCompleted) {
      // Existing users can still apply for a school from settings.
      if (data.schoolId) {
        const member = await post<Membership>('/schools/select', data)
        await post(`/schools/memberships/${member.id}`, data)
      }
      await post('/me/academic-profile', data)
      await post('/me/profile', { displayName: data.displayName, email: auth.me.email, bio: auth.me.bio, avatarUrl: auth.me.avatarUrl })
      await auth.refreshMe()
    } else { auth.me = await post<Me>('/me/onboarding', data) }
    clear()
    ElMessage.success(auth.me?.memberships.some(m => m.status === 'PENDING') ? '注册已完成，学校申请等待审核' : '资料已保存')
    await router.replace('/accounts')
  } catch (e) { notifyError(e) }
  finally { saving.value = false }
}
</script>
<template>
  <OnboardingLayout :step="3" icon="i-lucide-user-round" title="完善个人资料" description="填好资料即可完成注册，刷题账号可以稍后绑定。">
    <form class="flex flex-col gap-4" @submit.prevent="submit">
      <div class="flex flex-col gap-1.5"><label for="profile-name">昵称（必填）</label><input id="profile-name" v-model="draft.displayName" required maxlength="32" class="auth-input" placeholder="档案和榜单上展示的名字" autocomplete="nickname" /></div>
      <div class="flex flex-col gap-1.5"><label for="profile-year">入学年级（必填）</label><select id="profile-year" v-model="draft.enrollmentYear" required class="auth-input"><option :value="null" disabled>请选择入学年级</option><option v-for="year in years" :key="year" :value="year">{{ year }} 级</option></select><p v-if="yearError" role="alert" class="text-danger-ink">年级加载失败 <button type="button" class="auth-link" @click="reload">重试</button></p></div>
      <div class="flex flex-col gap-1.5"><label for="profile-class">班级（必填）</label><input required id="profile-class" v-model="draft.className" maxlength="64" class="auth-input" placeholder="如24计应2班" /></div>
      <div class="flex flex-col gap-1.5"><label for="profile-student">学号（必填，默认不公开）</label><input required id="profile-student" v-model="draft.studentNo" maxlength="32" class="auth-input" placeholder="填写你的学号" /></div>
      <p class="m-0 text-sm text-muted">联系邮箱默认使用注册邮箱，之后可在设置中修改；邮箱默认不公开。</p>
    </form>
    <template #footer><div class="flex flex-col items-center gap-3"><button type="button" class="auth-button is-primary" :disabled="saving" @click="submit">{{ saving ? '正在保存…' : '完成注册，前往平台账号' }}</button><RouterLink to="/select-school" class="auth-link">返回修改学校</RouterLink></div></template>
  </OnboardingLayout>
</template>
