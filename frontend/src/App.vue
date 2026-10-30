<script setup lang="ts">
import { computed, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import zhCn from 'element-plus/es/locale/lang/zh-cn'
import { useAuthStore } from '@/stores/auth'
import { useBreakpoint } from '@/composables/useBreakpoint'
import AppSidebar from '@/components/layout/AppSidebar.vue'
import AppRail from '@/components/layout/AppRail.vue'
import MobileTopBar from '@/components/layout/MobileTopBar.vue'
import MobileTabBar from '@/components/layout/MobileTabBar.vue'
import ReminderUnread from '@/components/layout/ReminderUnread.vue'
import PageLoading from '@/components/layout/PageLoading.vue'
import { pendingNavigation } from '@/composables/useNavigation'
import { notifyError } from '@/api/client'
import { ElMessage } from 'element-plus'

const auth = useAuthStore()
const route = useRoute()
const router = useRouter()
// 电脑（≥ 1024）侧栏、平板（768–1023）图标栏；手机是顶栏 + 底部标签栏
const { md, lg } = useBreakpoint()

const isAuthPage = computed(() => route.meta.fullscreen === true)

/** 提示条上的按钮：去「设置与安全」绑定并验证登录邮箱。 */
function goVerifyEmail() {
  router.push({ path: '/settings', query: { section: 'login-email' } })
}
function retryStartup() { window.location.reload() }
const retryingLogout = ref(false)
async function retryLogout() {
  retryingLogout.value = true
  try {
    await auth.retryPendingLogout()
    if (!auth.pendingLogout) ElMessage.success('服务端退出已完成')
  } catch (error) { notifyError(error) }
  finally { retryingLogout.value = false }
}
</script>

<template>
  <!-- 组件库的中文语言包；确认框这类挂在组件树外的组件也会用到这份配置 -->
  <el-config-provider :locale="zhCn">
    <main v-if="auth.pendingLogout && !auth.pendingLogout.requiresIdentity" class="min-h-screen flex items-center justify-center p-6" data-test="pending-logout">
      <section class="max-w-md rounded-card border border-solid border-line bg-surface p-6 text-center" role="alert">
        <h1 class="text-xl text-ink">服务端退出尚未完成</h1>
        <p class="text-sm text-muted leading-relaxed">此浏览器已暂停登录恢复。请恢复网络，系统会自动重试；退出成功前请勿把共享电脑交给他人。</p>
        <p v-if="auth.pendingLogout.mode === 'all'" class="text-sm text-muted">其他设备尚未确认退出。</p>
        <p v-if="!auth.logoutMarkerPersistent" class="text-sm text-danger-ink">浏览器无法保存待退出状态，请保持本页打开，完成退出后再关闭。</p>
        <el-button type="primary" :loading="retryingLogout" @click="retryLogout">重试退出</el-button>
      </section>
    </main>
    <template v-else-if="auth.pendingLogout?.requiresIdentity">
      <aside class="border border-solid border-warning-line bg-warning-soft p-4 text-center" role="alert" data-test="logout-identity">
        <strong>尚未确认退出所有设备</strong>
        <p class="mb-0 text-sm">请重新登录发起操作的原账号，仅用于完成退出。其他账号不会被退出，也不会恢复已登录页面。</p>
      </aside>
      <router-view />
    </template>
    <main v-else-if="auth.bootstrapError && !auth.loaded" class="min-h-screen flex items-center justify-center p-6">
      <section class="max-w-md rounded-card border border-solid border-line bg-surface p-6 text-center" role="alert">
        <h1 class="text-xl text-ink">暂时无法连接服务器</h1>
        <p class="text-sm text-muted leading-relaxed">{{ auth.bootstrapError }}</p>
        <el-button type="primary" @click="retryStartup">重试连接</el-button>
      </section>
    </main>
    <router-view v-else-if="isAuthPage" />

    <div v-else class="app-shell">
      <AppSidebar v-if="lg" />
      <AppRail v-else-if="md" />

      <div class="app-main">
        <MobileTopBar v-if="!md" />

        <main class="app-content">
          <ReminderUnread v-if="!auth.isAdmin" />
          <aside v-if="!auth.isAdmin && auth.me?.nicknameNeedsReview" class="mb-4 flex flex-wrap items-center gap-3 rounded-xl bg-warning-soft px-4 py-3 text-sm" role="status">
            <span class="flex-1">你目前使用系统生成的昵称 {{ auth.me.displayName }}，可以改成方便同学认识你的名字。</span>
            <RouterLink to="/settings?section=profile">修改昵称</RouterLink>
          </aside>
          <div
            v-if="auth.emailGateBlocked"
            class="mb-4 flex flex-wrap items-center gap-3 border border-warning-line rounded-xl border-solid bg-warning-soft px-3.5 py-3"
          >
            <span class="size-8 flex shrink-0 items-center justify-center rounded-full bg-warning-tint text-warning-ink" aria-hidden="true">
              <span class="i-lucide-mail text-[17px]" />
            </span>
            <span class="min-w-0 flex flex-1 flex-col gap-0.5">
              <span class="text-sm text-ink font-semibold">先验证登录邮箱，才能使用平台的其他功能</span>
              <span class="text-[12.5px] text-warning-ink leading-normal">
                只需一次：绑定邮箱 → 收验证码 → 填进去，之后就不会再拦。在这之前，统计、动态等页面都读不到数据。
              </span>
            </span>
            <button type="button" class="gate-button" @click="goVerifyEmail">去验证邮箱</button>
          </div>

          <PageLoading v-if="pendingNavigation && !pendingNavigation.meta.fullscreen" :title="pendingNavigation.meta.title" />
          <div v-show="!pendingNavigation || pendingNavigation.meta.fullscreen">
            <router-view />
          </div>
        </main>
      </div>

      <MobileTabBar v-if="!md" />
    </div>
  </el-config-provider>
</template>

<style scoped>
.gate-button {
  flex-shrink: 0;
  height: 32px;
  padding: 0 12px;
  border: 0;
  border-radius: 8px;
  /* 浅色是深琥珀底白字，深色是亮琥珀底深字 */
  background: var(--warning-ink);
  color: var(--bg-elevated);
  font: inherit;
  font-size: 13px;
  font-weight: 600;
  cursor: pointer;
}

.gate-button:hover {
  filter: brightness(1.08);
}

.gate-button:focus-visible {
  outline: 2px solid var(--warning-ink);
  outline-offset: 2px;
}

@media (max-width: 767px) {
  .gate-button {
    width: 100%;
    height: 40px;
  }
}
</style>
