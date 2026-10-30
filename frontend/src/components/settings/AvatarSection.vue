<script setup lang="ts">
import { computed, onMounted, ref, useId } from 'vue'
import { ElMessage } from 'element-plus'
import { get, notifyError, post } from '@/api/client'
import PlatformLogo from '@/components/PlatformLogo.vue'
import SectionCard from '@/components/ui/SectionCard.vue'
import UserAvatar from '@/components/ui/UserAvatar.vue'
import { useAuthStore } from '@/stores/auth'
import { platformName } from '@/utils/platformBrand'
import { displayAvatarUrl } from '@/utils/avatar'

/**
 * 头像：不上传图片，从自己已验证的 OJ 账号里选一个，用那个平台上的头像；也可以用默认字标（名字的第一个字）。
 * 能选的排成一排圆形小头像（右下角是平台角标，选中的有品牌色圆环和对勾），第一个是默认字标；
 * 不能选的（平台不提供、还没验证归属、还没取到头像）不占位置，按原因合并成底部几行灰字。选了马上生效，不走底部保存条。
 * 头像地址跟着账号同步刷新（至多一天查一次），所以在 OJ 上换了头像，下次同步后这里跟着变。
 * 平台给了地址但图片加载不出来（图床暂时不可达）时，先显示字标并说明原因，恢复后刷新即可。
 */
interface AvatarOption {
  accountId: number
  platform: string
  handle: string
  avatarUrl: string | null
  selectable: boolean
  reason: string | null
  checkedAt: string | null
}
interface AvatarView {
  selectedAccountId: number | null
  avatarUrl: string | null
  options: AvatarOption[]
}

/** 平台名夹在中文里：英文开头、结尾的（Codeforces、杭电 OJ）两边留空格，中文的（洛谷）不留 */
const spaced = (name: string) => `${/^[!-~]/.test(name) ? ' ' : ''}${name}${/[!-~]$/.test(name) ? ' ' : ''}`

/** 后端给的不能选的原因（AvatarService 里的常量），换成按平台合并后的说法；认不出的原样显示 */
const REASONS: Record<string, { text: (names: string) => string; link?: { to: string; label: string } }> = {
  这个平台不提供头像: { text: (names) => `${names}不提供头像` },
  '还没验证归属，验证后才能用它的头像': {
    text: (names) => `${names}还没验证归属，验证后才能用它的头像`,
    link: { to: '/accounts', label: '去验证' }
  },
  '还没取到头像，同步一次后再来看': { text: (names) => `${names}还没取到头像，同步一次后再来看` },
  平台上没有取到这个账号的头像: { text: (names) => `${names}上没有取到这个账号的头像` }
}

const auth = useAuthStore()
const view = ref<AvatarView | null>(null)
const loading = ref(true)
const saving = ref(false)
const groupName = useId()
/** 图片加载不出来的账号：每次拿到新数据都重新探一遍，旧的探测结果作废 */
const broken = ref<Set<number>>(new Set())
let probeGeneration = 0

function probeImages(options: AvatarOption[]) {
  const generation = ++probeGeneration
  broken.value = new Set()
  for (const option of options) {
    if (!option.avatarUrl) continue
    const image = new Image()
    image.referrerPolicy = 'no-referrer'
    image.onerror = () => {
      if (generation === probeGeneration) broken.value = new Set([...broken.value, option.accountId])
    }
    image.src = displayAvatarUrl(option.avatarUrl) ?? option.avatarUrl
  }
}

async function load() {
  loading.value = true
  try {
    view.value = await get<AvatarView>('/me/avatar')
    probeImages(view.value.options)
  } catch (e) {
    notifyError(e)
  } finally {
    loading.value = false
  }
}
onMounted(load)

/** 圆形头像下面的字：平台名去掉括号里的补充（力扣（中国站）→ 力扣）；同一平台有几个账号能选时写账号名 */
const shortName = (platform: string) => platformName(platform).replace(/（.*）$/, '')
const choices = computed(() => {
  const list = view.value?.options.filter((o) => o.selectable) ?? []
  const count = new Map<string, number>()
  for (const o of list) count.set(o.platform, (count.get(o.platform) ?? 0) + 1)
  return list.map((o) => ({ ...o, label: (count.get(o.platform) ?? 0) > 1 ? o.handle : shortName(o.platform) }))
})
const unavailable = computed(() => {
  const groups = new Map<string, string[]>()
  for (const o of view.value?.options ?? []) {
    if (o.selectable) continue
    const names = groups.get(o.reason ?? '') ?? []
    const name = platformName(o.platform)
    if (!names.includes(name)) names.push(name)
    groups.set(o.reason ?? '', names)
  }
  return [...groups].map(([reason, names]) => {
    const known = REASONS[reason]
    const joined = names.join('、')
    return { key: reason, text: known ? known.text(spaced(joined).trimStart()) : `${joined}：${reason || '暂时不能用'}`, link: known?.link }
  })
})

const selected = computed(() => view.value?.options.find((o) => o.accountId === view.value?.selectedAccountId) ?? null)
/** 选了某个账号，但现在取不到它的头像（没有地址或图片加载不出来）：暂时显示字标 */
const selectedFallsBack = computed(() => !!selected.value && (!view.value?.avatarUrl || broken.value.has(selected.value.accountId)))

async function choose(accountId: number | null) {
  if (saving.value || !view.value || accountId === view.value.selectedAccountId) return
  saving.value = true
  try {
    view.value = await post<AvatarView>('/me/avatar', { accountId })
    probeImages(view.value.options)
    await auth.refreshMe()
    ElMessage.success(accountId == null ? '已改回默认字标' : '已选择头像来源')
  } catch (e) {
    notifyError(e)
    await load()
  } finally {
    saving.value = false
  }
}
</script>

<template>
  <SectionCard title="头像" description="从已验证的 OJ 账号里选一个头像，选好马上生效，不用上传图片。" data-test="settings-avatar">
    <div class="flex flex-col gap-5">
      <!-- 当前头像 -->
      <div class="flex items-center gap-4">
        <UserAvatar :src="auth.me?.avatarUrl" :name="auth.me?.displayName" class="current" />
        <div class="min-w-0 flex flex-col gap-1">
          <span class="text-[15px] text-ink font-semibold leading-6">
            {{ selected ? `用的是${spaced(platformName(selected.platform))}上的头像` : '用的是默认字标' }}
          </span>
          <span v-if="selectedFallsBack" class="text-[12.5px] text-warning-ink leading-5">
            {{ selected && broken.has(selected.accountId)
              ? `${spaced(platformName(selected.platform)).trimStart()}的头像图片暂时加载不出来，先显示字标；图片服务恢复后刷新页面即可。`
              : '现在取不到这个账号的头像，先显示字标；下次同步取到后会自动用上。' }}
          </span>
          <span v-else-if="selected" class="text-[12.5px] text-muted leading-5">
            账号 <span class="mono">{{ selected.handle }}</span> · 在 OJ 上换了头像，下次同步后这里跟着变
          </span>
          <span v-else class="text-[12.5px] text-muted leading-5">显示昵称的第一个字</span>
        </div>
      </div>

      <div v-if="loading" class="text-[13px] text-muted" role="status">正在读取各平台的头像…</div>
      <template v-else-if="view">
        <div
          role="radiogroup"
          aria-label="选择头像"
          class="choices border-0 border-t border-solid border-line-soft pt-5"
          :class="{ 'is-saving': saving }"
          :aria-busy="saving"
        >
          <label class="choice" :class="{ 'is-active': view.selectedAccountId == null }" title="默认字标：显示昵称的第一个字">
            <input
              class="sr-only"
              type="radio"
              :name="groupName"
              :checked="view.selectedAccountId == null"
              :disabled="saving"
              aria-label="默认字标"
              data-test="avatar-option-default"
              @change="choose(null)"
            >
            <span class="ring">
              <UserAvatar :name="auth.me?.displayName" class="face bg-brand-btn text-white" />
              <span class="check" aria-hidden="true"><span class="i-lucide-check" /></span>
            </span>
            <span class="label">默认</span>
          </label>

          <label
            v-for="option in choices"
            :key="option.accountId"
            class="choice"
            :class="{ 'is-active': option.accountId === view.selectedAccountId }"
            :title="`${platformName(option.platform)} · ${option.handle}${broken.has(option.accountId) ? '（图片暂时加载不出来）' : ''}`"
            :data-test="`avatar-option-${option.platform}`"
          >
            <input
              class="sr-only"
              type="radio"
              :name="groupName"
              :checked="option.accountId === view.selectedAccountId"
              :disabled="saving"
              :aria-label="`${platformName(option.platform)}（${option.handle}）的头像`"
              @change="choose(option.accountId)"
            >
            <span class="ring">
              <UserAvatar :src="option.avatarUrl" :name="option.handle" class="face bg-surface-2 text-muted" />
              <span class="badge" aria-hidden="true"><PlatformLogo :platform="option.platform" :size="18" /></span>
              <span class="check" aria-hidden="true"><span class="i-lucide-check" /></span>
            </span>
            <span class="label">{{ option.label }}</span>
          </label>
        </div>

        <ul v-if="unavailable.length" class="m-0 flex list-none flex-col gap-1.5 p-0" data-test="avatar-unavailable">
          <li v-for="item in unavailable" :key="item.key" class="flex items-start gap-2 text-[12.5px] text-muted leading-5">
            <span class="i-lucide-circle-slash mt-0.5 shrink-0 text-[13px] text-faint" aria-hidden="true" />
            <span>
              {{ item.text }}<RouterLink v-if="item.link" :to="item.link.to" class="ml-1.5 whitespace-nowrap text-brand-600">{{ item.link.label }} →</RouterLink>
            </span>
          </li>
        </ul>
        <p v-if="!view.options.length" class="m-0 text-[12.5px] text-muted leading-5">
          还没有绑定 OJ 账号。去<RouterLink to="/accounts" class="text-brand-600">平台账号</RouterLink>绑定并验证归属后，就能用那个平台的头像。
        </p>
      </template>
    </div>
  </SectionCard>
</template>

<style scoped>
.current {
  display: flex;
  flex-shrink: 0;
  align-items: center;
  justify-content: center;
  width: 64px;
  height: 64px;
  border-radius: 50%;
  background: var(--brand-btn);
  color: #fff;
  font-size: 26px;
  font-weight: 600;
}

/* 手机一行 5 个，电脑一行能放十来个；格子等宽，头像在格子里居中 */
.choices {
  display: grid;
  grid-template-columns: repeat(auto-fill, minmax(60px, 1fr));
  gap: 16px 8px;
}

.choices.is-saving {
  opacity: 0.6;
}

.choice {
  display: flex;
  flex-direction: column;
  align-items: center;
  gap: 8px;
  min-width: 0;
  cursor: pointer;
}

.is-saving .choice {
  cursor: progress;
}

.ring {
  position: relative;
  width: 48px;
  height: 48px;
  border-radius: 50%;
  box-shadow: 0 0 0 2px var(--bg-elevated), 0 0 0 3px var(--border);
  transition: box-shadow 0.15s;
}

.choice:hover .ring {
  box-shadow: 0 0 0 2px var(--bg-elevated), 0 0 0 3px var(--border-strong);
}

.choice.is-active .ring {
  box-shadow: 0 0 0 2px var(--bg-elevated), 0 0 0 4px var(--brand-500);
}

.choice:has(input:focus-visible) .ring {
  outline: 2px solid var(--brand-500);
  outline-offset: 5px;
}

.face {
  display: flex;
  align-items: center;
  justify-content: center;
  width: 100%;
  height: 100%;
  overflow: hidden;
  border-radius: 50%;
  font-size: 19px;
  font-weight: 600;
}

/* 透明底的头像（如 AtCoder 默认头像是黑线条）在深色主题下会看不见，图片一律垫白底 */
img.current,
img.face {
  background: #fff;
}

/* 平台角标压在右下角，垫一圈卡片底色和头像分开 */
.badge {
  position: absolute;
  right: -5px;
  bottom: -5px;
  display: flex;
  padding: 2px;
  border-radius: 8px;
  background: var(--bg-elevated);
}

.check {
  position: absolute;
  top: -5px;
  right: -5px;
  display: none;
  align-items: center;
  justify-content: center;
  width: 20px;
  height: 20px;
  box-sizing: border-box;
  border: 2px solid var(--bg-elevated);
  border-radius: 50%;
  background: var(--brand-500);
  color: #fff;
  font-size: 11px;
}

.is-active .check {
  display: flex;
}

.label {
  max-width: 100%;
  overflow: hidden;
  color: var(--text-muted);
  font-size: 12px;
  line-height: 16px;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.is-active .label {
  color: var(--brand-600);
  font-weight: 600;
}

@media (min-width: 768px) {
  .choices {
    grid-template-columns: repeat(auto-fill, minmax(68px, 1fr));
  }

  .ring {
    width: 52px;
    height: 52px;
  }
}

@media (prefers-reduced-motion: reduce) {
  .ring {
    transition: none;
  }
}
</style>
