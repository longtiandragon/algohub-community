<script setup lang="ts">
import { adminLoginPath } from '@/utils/adminHost'
import { computed, onBeforeUnmount, onMounted, reactive, ref } from 'vue'
import { onBeforeRouteLeave, onBeforeRouteUpdate, useRoute, useRouter } from 'vue-router'
import { ElMessage, ElMessageBox } from 'element-plus'
import { get, notifyError, post } from '@/api/client'
import type { AuthEvent, Membership, PrivacySettings, SessionInfo } from '@/api/types'
import EmailIdentityPanel from '@/components/EmailIdentityPanel.vue'
import AvatarSection from '@/components/settings/AvatarSection.vue'
import DataSection from '@/components/settings/DataSection.vue'
import DevicesSection from '@/components/settings/DevicesSection.vue'
import EventsSection from '@/components/settings/EventsSection.vue'
import OauthSection from '@/components/settings/OauthSection.vue'
import SaveBar from '@/components/settings/SaveBar.vue'
import SchoolSection from '@/components/settings/SchoolSection.vue'
import PageHeader from '@/components/ui/PageHeader.vue'
import RadioCards from '@/components/ui/RadioCards.vue'
import SectionCard from '@/components/ui/SectionCard.vue'
import SegmentedControl from '@/components/ui/SegmentedControl.vue'
import UserAvatar from '@/components/ui/UserAvatar.vue'
import { useBreakpoint } from '@/composables/useBreakpoint'
import { useTopBarOverride } from '@/composables/useTopBarOverride'
import { useAuthStore } from '@/stores/auth'
import { THEME_MODES, useThemeStore } from '@/stores/theme'

/**
 * 设置与安全。分组用 ?section= 记住：
 * - 电脑、平板：左边分组导航，右边这一组的卡片（最宽 720）；
 * - 手机：没带 section 时是分组列表（「展示平台账号」开关可直接切），点一行进子页，顶栏写子页标题、返回到列表。
 * 个人资料、学校与年级、隐私的表单改了之后底部出现深色保存条（放弃 / 保存）；外观选了马上生效，和侧栏、「我的」页用同一个主题设置；有没保存的修改时切分组、离开页面、关标签页都会先问一句。
 * 旧链接的 ?tab=（login-email、profile、privacy、security）照样能用。
 */
const SECTIONS = [
  { key: 'profile', label: '个人资料', group: '账号', icon: 'i-lucide-user-round' },
  { key: 'school', label: '学校与年级', group: '账号', icon: 'i-lucide-school' },
  { key: 'login-email', label: '登录邮箱', group: '账号', icon: 'i-lucide-mail' },
  { key: 'privacy', label: '隐私', group: '隐私', icon: 'i-lucide-eye' },
  { key: 'devices', label: '登录设备', group: '安全', icon: 'i-lucide-monitor-smartphone' },
  { key: 'oauth', label: '第三方账号', group: '安全', icon: 'i-lucide-link-2' },
  { key: 'events', label: '登录记录', group: '安全', icon: 'i-lucide-history' },
  { key: 'appearance', label: '外观', group: '外观', icon: 'i-lucide-palette' },
  { key: 'data', label: '数据与注销', group: '数据', icon: 'i-lucide-database' }
] as const
type SectionKey = (typeof SECTIONS)[number]['key']
const LEGACY_TABS: Record<string, SectionKey> = { 'login-email': 'login-email', profile: 'profile', privacy: 'privacy', security: 'devices' }
const GROUPS = ['账号', '隐私', '安全', '外观', '数据'] as const

const auth = useAuthStore()
const themeStore = useThemeStore()
const themeOptions = THEME_MODES.map((m) => ({ value: m.value, label: m.label, icon: m.icon, description: m.hint || (m.value === 'light' ? '白天、屏幕亮的时候' : '晚上看更舒服') }))
const VISIBILITY_OPTIONS = [
  { value: 'SCHOOL' as const, label: '本校成员', icon: 'i-lucide-school', description: '只有同一所学校的成员能看到' },
  { value: 'PUBLIC' as const, label: '所有人', icon: 'i-lucide-globe', description: '任何登录用户都能看到我的档案（新用户默认）' },
  { value: 'PRIVATE' as const, label: '仅自己', icon: 'i-lucide-lock', description: '普通用户无法打开；具备权限的平台管理员及本校管理员因管理需要可以查看，访问会留痕' }
]
const router = useRouter()
const route = useRoute()
const { isMobile } = useBreakpoint()

const section = computed<SectionKey | null>(() => {
  const asked = typeof route.query.section === 'string' ? route.query.section : typeof route.query.tab === 'string' ? LEGACY_TABS[route.query.tab] : undefined
  if (asked && SECTIONS.some((s) => s.key === asked)) return asked as SectionKey
  return isMobile.value ? null : 'profile'
})
const current = computed(() => SECTIONS.find((s) => s.key === section.value) ?? null)
/** 电脑上换分组不在浏览器历史里堆一串；手机从列表进子页，返回键能回到列表 */
const linkTo = (key: SectionKey) => ({ path: '/settings', query: { section: key } })
useTopBarOverride(() => (isMobile.value && current.value ? { title: current.value.label, parent: '/settings', parentTitle: '设置与安全' } : null))

/* ------------------------------ 数据 ------------------------------ */
const loading = ref(false)
const sessions = ref<SessionInfo[]>([])
const events = ref<AuthEvent[]>([])
const githubEnabled = ref(false)
const githubNote = ref('')

const profileForm = reactive({ displayName: '', email: '', bio: '' })
const privacy = reactive<PrivacySettings>({ profileVisibility: 'SCHOOL', publicActivity: true, showOnRanking: true, showAccounts: true, showStudentNo: false, showEmail: false })
const passwordForm = reactive({ oldPassword: '', newPassword: '', confirmPassword: '' })
/** 学校与年级：改的是这条入校记录（有已通过的用它，否则第一条）；学号从成员接口读，保存时原样带回 */
const schoolMember = ref<Membership | null>(null)
const schoolForm = reactive<{ enrollmentYear: number | null; className: string; studentNo: string }>({ enrollmentYear: null, className: '', studentNo: '' })
/** 表单的原样（从服务器读到的），用来判断改没改、放弃时还原 */
const profileBase = ref('')
const privacyBase = ref('')
const schoolBase = ref('')

async function loadMembership() {
  try {
    const list = await get<Membership[]>('/schools/memberships')
    const member = list.find((m) => m.status === 'APPROVED') ?? list[0] ?? null
    schoolMember.value = member
    schoolForm.enrollmentYear = member?.enrollmentYear ?? auth.me?.enrollmentYear ?? null
    schoolForm.className = member?.className ?? auth.me?.className ?? ''
    schoolForm.studentNo = member?.studentNo ?? auth.me?.studentNo ?? ''
  } catch {
    schoolMember.value = null
  }
  schoolBase.value = JSON.stringify(schoolForm)
}

function syncFromStore() {
  profileForm.displayName = auth.me?.displayName ?? ''
  profileForm.email = auth.me?.email ?? ''
  profileForm.bio = auth.me?.bio ?? ''
  Object.assign(privacy, auth.me?.privacy ?? privacy)
  profileBase.value = JSON.stringify(profileForm)
  privacyBase.value = JSON.stringify(privacy)
}

async function load() {
  loading.value = true
  try {
    await auth.refreshMe()
    syncFromStore()
    await loadMembership()
    const [s, e, g] = await Promise.all([
      get<SessionInfo[]>('/auth/sessions'),
      get<AuthEvent[]>('/auth/events', { limit: 200 }),
      get<{ enabled: boolean; note: string }>('/auth/github/status')
    ])
    sessions.value = s
    events.value = e
    githubEnabled.value = g.enabled
    githubNote.value = g.note
  } catch (e) {
    notifyError(e)
  } finally {
    loading.value = false
  }
}

/* ------------------------------ 保存条 ------------------------------ */
const profileDirty = computed(() => profileBase.value !== '' && JSON.stringify(profileForm) !== profileBase.value)
const privacyDirty = computed(() => privacyBase.value !== '' && JSON.stringify(privacy) !== privacyBase.value)
const schoolDirty = computed(() => schoolBase.value !== '' && JSON.stringify(schoolForm) !== schoolBase.value)
const dirty = computed(() => profileDirty.value || privacyDirty.value || schoolDirty.value)
const saving = ref(false)

async function save() {
  if (!dirty.value || saving.value) return
  if (schoolDirty.value && (!schoolForm.className.trim() || !schoolForm.studentNo.trim() || !schoolForm.enrollmentYear)) { ElMessage.warning('请填写年级、班级和学号'); return }
  saving.value = true
  try {
    if (profileDirty.value) await post('/me/profile', { ...profileForm })
    if (privacyDirty.value) await post('/me/privacy', { ...privacy })
    if (schoolDirty.value && schoolMember.value) {
      await post(`/schools/memberships/${schoolMember.value.id}`, {
        enrollmentYear: schoolForm.enrollmentYear,
        className: schoolForm.className.trim() || null,
        studentNo: schoolForm.studentNo.trim() || null
      })
    }
    if (schoolDirty.value && !schoolMember.value) await post('/me/academic-profile', schoolForm)
    await auth.refreshMe()
    syncFromStore()
    await loadMembership()
    ElMessage.success('已保存')
  } catch (e) {
    notifyError(e)
  } finally {
    saving.value = false
  }
}
function discard() {
  if (profileBase.value) Object.assign(profileForm, JSON.parse(profileBase.value))
  if (privacyBase.value) Object.assign(privacy, JSON.parse(privacyBase.value))
  if (schoolBase.value) Object.assign(schoolForm, JSON.parse(schoolBase.value))
}

/** 有没保存的修改时，切分组、离开页面前问一句；选「不保存」就还原 */
async function confirmLeave() {
  if (!dirty.value) return true
  try {
    await ElMessageBox.confirm('有修改还没保存，离开后会丢失。', '还没保存', {
      confirmButtonText: '不保存，离开',
      cancelButtonText: '留下来',
      type: 'warning'
    })
    discard()
    return true
  } catch {
    return false
  }
}
onBeforeRouteLeave(confirmLeave)
onBeforeRouteUpdate((to, from) => (to.query.section !== from.query.section || to.query.tab !== from.query.tab ? confirmLeave() : true))
function beforeUnload(event: BeforeUnloadEvent) {
  if (!dirty.value) return
  event.preventDefault()
  event.returnValue = ''
}
onMounted(() => {
  void load()
  window.addEventListener('beforeunload', beforeUnload)
})
onBeforeUnmount(() => window.removeEventListener('beforeunload', beforeUnload))

/* ------------------------------ 手机列表里的开关：直接保存 ------------------------------ */
const quickSaving = ref<string | null>(null)
async function quickToggle(key: 'showAccounts', value: boolean) {
  quickSaving.value = key
  try {
    await post('/me/privacy', { ...JSON.parse(privacyBase.value || JSON.stringify(privacy)), [key]: value })
    await auth.refreshMe()
    syncFromStore()
    ElMessage.success(value ? '已打开' : '已关闭')
  } catch (e) {
    notifyError(e)
  } finally {
    quickSaving.value = null
  }
}

/* ------------------------------ 账号安全 ------------------------------ */
const changingPassword = ref(false)
async function changePassword() {
  if (changingPassword.value) return
  if (passwordForm.newPassword !== passwordForm.confirmPassword) {
    ElMessage.warning('两次输入的新密码不一致')
    return
  }
  changingPassword.value = true
  try {
    await post('/me/password', { oldPassword: passwordForm.oldPassword, newPassword: passwordForm.newPassword })
    ElMessage.success('密码已修改，所有设备已退出，请重新登录')
    auth.clear()
    void router.push(adminLoginPath)
  } catch (e) {
    notifyError(e)
  } finally {
    changingPassword.value = false
  }
}
async function logoutAll() {
  try {
    await ElMessageBox.confirm('将撤销所有设备的登录状态，包括当前设备。继续？', '退出所有设备', { type: 'warning' })
  } catch {
    return
  }
  try {
    await auth.logoutAll()
  } catch (e) {
    notifyError(e)
  } finally {
    // 本地登录状态已经清掉，不管服务端成没成功都回登录页
    void router.push('/login')
  }
}

/* ------------------------------ 列表里每一行右边的说明 ------------------------------ */
const VISIBILITY: Record<string, string> = { PUBLIC: '所有人', SCHOOL: '本校成员', PRIVATE: '仅自己' }
function maskEmail(email: string | null | undefined) {
  if (!email) return '还没有登录邮箱'
  const [name, domain] = email.split('@')
  return `${name.slice(0, Math.min(4, Math.max(1, name.length - 1)))}***@${domain ?? ''}`
}
const values = computed<Record<SectionKey, string>>(() => {
  const m = auth.approvedMembership
  return {
    profile: '',
    school: m ? [m.enrollmentYear ? `${m.enrollmentYear} 级` : '', m.className].filter(Boolean).join(' · ') || m.schoolName : auth.me?.memberships?.length ? '待审核' : '未加入',
    'login-email': auth.me?.emailVerified ? '已验证' : '未验证',
    privacy: VISIBILITY[JSON.parse(privacyBase.value || '{}').profileVisibility ?? 'SCHOOL'] ?? '',
    devices: sessions.value.length ? `${sessions.value.length} 台` : '',
    oauth: auth.me?.bindings?.length ? auth.me.bindings.map((b) => (b.provider.toLowerCase() === 'github' ? 'GitHub' : b.provider)).join('、') : '未绑定',
    events: '',
    appearance: THEME_MODES.find((m) => m.value === themeStore.mode)?.label ?? '',
    data: 'JSON'
  }
})
const formatTime = (value: string | null | undefined) => (value ? new Date(value).toLocaleString('zh-CN', { timeZone: 'Asia/Shanghai', hour12: false }) : '—')
</script>

<template>
  <div class="page" data-test="page">
    <PageHeader title="设置与安全">
      <template v-if="!isMobile" #description>个人资料、学校与年级、隐私、登录设备和第三方账号都在这里管理。新用户档案默认对所有登录用户可见，已有用户保留原设置；学号和邮箱默认不公开。</template>
    </PageHeader>

    <!-- 手机：分组列表 -->
    <div v-if="isMobile && !section" class="flex flex-col gap-[18px]" data-test="settings-list">
      <RouterLink :to="linkTo('profile')" class="profile-link">
        <UserAvatar :src="auth.me?.avatarUrl" :name="auth.me?.displayName" class="avatar" />
        <span class="min-w-0 flex flex-1 flex-col gap-0.5">
          <span class="truncate text-[17px] text-ink font-semibold">{{ auth.me?.displayName }}</span>
          <span class="truncate text-[12.5px] text-muted">{{ maskEmail(auth.me?.email) }}{{ auth.me?.email ? (auth.me?.emailVerified ? ' · 已验证' : ' · 未验证') : '' }}</span>
        </span>
        <span class="i-lucide-chevron-right shrink-0 text-lg text-faint" aria-hidden="true" />
      </RouterLink>

      <section v-for="group in GROUPS" :key="group" class="flex flex-col gap-1.5">
        <h2 class="m-0 px-1 text-[12.5px] text-muted font-normal">{{ group }}</h2>
        <SegmentedControl
          v-if="group === '外观'"
          :model-value="themeStore.mode"
          :options="THEME_MODES.map((m) => ({ label: m.label, value: m.value }))"
          label="外观"
          block
          class="theme-switch"
          data-test="quick-theme"
          @update:model-value="themeStore.setMode($event)"
        />
        <ul v-else class="m-0 list-none overflow-hidden border border-line rounded-card border-solid bg-surface p-0">
          <template v-for="item in SECTIONS.filter((s) => s.group === group)" :key="item.key">
            <li v-if="item.key === 'data'" class="list-row-wrap">
              <RouterLink :to="linkTo('data')" class="list-row">
                <span class="flex-1 text-[15px] text-ink">导出我的数据</span>
                <span class="text-[13.5px] text-muted">JSON</span>
                <span class="i-lucide-chevron-right text-[17px] text-faint" aria-hidden="true" />
              </RouterLink>
            </li>
            <li v-if="item.key === 'data'" class="list-row-wrap">
              <RouterLink :to="linkTo('data')" class="list-row">
                <span class="flex-1 text-[15px] text-danger-ink">注销账号</span>
                <span class="i-lucide-chevron-right text-[17px] text-faint" aria-hidden="true" />
              </RouterLink>
            </li>
            <li v-else class="list-row-wrap">
              <RouterLink :to="linkTo(item.key)" class="list-row" :data-test="`settings-row-${item.key}`">
                <span class="flex-1 text-[15px] text-ink">{{ item.key === 'privacy' ? '谁能看到我的档案' : item.label }}</span>
                <span v-if="values[item.key]" class="max-w-[55%] truncate text-[13.5px] text-muted">{{ values[item.key] }}</span>
                <span class="i-lucide-chevron-right shrink-0 text-[17px] text-faint" aria-hidden="true" />
              </RouterLink>
            </li>
            <template v-if="item.key === 'privacy'">
              <li class="list-row-wrap">
                <div class="list-row">
                  <span id="quick-accounts" class="flex-1 text-[15px] text-ink">展示我的平台账号</span>
                  <el-switch :model-value="JSON.parse(privacyBase || '{}').showAccounts ?? true" :loading="quickSaving === 'showAccounts'" aria-label="展示我的平台账号" data-test="quick-accounts" @change="quickToggle('showAccounts', Boolean($event))" />
                </div>
              </li>
            </template>
          </template>
        </ul>
      </section>
    </div>

    <!-- 电脑、平板：左边分组导航 + 右边这一组；手机：子页 -->
    <div v-else class="grid grid-cols-1 items-start gap-6 md:grid-cols-[196px_minmax(0,720px)]">
      <nav v-if="!isMobile" aria-label="设置分组" class="flex flex-col gap-4 md:(sticky top-6)" data-test="settings-nav">
        <div v-for="group in GROUPS" :key="group" class="flex flex-col gap-0.5">
          <span class="px-3 pb-1 text-xs text-muted">{{ group }}</span>
          <RouterLink
            v-for="item in SECTIONS.filter((s) => s.group === group)"
            :key="item.key"
            :to="linkTo(item.key)"
            replace
            class="nav-item"
            :class="{ 'is-active': section === item.key }"
            :aria-current="section === item.key ? 'page' : undefined"
          >
            <span :class="item.icon" class="text-[17px]" aria-hidden="true" />{{ item.label }}
          </RouterLink>
        </div>
      </nav>

      <div class="min-w-0 flex flex-col gap-4" :data-test="`section-${section}`">
        <h2 v-if="!isMobile && current" class="m-0 text-lg text-ink font-semibold">{{ current.label }}</h2>

        <!-- 个人资料 -->
        <template v-if="section === 'profile'">
          <AvatarSection />
          <SectionCard title="基本信息" description="改完后点底部的「保存」。">
            <el-form label-position="top" @submit.prevent="save">
              <el-form-item label="昵称">
                <el-input v-model="profileForm.displayName" maxlength="32" show-word-limit />
              </el-form-item>
              <el-form-item label="联系邮箱（选填）">
                <el-input v-model="profileForm.email" placeholder="请输入联系邮箱地址" aria-describedby="contact-email-help" />
                <p id="contact-email-help" class="mb-0 mt-2 text-[12.5px] text-muted leading-5">用于个人档案，不用于登录。是否向其他同学展示，可在「隐私」中设置。</p>
              </el-form-item>
              <el-form-item label="简介" class="!mb-0">
                <el-input v-model="profileForm.bio" type="textarea" :rows="3" maxlength="255" show-word-limit />
              </el-form-item>
            </el-form>
          </SectionCard>
          <SectionCard title="账号信息">
            <dl class="m-0 grid grid-cols-[88px_minmax(0,1fr)] gap-x-3 gap-y-2.5 text-[13.5px]">
              <dt class="text-muted">{{ auth.me?.username?.startsWith('mail_') ? '系统账号标识' : '用户名' }}</dt><dd class="m-0 break-all text-ink">{{ auth.me?.username }}<p v-if="auth.me?.username?.startsWith('mail_')" class="m-0 mt-1 text-xs text-muted">邮箱注册时自动生成，无需记忆。登录使用邮箱，榜单和动态展示昵称。</p></dd>
              <dt class="text-muted">角色</dt><dd class="m-0 text-ink">{{ auth.isAdmin ? '管理员' : '学生' }}</dd>
              <dt class="text-muted">注册时间</dt><dd class="m-0 text-ink">{{ formatTime(auth.me?.createdAt) }}</dd>
              <dt class="text-muted">上次登录</dt><dd class="m-0 text-ink">{{ formatTime(auth.me?.lastLoginAt) }}</dd>
            </dl>
          </SectionCard>
        </template>

        <SchoolSection v-else-if="section === 'school'" :membership="schoolMember" :form="schoolForm" />

        <!-- 登录邮箱；管理员还有密码 -->
        <template v-else-if="section === 'login-email'">
          <EmailIdentityPanel />
          <!-- 普通用户使用邮箱或 GitHub；管理员保留独立密码入口 /admin/login -->
          <SectionCard v-if="auth.isAdmin" :title="auth.me?.hasPassword ? '修改密码' : '设置密码（可选）'">
            <div v-if="!auth.me?.hasPassword" class="flex flex-col items-start gap-3">
              <p class="m-0 text-[13.5px] text-body">可以继续用邮箱验证码登录，不用设置密码。需要密码登录时，先验证已绑定的邮箱。</p>
              <el-button @click="router.push('/forgot-password?mode=set')">通过邮箱验证设置密码</el-button>
            </div>
            <el-form v-else label-position="top" @submit.prevent="changePassword">
              <el-form-item label="当前密码">
                <el-input v-model="passwordForm.oldPassword" type="password" show-password autocomplete="current-password" />
              </el-form-item>
              <el-form-item label="新密码">
                <el-input v-model="passwordForm.newPassword" type="password" show-password autocomplete="new-password" placeholder="至少 8 位" />
              </el-form-item>
              <el-form-item label="确认新密码">
                <el-input v-model="passwordForm.confirmPassword" type="password" show-password autocomplete="new-password" />
              </el-form-item>
              <el-button type="primary" native-type="submit" :loading="changingPassword">修改密码</el-button>
              <p class="mb-0 mt-2 text-[12.5px] text-muted">修改后所有设备的登录状态立即失效，需要重新登录。</p>
            </el-form>
          </SectionCard>
        </template>

        <!-- 隐私 -->
        <template v-else-if="section === 'privacy'">
          <SectionCard title="谁能看到我的档案">
            <p class="mt-0 text-[12.5px] text-muted leading-5">总榜会向登录用户展示参榜者的昵称、学校和成绩；档案的其他内容仍按这里的范围控制。设为「仅自己」则不参与榜单。</p>
            <RadioCards v-model="privacy.profileVisibility" :options="VISIBILITY_OPTIONS" label="谁能看到我的档案" data-test="visibility" />
          </SectionCard>
          <SectionCard title="展示与公开">
            <ul class="m-0 flex list-none flex-col p-0">
              <li class="switch-row">
                <span class="flex flex-1 flex-col"><span class="text-sm text-ink">公开动态</span><span class="text-[12.5px] text-muted">默认开启，动态同时展示在公开和校内；关闭后仅展示在校内。档案设为仅自己时均不展示。</span></span>
                <el-switch v-model="privacy.publicActivity" aria-label="公开动态" />
              </li>
              <li class="switch-row">
                <span class="flex flex-1 flex-col"><span id="sw-accounts" class="text-sm text-ink">展示我绑定的平台账号</span><span class="text-[12.5px] text-muted">关闭后别人看不到你在各 OJ 上的账号名</span></span>
                <el-switch v-model="privacy.showAccounts" aria-label="展示我绑定的平台账号" />
              </li>
              <li class="switch-row">
                <span class="flex flex-1 flex-col"><span id="sw-student" class="text-sm text-ink">公开学号</span><span class="text-[12.5px] text-muted">默认关闭。学号是身份信息，只用于成员管理</span></span>
                <el-switch v-model="privacy.showStudentNo" aria-label="公开学号" />
              </li>
              <li class="switch-row">
                <span class="flex flex-1 flex-col"><span id="sw-email" class="text-sm text-ink">在个人档案中展示联系邮箱</span><span class="text-[12.5px] text-muted">关闭后不向其他同学展示</span></span>
                <el-switch v-model="privacy.showEmail" aria-label="在个人档案中展示联系邮箱" />
              </li>
            </ul>
          </SectionCard>
        </template>

        <DevicesSection v-else-if="section === 'devices'" :sessions="sessions" :loading="loading" @logout-all="logoutAll" />
        <OauthSection v-else-if="section === 'oauth'" :github-enabled="githubEnabled" :github-note="githubNote" />
        <EventsSection v-else-if="section === 'events'" :events="events" :loading="loading" />
        <SectionCard v-else-if="section === 'appearance'" title="深浅色" description="选了马上生效，只记在这台设备上；侧栏的个人菜单、「我的」页里的是同一个设置。">
          <RadioCards :model-value="themeStore.mode" :options="themeOptions" label="深浅色" data-test="theme-cards" @update:model-value="themeStore.setMode($event)" />
        </SectionCard>
        <DataSection v-else-if="section === 'data'" />
      </div>
    </div>

    <SaveBar v-if="dirty" :saving="saving" @discard="discard" @save="save" />
  </div>
</template>

<style scoped>
.profile-link {
  display: flex;
  align-items: center;
  gap: 14px;
  padding: 14px 16px;
  border: 1px solid var(--border);
  border-radius: var(--radius);
  background: var(--bg-elevated);
  color: inherit;
  text-decoration: none;
}

.avatar {
  display: flex;
  flex-shrink: 0;
  align-items: center;
  justify-content: center;
  width: 52px;
  height: 52px;
  border-radius: 50%;
  background: var(--brand-btn);
  color: #fff;
  font-size: 21px;
  font-weight: 600;
}

.list-row-wrap + .list-row-wrap {
  border-top: 1px solid var(--border-soft);
}

.list-row {
  display: flex;
  align-items: center;
  gap: 12px;
  min-height: 52px;
  padding: 0 14px 0 16px;
  color: inherit;
  text-decoration: none;
}

.nav-item {
  display: flex;
  align-items: center;
  gap: 10px;
  height: 38px;
  padding: 0 12px;
  border-radius: 10px;
  color: var(--text);
  font-size: 14px;
  text-decoration: none;
}

.nav-item:hover {
  background: var(--bg-subtle);
}

.nav-item.is-active {
  background: var(--brand-soft);
  color: var(--brand-600);
  font-weight: 600;
}

.switch-row {
  display: flex;
  align-items: center;
  gap: 16px;
  padding: 12px 0;
}

.switch-row + .switch-row {
  border-top: 1px solid var(--border-soft);
}

.switch-row:first-child {
  padding-top: 0;
}

.switch-row:last-child {
  padding-bottom: 0;
}

/* 手机列表里的外观分段按钮：高一点、好点 */
.theme-switch :deep(.segmented-item) {
  height: 38px;
  font-size: 14px;
}

.profile-link:focus-visible,
.list-row:focus-visible,
.nav-item:focus-visible {
  outline: 2px solid var(--brand-500);
  outline-offset: -2px;
}
</style>
