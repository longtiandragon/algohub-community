<script setup lang="ts">
import { reactive } from 'vue'
import { ElMessage } from 'element-plus'
import { notifyError, post } from '@/api/client'
import type { PlatformAccount } from '@/api/types'
import PlatformTag from '@/components/PlatformTag.vue'
import { platformName } from '@/utils/platformBrand'

/** 设置平台登录态：Cookie、User-Agent、附加请求头；可以先试用（不保存），保存时后端核对 UID */
const props = defineProps<{
  /** 页面的刷新：保存之后调用 */
  reload: () => Promise<unknown>
}>()

const credentialDialog = reactive({
  visible: false,
  accountId: 0,
  platform: '',
  value: '',
  userAgent: '',
  extraHeaders: '',
  testing: false,
  testResult: null as null | Record<string, unknown>
})

function open(row: PlatformAccount) {
  credentialDialog.visible = true
  credentialDialog.accountId = row.id
  credentialDialog.platform = row.platform
  credentialDialog.value = ''
  credentialDialog.userAgent = ''
  credentialDialog.extraHeaders = ''
  credentialDialog.testResult = null
}

/** 只要一项 Cookie 的平台：告诉同学复制哪一项 */
const COOKIE_HINT: Record<string, { placeholder: string; help: string }> = {
}

const NEWLINE = `
`

/** 后端只接受这几种附加请求头（其余的会被拒绝），在这里先说清楚 */
const ALLOWED_HEADERS = ['x-csrf-token', 'x-requested-with', 'referer', 'origin']

/** 附加请求头按「名称: 值」逐行填写，解析失败时明确报错而不是静默忽略。 */
function parseHeaders(): Record<string, string> {
  const result: Record<string, string> = {}
  for (const line of credentialDialog.extraHeaders.split(NEWLINE)) {
    const text = line.trim()
    if (!text) continue
    const idx = text.indexOf(':')
    if (idx <= 0) {
      throw new Error(`请求头格式应为「名称: 值」，无法解析：${text}`)
    }
    const name = text.slice(0, idx).trim()
    if (!ALLOWED_HEADERS.includes(name.toLowerCase())) {
      throw new Error(`请求头格式应为「名称: 值」，且只能填 ${ALLOWED_HEADERS.join('、')}：${name} 不支持`)
    }
    result[name] = text.slice(idx + 1).trim()
  }
  return result
}

function credentialBody() {
  return {
    cookie: credentialDialog.value.trim(),
    userAgent: credentialDialog.userAgent.trim() || null,
    headers: parseHeaders()
  }
}

async function testCredential() {
  credentialDialog.testing = true
  credentialDialog.testResult = null
  try {
    credentialDialog.testResult = await post<Record<string, unknown>>(
      `/accounts/${credentialDialog.accountId}/credential/test`,
      credentialBody()
    )
    ElMessage.success('凭据可用（本次未保存）')
  } catch (e) {
    if (e instanceof Error && e.message.startsWith('请求头格式')) {
      ElMessage.error(e.message)
    } else {
      notifyError(e)
    }
  } finally {
    credentialDialog.testing = false
  }
}

async function saveCredential() {
  try {
    await post(`/accounts/${credentialDialog.accountId}/credential`, credentialBody())
    ElMessage.success('已保存并验证登录态')
    credentialDialog.visible = false
    await props.reload()
  } catch (e) {
    notifyError(e)
  }
}

defineExpose({ open })
</script>

<template>
  <el-dialog
    v-model="credentialDialog.visible"
    title="设置平台登录态"
    width="min(640px, 94vw)"
    class="cred-dialog"
  >
    <div class="cred-intro">
      <div class="cred-intro-head">
        <span class="muted">平台</span>
        <PlatformTag
          :platform="credentialDialog.platform"
          :label="platformName(credentialDialog.platform)"
        />
      </div>
      <p class="muted" style="margin: 0">
        保存前后端会用这份凭据请求本人信息接口核对 UID，不一致时不会保存；
        采集器只使用统计所需的读取接口。凭据加密保存在平台服务器上，界面与日志都不会回显内容。
      </p>
    </div>

    <el-form label-position="top">
      <el-form-item label="Cookie">
        <el-input v-model="credentialDialog.value" type="password" show-password autocomplete="off" :placeholder="COOKIE_HINT[credentialDialog.platform]?.placeholder ?? 'key=value; key2=value2'" />
        <p v-if="COOKIE_HINT[credentialDialog.platform]" class="mb-0 mt-1.5 text-xs text-muted leading-[1.6]">{{ COOKIE_HINT[credentialDialog.platform].help }}</p>
      </el-form-item>
      <el-form-item label="User-Agent">
        <el-input v-model="credentialDialog.userAgent" placeholder="选填；带浏览器挑战的站点需要与取 Cookie 时的浏览器一致" />
      </el-form-item>
      <el-form-item label="附加请求头">
        <el-input
          v-model="credentialDialog.extraHeaders"
          type="textarea"
          :rows="2"
          placeholder="选填，每行一个，如：x-csrf-token: abc"
        />
        <p class="mb-0 mt-1.5 text-xs text-muted leading-[1.6]">只支持 x-csrf-token、x-requested-with、referer、origin，最多 10 个。</p>
      </el-form-item>
    </el-form>

    <el-alert type="info" :closable="false" show-icon>
      <div class="muted">
        有的站点（如带 Cloudflare 挑战的页面）放行凭据与浏览器 User-Agent 绑定，UA 不一致会重新出挑战页；
        有的接口还需要 Cookie 之外的 CSRF Token。可以先「试用」确认能不能取到数据，再决定是否保存。
      </div>
    </el-alert>

    <el-descriptions v-if="credentialDialog.testResult" :column="1" border size="small" style="margin-top: 12px">
      <el-descriptions-item label="凭据结构">{{ credentialDialog.testResult.credentialSummary }}</el-descriptions-item>
      <el-descriptions-item label="平台返回身份">
        {{ credentialDialog.testResult.resolvedHandle }}（UID {{ credentialDialog.testResult.resolvedUid }}）
      </el-descriptions-item>
      <el-descriptions-item label="与绑定账号一致">
        <el-tag size="small" :type="credentialDialog.testResult.uidMatchesBinding ? 'success' : 'danger'">
          {{ credentialDialog.testResult.uidMatchesBinding ? '一致' : '不一致' }}
        </el-tag>
      </el-descriptions-item>
      <el-descriptions-item label="说明">{{ credentialDialog.testResult.note }}</el-descriptions-item>
    </el-descriptions>

    <template #footer>
      <div class="cred-footer">
        <el-button @click="credentialDialog.visible = false">取消</el-button>
        <el-button :loading="credentialDialog.testing" @click="testCredential">试用（不保存）</el-button>
        <el-button type="primary" @click="saveCredential">保存并验证</el-button>
      </div>
    </template>
  </el-dialog>
</template>

<style scoped>
.cred-intro {
  background: var(--bg-subtle);
  border: 1px solid var(--border);
  border-radius: var(--radius-sm);
  padding: 10px 12px;
  margin-bottom: 14px;
}

.cred-intro-head {
  display: flex;
  align-items: center;
  gap: 6px;
  margin-bottom: 6px;
}

.cred-footer {
  display: flex;
  flex-wrap: wrap;
  justify-content: flex-end;
  gap: 8px;
}
</style>
