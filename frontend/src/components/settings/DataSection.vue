<script setup lang="ts">
import { confirmIdentity } from '@/api/securityAction'
import { useRouter } from 'vue-router'
import { ElMessage, ElMessageBox } from 'element-plus'
import { get, notifyError, post } from '@/api/client'
import SectionCard from '@/components/ui/SectionCard.vue'
import { useAuthStore } from '@/stores/auth'

/**
 * 数据与注销：导出我的数据（JSON，不含密码、令牌和平台登录态）；注销账号（输入用户名确认，有密码的再输密码），不可恢复。
 */
const auth = useAuthStore()
const router = useRouter()

async function exportData() {
  try {
    const data = await get<Record<string, unknown>>('/me/export')
    const blob = new Blob([JSON.stringify(data, null, 2)], { type: 'application/json' })
    const url = URL.createObjectURL(blob)
    const link = document.createElement('a')
    link.href = url
    link.download = `algohub-me-${auth.me?.username ?? 'export'}.json`
    link.click()
    // 立刻释放的话有的浏览器还没开始下载
    setTimeout(() => URL.revokeObjectURL(url), 1000)
    ElMessage.success('已导出（不含密码与平台凭据）')
  } catch (e) {
    notifyError(e)
  }
}

async function deleteAccount() {
  try {
    const { value } = await ElMessageBox.prompt(
      `注销会删除你的账号、绑定的平台账号、同步记录与统计数据，且不可恢复。请输入用户名 ${auth.me?.username} 确认：`,
      '注销账号',
      { type: 'error', confirmButtonText: '确认注销', inputPlaceholder: '输入用户名' }
    )
    const actionToken = await confirmIdentity('DELETE_ACCOUNT')
    await post('/me/delete', { actionToken, confirmUsername: value })
    auth.clear()
    ElMessage.success('账号已注销')
    router.push('/login')
  } catch (e) {
    if (e !== 'cancel' && e !== 'close') notifyError(e)
  }
}
</script>

<template>
  <div class="flex flex-col gap-4" data-test="settings-data">
    <SectionCard title="导出我的数据">
      <div class="flex flex-col items-start gap-3">
        <p class="m-0 text-[13.5px] text-body leading-relaxed">
          导出个人资料、入校状态和认证记录（JSON）；<strong>不含密码、访问令牌和平台登录态</strong>。平台凭据只以加密形式保存在服务器上，不能导出。
        </p>
        <el-button @click="exportData"><span class="i-lucide-download mr-1.5" aria-hidden="true" />导出我的数据（JSON）</el-button>
      </div>
    </SectionCard>
    <SectionCard class="danger-card">
      <template #title><span class="text-danger-ink">注销账号</span></template>
      <div class="flex flex-col items-start gap-3">
        <p class="m-0 text-[13.5px] text-body leading-relaxed">注销会删除账号、绑定的平台账号、同步到的提交和统计数据，而且不能恢复。建议先导出数据。</p>
        <el-button type="danger" @click="deleteAccount">注销我的账号</el-button>
      </div>
    </SectionCard>
  </div>
</template>

<style scoped>
.danger-card {
  border-color: color-mix(in srgb, var(--danger) 45%, var(--border));
}
</style>
