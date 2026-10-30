<script setup lang="ts">
import { computed } from 'vue'
import { ElMessage } from 'element-plus'
import type { BindingRequest } from '@/api/types'
import { platformName } from '@/utils/platformBrand'

/**
 * 向导第四步：用公开资料验证归属。复制验证串 → 按后端返回的位置粘贴并保存 → 回来立即验证。
 * 位置、说明和有效期都用这条申请里后端给的值（各平台 ownershipProof()），不在前端写死。
 */
const props = defineProps<{
  request: BindingRequest
  /** 平台首页，给「打开平台」的链接 */
  homeUrl?: string
  /** 上一次核验失败的原因：留在这里，同学改完资料通常要来回核验几次 */
  error?: string
  /** 现在的时间（向导打开期间定时走），判断验证串过没过期 */
  now: number
}>()

const expired = computed(() => props.request.expired || Date.parse(props.request.expiresAt) <= props.now)
const until = computed(() =>
  new Date(props.request.expiresAt).toLocaleTimeString('zh-CN', { timeZone: 'Asia/Shanghai', hour: '2-digit', minute: '2-digit', hour12: false })
)

async function copyToken() {
  try {
    await navigator.clipboard.writeText(props.request.token)
    ElMessage.success('验证串已复制')
  } catch {
    ElMessage.info('浏览器不允许自动复制，请手动选中验证串复制')
  }
}
</script>

<template>
  <div class="flex flex-col gap-4">
    <el-alert
      v-if="request.ownedByOther"
      type="warning"
      :closable="false"
      show-icon
      title="这个账号已被另一位同学验证"
      description="核验成功后会生成申诉交管理员审核，不会自动改动任何人的记录。"
    />

    <ol class="m-0 flex list-none flex-col gap-4 p-0">
      <li class="flex gap-3">
        <span class="step-no num" aria-hidden="true">1</span>
        <div class="min-w-0 flex flex-1 flex-col gap-2">
          <span class="text-[14.5px] text-ink leading-6">复制这段验证串</span>
          <div class="flex gap-2">
            <input class="token mono" :value="request.token" readonly aria-label="公开资料验证串" @focus="($event.target as HTMLInputElement).select()" />
            <el-button size="large" class="shrink-0" @click="copyToken">
              <span class="i-lucide-copy mr-1.5 text-base" aria-hidden="true" />复制
            </el-button>
          </div>
        </div>
      </li>
      <li class="flex gap-3">
        <span class="step-no num" aria-hidden="true">2</span>
        <div class="min-w-0 flex flex-1 flex-col items-start gap-2">
          <span class="text-[14.5px] text-ink leading-relaxed">
            打开 <strong>{{ request.location ?? `${platformName(request.platform)} 的公开资料` }}</strong>，把验证串粘贴到末尾，保存。
          </span>
          <span v-if="request.note" class="text-[13px] text-muted leading-relaxed">{{ request.note }}</span>
          <span class="text-[13px] text-muted leading-relaxed">只用于核验，通过后可以删掉验证串、改回原样。</span>
          <!-- 有设置页地址的平台直接打开设置页，省掉「右上角 → 设置」几步 -->
          <a v-if="request.settingsUrl || homeUrl" :href="request.settingsUrl || homeUrl" target="_blank" rel="noopener noreferrer" class="open-link" data-test="open-platform">
            打开 {{ platformName(request.platform) }}{{ ['LUOGU', 'NOWCODER'].includes(request.platform) ? ' 个人主页' : request.settingsUrl ? ' 设置页' : '' }}<span class="i-lucide-external-link text-sm" aria-hidden="true" />
          </a>
        </div>
      </li>
      <li class="flex gap-3">
        <span class="step-no num" aria-hidden="true">3</span>
        <span class="pt-0.5 text-[14.5px] text-ink leading-6">保存好后回到这里，点「立即验证」</span>
      </li>
    </ol>

    <el-alert v-if="error" type="error" :closable="false" show-icon :title="error" data-test="proof-error" />

    <p v-if="expired" class="m-0 flex items-start gap-2 text-[13px] text-warning-ink leading-5" data-test="proof-validity">
      <span class="i-lucide-clock mt-[2.5px] text-[15px]" aria-hidden="true" />验证串已过期，点「重新获取验证串」签发新的
    </p>
    <p v-else class="m-0 flex items-start gap-2 text-[13px] text-muted leading-5" data-test="proof-validity">
      <span class="i-lucide-clock mt-[2.5px] text-[15px]" aria-hidden="true" />
      <span>验证串 {{ request.validMinutes }} 分钟内有效（到 <span class="num">{{ until }}</span>）· 已核验 {{ request.attempts }}/{{ request.maxAttempts }} 次 · 重新打开不会延长时间</span>
    </p>
  </div>
</template>

<style scoped>
.step-no {
  display: flex;
  flex-shrink: 0;
  align-items: center;
  justify-content: center;
  width: 24px;
  height: 24px;
  border-radius: 50%;
  background: var(--brand-soft);
  color: var(--brand-600);
  font-size: 13px;
  font-weight: 600;
}

.token {
  box-sizing: border-box;
  flex: 1;
  min-width: 0;
  height: 40px;
  padding: 0 14px;
  border: 1.5px dashed color-mix(in srgb, var(--brand-400) 70%, var(--border));
  border-radius: 10px;
  background: color-mix(in srgb, var(--brand-soft) 45%, var(--bg-elevated));
  color: var(--brand-600);
  font-size: 16px;
  font-weight: 600;
  letter-spacing: 0.04em;
  outline: none;
}

.token:focus {
  border-style: solid;
  border-color: var(--brand-500);
}

.open-link {
  display: inline-flex;
  align-items: center;
  gap: 6px;
  height: 34px;
  padding: 0 12px;
  border: 1px solid var(--border-strong);
  border-radius: 9px;
  color: var(--text-strong);
  font-size: 13.5px;
}

.open-link:hover {
  border-color: var(--brand-500);
  color: var(--brand-600);
}
</style>
