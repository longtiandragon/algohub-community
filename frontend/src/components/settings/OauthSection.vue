<script setup lang="ts">
import { confirmIdentity } from '@/api/securityAction'
import { ElMessage, ElMessageBox } from 'element-plus'
import { notifyError, post } from '@/api/client'
import { startGithubBind } from '@/api/githubAuthorize'
import SectionCard from '@/components/ui/SectionCard.vue'
import type { TokenView } from '@/api/types'
import { useAuthStore } from '@/stores/auth'
import { httpUrl } from '@/utils/url'

/**
 * 第三方账号：目前只有 GitHub。绑定后可以用 GitHub 直接登录；只读公开资料和邮箱地址，
 * 不读仓库，也不保存 GitHub 的访问令牌，绑定不改本站的登录邮箱。
 */
defineProps<{ githubEnabled: boolean; githubNote: string }>()
const auth = useAuthStore()
const DEV = import.meta.env.DEV

/** 绑定前先确认本人（邮箱验证码或密码）；绑定后其他设备退出，这台设备保持登录 */
async function bindGithub() {
  try {
    const actionToken = await confirmIdentity('BIND_GITHUB')
    try {
      sessionStorage.setItem('security-return', '/settings?tab=security')
    } catch {
      // 存不了就回首页
    }
    await startGithubBind(actionToken)
  } catch (e) {
    if (e !== 'cancel' && e !== 'close') notifyError(e)
  }
}

async function unbindGithub() {
  try {
    await ElMessageBox.confirm('解绑后将无法用 GitHub 登录，可随时重新绑定。继续？', '解绑 GitHub', { type: 'warning' })
    const actionToken = await confirmIdentity('UNBIND_GITHUB')
    if (!await auth.apply(await post<TokenView>('/auth/github/unbind', { actionToken }))) return
    ElMessage.success('已解绑 GitHub，其他设备已退出登录')
  } catch (e) {
    if (e !== 'cancel' && e !== 'close') notifyError(e)
  }
}
const formatTime = (value: string | null | undefined) => (value ? new Date(value).toLocaleString('zh-CN', { timeZone: 'Asia/Shanghai', hour12: false }) : '—')
</script>

<template>
  <SectionCard title="第三方账号" data-test="settings-oauth">
    <ul v-if="auth.me?.bindings?.length" class="m-0 flex list-none flex-col gap-3 p-0">
      <li v-for="b in auth.me.bindings" :key="b.provider" class="flex flex-wrap items-center gap-3">
        <img v-if="httpUrl(b.avatarUrl)" :src="httpUrl(b.avatarUrl)" alt="" referrerpolicy="no-referrer" class="size-9 rounded-full object-cover">
        <span v-else class="i-lucide-github size-9 text-ink" aria-hidden="true" />
        <span class="min-w-0 flex flex-1 flex-col">
          <span class="text-sm text-ink font-semibold">{{ b.provider }} · {{ b.login }}</span>
          <span class="text-xs text-muted">绑定于 {{ formatTime(b.linkedAt) }}</span>
        </span>
        <a v-if="httpUrl(b.profileUrl)" :href="httpUrl(b.profileUrl)" target="_blank" rel="noopener noreferrer" class="text-[13.5px]">查看主页</a>
        <el-button size="small" type="danger" plain @click="unbindGithub">解绑</el-button>
      </li>
    </ul>
    <div v-else class="flex flex-col items-start gap-3">
      <p class="m-0 text-[13.5px] text-body leading-relaxed">
        绑定后可以用 GitHub 直接登录。只读取公开资料和邮箱地址，不读取仓库内容，也不保存 GitHub 的访问令牌；绑定不会改动你在本站的登录邮箱。
      </p>
      <el-button :disabled="!githubEnabled" @click="bindGithub"><span class="i-lucide-github mr-1.5" aria-hidden="true" />绑定 GitHub</el-button>
      <p v-if="!githubEnabled" class="m-0 text-[12.5px] text-muted">{{ DEV && githubNote ? `开发提示：${githubNote}` : 'GitHub 登录暂未开放，开放后可以在这里绑定。' }}</p>
    </div>
  </SectionCard>
</template>
