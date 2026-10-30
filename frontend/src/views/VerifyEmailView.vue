<script setup lang="ts">
import { ref } from 'vue'
import { useRouter } from 'vue-router'
import EmailIdentityPanel from '@/components/EmailIdentityPanel.vue'
import OnboardingLayout from '@/components/onboarding/OnboardingLayout.vue'
import { useAuthStore } from '@/stores/auth'

/**
 * 引导第 1 步：验证登录邮箱。所有账号都要做一次；邮箱登录、GitHub 登录（邮箱已验证）的同学不会来这一页，
 * 路由直接把他们送到第 2 步。验证成功后去选学校（已经有学校的回总览）。
 */
const auth = useAuthStore()
const router = useRouter()
const panel = ref<InstanceType<typeof EmailIdentityPanel> | null>(null)
const error = ref('')

async function next() {
  error.value = ''
  try {
    await auth.refreshMe()
    if (auth.me?.emailVerified) await router.push(auth.me?.onboardingCompleted ? (auth.approvedMembership ? '/' : '/ranking') : '/select-school')
    else error.value = '还没确认到验证结果，请稍后再点一次。'
  } catch (e) {
    error.value = e instanceof Error ? e.message : '无法确认邮箱验证状态，请重试'
  }
}
</script>

<template>
  <OnboardingLayout :step="1" icon="i-lucide-mail" title="验证你的登录邮箱" description="以后用它登录和找回账号，只需要做一次。">
    <EmailIdentityPanel ref="panel" mode="first" @verified="next" />
    <p v-if="error" class="m-0 text-[13px] text-danger-ink" role="alert">{{ error }}</p>
    <template #footer>
      <button type="button" class="auth-button is-primary" :disabled="!panel?.canVerify" @click="panel?.verify()">
        {{ panel?.verifying ? '正在验证…' : '验证并继续' }}
      </button>
    </template>
  </OnboardingLayout>
</template>
