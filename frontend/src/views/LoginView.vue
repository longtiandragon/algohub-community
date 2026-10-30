<script setup lang="ts">
import { computed, onMounted, reactive, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import EmailLoginPanel from '@/components/EmailLoginPanel.vue'
import { get, notifyError } from '@/api/client'
import { startGithubAuthorize } from '@/api/githubAuthorize'
import { useAuthStore } from '@/stores/auth'
import { isAdminHost } from '@/utils/adminHost'
import { platformLogoUrl } from '@/utils/platformBrand'

/**
 * 登录页。默认只有邮箱验证码与 GitHub 两种方式；第一次用会自动创建账号。
 * 管理员密码登录仅在 /admin/login 提供。
 * 电脑：左侧深靛蓝方格纸面板（平台节点图、三个卖点），右侧表单；手机和平板：顶部品牌区，下面白色面板放表单。
 */
const auth = useAuthStore()
const router = useRouter()
const route = useRoute()

const adminLogin = computed(() => route.name === 'admin-login')
const passwordMode = adminLogin
watch(adminLogin, () => { loginForm.password = '' })

/** 带着 redirect 切换登录方式，登录后还回原来要去的页面 */
const emailModeLink = computed(() => ({ path: '/login', query: route.query.redirect ? { redirect: route.query.redirect } : {} }))

// ---------- 平台节点图（电脑）与图标行（手机） ----------
/** 节点中心坐标取自画布（632 × 330） */
const NODES = [
  { platform: 'CODEFORCES', x: 92, y: 58 },
  { platform: 'LUOGU', x: 240, y: 34 },
  { platform: 'LEETCODE_CN', x: 400, y: 30 },
  { platform: 'ATCODER', x: 548, y: 64 },
  { platform: 'NOWCODER', x: 586, y: 196 },
  { platform: 'MATIJI', x: 316, y: 298 },
  { platform: 'HDU', x: 52, y: 196 }
].map((node) => ({ ...node, logo: platformLogoUrl(node.platform) ?? '' }))
const MOBILE_LOGOS = NODES.slice(0, 6)

const FEATURES = [
  { title: '自动同步', text: '绑定 Codeforces、洛谷、力扣等账号，通过记录自动汇总' },
  { title: '同届排行', text: '看到自己在同届、同班里的位置和同学们在练什么' },
  { title: '比赛提醒', text: '各平台近期比赛一页看完，收藏后导入手机日历' }
]

// ---------- GitHub ----------
const githubEnabled = ref(false)
const githubNote = ref('')
const githubLoading = ref(true)
const githubLoadError = ref(false)
const githubRedirecting = ref(false)
/** 没配置时的说明（写着配置文件路径）只在开发时显示，同学们只看到「暂未开放」 */
const showGithubNote = import.meta.env.DEV

async function loadGithubStatus() {
  githubLoading.value = true
  githubLoadError.value = false
  githubEnabled.value = false
  try {
    const status = await get<{ enabled: boolean; note: string }>('/auth/github/status')
    githubEnabled.value = status.enabled
    githubNote.value = status.note
  } catch {
    githubLoadError.value = true
  } finally {
    githubLoading.value = false
  }
}
onMounted(loadGithubStatus)

const githubLabel = computed(() => {
  if (githubRedirecting.value) return '正在跳转到 GitHub…'
  if (githubLoading.value || githubEnabled.value || githubLoadError.value) return '使用 GitHub 登录'
  return 'GitHub 登录暂未开放'
})

async function loginWithGithub() {
  if (!githubEnabled.value || githubLoading.value || githubRedirecting.value) return
  githubRedirecting.value = true
  try {
    await startGithubAuthorize()
  } catch (e) {
    notifyError(e)
    githubRedirecting.value = false
  }
}

// ---------- 管理员密码登录 ----------
const submitting = ref(false)
const loginForm = reactive({ username: '', password: '' })
function redirect() {
  if (auth.isAdmin) return router.push('/admin')
  const target = route.query.redirect
  return router.push(typeof target === 'string' && target.startsWith('/') && !target.startsWith('//') && !target.includes('\\') ? target : '/')
}

async function onLogin() {
  if (submitting.value) return
  if (!loginForm.username.trim() || !loginForm.password) {
    ElMessage.warning('请填写用户名和密码')
    return
  }
  submitting.value = true
  try {
    if (!await auth.login(loginForm.username.trim(), loginForm.password)) return
    loginForm.password = ''
    if (adminLogin.value && !auth.isAdmin) {
      await auth.logout()
      ElMessage.warning('此账号没有管理权限，请使用普通登录入口')
      return
    }
    ElMessage.success('登录成功')
    await redirect()
  } catch (e) {
    notifyError(e)
  } finally {
    submitting.value = false
  }
}

</script>

<template>
  <div class="min-h-screen flex flex-col bg-surface lg:flex-row">
    <!-- 品牌面板：电脑在左边，手机和平板在顶上 -->
    <section class="brand-panel">
      <div class="brand-inner">
        <div class="flex items-center gap-2.5 lg:gap-3">
          <img src="/algohub-mark.svg" alt="" class="brand-mark" aria-hidden="true" />
          <span class="flex flex-col leading-[1.35]">
            <span class="text-[15px] font-650 lg:text-base">AlgoHub</span>
            <span class="hidden text-[12.5px] text-[#c7d2fe] lg:block">算法学习与成长档案</span>
          </span>
        </div>

        <svg class="node-graph" viewBox="0 0 632 330" aria-hidden="true">
          <defs>
            <filter id="login-node-shadow" x="-50%" y="-50%" width="200%" height="200%">
              <feDropShadow dx="0" dy="6" stdDeviation="9" flood-color="#000" flood-opacity="0.25" />
            </filter>
          </defs>
          <path
            :d="NODES.map((node) => `M316 165 L${node.x} ${node.y}`).join(' ')"
            fill="none"
            stroke="rgba(199,210,254,0.45)"
            stroke-width="1.5"
            stroke-dasharray="5 6"
          />
          <circle cx="316" cy="165" r="57" fill="#4f46e5" stroke="rgba(255,255,255,0.14)" stroke-width="6" />
          <image href="/algohub-mark.svg" x="294" y="139" width="44" height="44" />
          <text x="316" y="186" text-anchor="middle" fill="#e0e7ff" font-size="12">你的档案</text>
          <g v-for="node in NODES" :key="node.platform">
            <circle :cx="node.x" :cy="node.y" r="26" fill="#fff" filter="url(#login-node-shadow)" />
            <image :href="node.logo" :x="node.x - 15" :y="node.y - 15" width="30" height="30" />
          </g>
        </svg>

        <div class="flex flex-col gap-[22px]">
          <h1 class="m-0 text-[25px] font-bold leading-[1.4] lg:(text-[28px] leading-[1.35]) xl:text-[34px] tracking-[-0.01em]">
            <span class="hidden lg:inline">把分散在各个 OJ 的练习，</span><span class="lg:hidden">把各个 OJ 的练习，</span><br />汇成一份看得见进步的档案
          </h1>
          <ul class="m-0 hidden list-none grid-cols-1 gap-3 p-0 lg:grid xl:grid-cols-3">
            <li v-for="feature in FEATURES" :key="feature.title" class="feature-card">
              <span class="text-sm font-semibold">{{ feature.title }}</span>
              <span class="text-[12.5px] text-[#c7d2fe] leading-[1.6]">{{ feature.text }}</span>
            </li>
          </ul>
          <div class="flex items-center gap-2 lg:hidden">
            <span v-for="node in MOBILE_LOGOS" :key="node.platform" class="size-9 flex shrink-0 items-center justify-center rounded-full bg-white">
              <img :src="node.logo" alt="" class="size-6" />
            </span>
            <span class="ml-0.5 whitespace-nowrap text-[12.5px] text-[#c7d2fe]">等多个平台</span>
          </div>
        </div>
      </div>
    </section>

    <!-- 表单 -->
    <section class="form-panel">
      <div class="mx-auto w-full max-w-[400px] flex flex-col gap-[18px] lg:gap-[26px]">
        <template v-if="!passwordMode">
          <div class="flex flex-col gap-1.5 lg:gap-2">
            <h2 class="m-0 text-[22px] text-ink font-bold lg:text-[28px]">登录 / 注册</h2>
            <p class="m-0 text-sm text-muted leading-[1.7] lg:text-[14.5px]">用邮箱验证码就行，第一次登录会自动创建账号。</p>
          </div>

          <EmailLoginPanel />

          <div class="flex items-center gap-3 text-[13px] text-muted" aria-hidden="true">
            <span class="h-px flex-1 bg-line" />或<span class="h-px flex-1 bg-line" />
          </div>

          <div class="flex flex-col gap-2">
            <button
              type="button"
              class="auth-button is-outline"
              :disabled="!githubEnabled || githubLoading || githubRedirecting"
              :aria-busy="githubLoading || githubRedirecting ? 'true' : undefined"
              @click="loginWithGithub"
            >
              <span class="i-lucide-github text-[19px]" aria-hidden="true" />
              {{ githubLabel }}
            </button>
            <p v-if="githubLoadError" class="m-0 text-[12.5px] text-muted">
              读不到 GitHub 登录的状态。<button type="button" class="auth-link" @click="loadGithubStatus">重试</button>
            </p>
            <p v-else-if="!githubLoading && !githubEnabled && showGithubNote && githubNote" class="m-0 text-xs text-muted leading-[1.6]">
              开发提示：{{ githubNote }}
            </p>
          </div>

          <ul class="m-0 flex list-none flex-col gap-1.5 border-t border-t-solid border-line-soft p-0 pt-4 text-[12.5px] text-muted leading-[1.6]">
            <li class="flex gap-2"><span class="i-lucide-mail-question mt-[3px] text-sm" aria-hidden="true" />收不到验证码？先看看垃圾邮件箱。</li>
            <li class="flex gap-2"><span class="i-lucide-user-round-check mt-[3px] text-sm" aria-hidden="true" />老账号请用已绑定的邮箱或 GitHub 登录；都没绑定过的，请联系管理员恢复。</li>
          </ul>
        </template>

        <template v-else>
          <div class="flex flex-col gap-1.5 lg:gap-2">
            <h2 class="m-0 text-[22px] text-ink font-bold lg:text-[28px]">{{ adminLogin ? '管理登录' : '账号密码登录' }}</h2>
            <p class="m-0 text-sm text-muted leading-[1.7] lg:text-[14.5px]">{{ adminLogin ? '使用管理员账号和密码登录管理后台。' : '使用已有账号的用户名和密码登录。' }}</p>
          </div>
          <form class="flex flex-col gap-4" novalidate @submit.prevent="onLogin">
            <div class="flex flex-col gap-1.5">
              <label for="login-username" class="text-[13.5px] text-ink font-semibold">用户名</label>
              <input id="login-username" v-model="loginForm.username" class="auth-input" autocomplete="username" />
            </div>
            <div class="flex flex-col gap-1.5">
              <label for="login-password" class="text-[13.5px] text-ink font-semibold">密码</label>
              <input id="login-password" v-model="loginForm.password" type="password" class="auth-input" autocomplete="current-password" />
            </div>
            <button type="submit" class="auth-button is-primary mt-1" :disabled="submitting">
              {{ submitting ? '正在登录…' : '登录' }}
            </button>
          </form>
          <div class="flex flex-wrap justify-between gap-x-4 gap-y-2 text-[13px]">
            <RouterLink to="/forgot-password" class="auth-link">忘记密码？用已验证的邮箱找回</RouterLink>
          </div>
          <a v-if="isAdminHost" href="https://www.algohub.top/login" class="auth-link self-start text-[13px]">← 返回用户登录</a>
          <RouterLink v-else :to="emailModeLink" class="auth-link self-start text-[13px]">← 返回邮箱验证码登录</RouterLink>
        </template>


      </div>
    </section>
  </div>
</template>

<style scoped>
/* 深靛蓝方格纸（深浅色主题都一样） */
.brand-panel {
  display: flex;
  flex-direction: column;
  flex-shrink: 0;
  height: 300px;
  padding: 28px 20px 48px;
  background-color: #1e1b4b;
  background-image:
    linear-gradient(rgba(255, 255, 255, 0.05) 1px, transparent 1px),
    linear-gradient(90deg, rgba(255, 255, 255, 0.05) 1px, transparent 1px);
  background-size: 24px 24px;
  color: #fff;
}

.brand-inner {
  display: flex;
  flex: 1;
  flex-direction: column;
  justify-content: space-between;
  width: 100%;
  max-width: 440px;
  margin: 0 auto;
}

.brand-mark {
  display: flex;
  flex-shrink: 0;
  align-items: center;
  justify-content: center;
  width: 34px;
  height: 34px;
  border-radius: 10px;
}

.node-graph {
  display: none;
  width: 100%;
  height: auto;
}

.feature-card {
  display: flex;
  flex-direction: column;
  gap: 6px;
  padding: 14px;
  border: 1px solid rgba(255, 255, 255, 0.12);
  border-radius: 12px;
  background: rgba(255, 255, 255, 0.07);
}

/* 手机和平板：白色圆角面板压在品牌区下沿 */
.form-panel {
  flex: 1;
  margin-top: -24px;
  padding: 26px 20px 28px;
  border-radius: 24px 24px 0 0;
  background: var(--bg-elevated);
}

@media (min-width: 1024px) {
  .brand-panel {
    width: 46%;
    height: auto;
    min-height: 100vh;
    padding: 48px 40px;
    background-size: 28px 28px;
  }

  .brand-inner {
    gap: 32px;
    max-width: 632px;
    margin: 0;
  }

  .brand-mark {
    width: 40px;
    height: 40px;
    border-radius: 11px;
  }

  .node-graph {
    display: block;
  }

  .form-panel {
    display: flex;
    align-items: center;
    margin-top: 0;
    padding: 48px;
    border-radius: 0;
  }
}

@media (min-width: 1280px) {
  .brand-panel {
    width: 760px;
    padding: 56px 64px;
  }
}
</style>
