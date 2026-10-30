<script setup lang="ts">
import { computed } from 'vue'
import type { RouteLocationRaw } from 'vue-router'
import { useLogout } from '@/composables/useLogout'
import { useAuthStore } from '@/stores/auth'
import { maskEmail } from '@/utils/email'

/**
 * 登录之外的整页框架：新同学的引导（验证邮箱 → 选择学校 → 完善资料）和找回密码。
 * 电脑和平板：方格纸底、顶部品牌栏、居中卡片，主按钮在卡片底部；有 step 时卡片上方是圆圈步骤条。
 * 手机：内容不套卡片，主按钮固定在屏幕底部；有 step 时顶部是「第 1 步 / 共 3 步」+ 三段进度条。
 * 顶栏两种：引导时右边是当前账号和「换个账号」（手机上是「退出登录」）；
 * 传了 back（如找回密码）时，手机顶栏是「← 标题」，电脑右上角是返回链接。主按钮写在 footer 插槽里。
 */
const props = withDefaults(
  defineProps<{
    /** 引导的第几步；不传就没有步骤条 */
    step?: 1 | 2 | 3
    title: string
    description?: string
    /** 标题旁的图标（lucide 类名） */
    icon: string
    /** 电脑上卡片的宽度 */
    width?: number
    /** 手机上显示图标；内容长的页面（选学校）省掉，给表单腾地方 */
    iconOnMobile?: boolean
    /** 返回到哪里；传了就用「返回」样式的顶栏 */
    back?: { to: RouteLocationRaw; label: string }
  }>(),
  { step: undefined, description: undefined, width: 480, iconOnMobile: true, back: undefined }
)

const STEPS = [
  { label: '验证邮箱', short: '验证邮箱' },
  { label: '选择学校', short: '选择学校' },
  { label: '完善资料', short: '完善资料' }
]

const auth = useAuthStore()
const logout = useLogout()

/** 右上角：邮箱打码，没有邮箱就写名字 */
const account = computed(() => {
  const me = auth.me
  if (!me) return ''
  return me.email ? maskEmail(me.email) : me.displayName || me.username
})

const stateOf = (index: number) => {
  const step = props.step ?? 0
  return index + 1 < step ? 'done' : index + 1 === step ? 'current' : 'todo'
}
</script>

<template>
  <div class="onboarding min-h-screen flex flex-col">
    <header class="onboarding-header" :class="{ 'has-back': back }">
      <template v-if="back">
        <RouterLink :to="back.to" class="back-link mobile-only" :aria-label="back.label">
          <span class="i-lucide-chevron-left text-[22px]" aria-hidden="true" />
        </RouterLink>
        <span class="mobile-only min-w-0 flex-1 truncate text-[17px] text-ink font-semibold">{{ title }}</span>
      </template>
      <span class="brand items-center gap-2 md:gap-2.5" :class="{ 'desktop-only': back }">
        <img src="/algohub-mark.svg" alt="" class="brand-mark" aria-hidden="true" />
        <span class="text-[15px] text-ink font-semibold md:font-650">AlgoHub</span>
      </span>
      <RouterLink v-if="back" :to="back.to" class="auth-link desktop-only text-[13.5px]">{{ back.label }}</RouterLink>
      <template v-else-if="auth.me">
        <span class="desktop-only text-[13.5px] text-muted">
          {{ account }} · <button type="button" class="auth-link" @click="logout">换个账号</button>
        </span>
        <button type="button" class="logout-button mobile-only" @click="logout">退出登录</button>
      </template>
    </header>

    <!-- 手机：第几步 + 三段进度 -->
    <div v-if="step" class="flex flex-col gap-2.5 px-4 pt-5 md:hidden">
      <span class="num text-[12.5px] text-muted">第 {{ step }} 步 / 共 3 步</span>
      <div class="grid grid-cols-3 gap-1.5" aria-hidden="true">
        <span
          v-for="(item, index) in STEPS"
          :key="item.label"
          class="h-1 rounded-sm"
          :class="{ 'bg-success': stateOf(index) === 'done', 'bg-brand': stateOf(index) === 'current', 'bg-line': stateOf(index) === 'todo' }"
        />
      </div>
      <ol class="m-0 grid grid-cols-3 list-none gap-1.5 p-0 text-[12.5px]" aria-label="开始使用的三个步骤">
        <li
          v-for="(item, index) in STEPS"
          :key="item.label"
          :aria-current="stateOf(index) === 'current' ? 'step' : undefined"
          :class="{ 'text-success-ink': stateOf(index) === 'done', 'text-brand-600 font-semibold': stateOf(index) === 'current', 'text-muted': stateOf(index) === 'todo' }"
        >
          <span v-if="stateOf(index) === 'done'" aria-hidden="true">✓ </span>{{ item.short }}<span v-if="stateOf(index) === 'done'" class="sr-only">（已完成）</span>
        </li>
      </ol>
    </div>

    <main class="onboarding-main" :class="{ 'no-step': !step, 'has-footer': $slots.footer }">
      <!-- 电脑和平板：圆圈步骤条 -->
      <ol v-if="step" class="m-0 hidden list-none items-center gap-3 p-0 text-[13.5px] md:flex" aria-label="开始使用的三个步骤">
        <template v-for="(item, index) in STEPS" :key="item.label">
          <li v-if="index > 0" class="h-0.5 w-12 rounded-full" :class="stateOf(index) === 'todo' ? 'bg-line-strong' : 'bg-success'" aria-hidden="true" />
          <li
            class="flex items-center gap-2"
            :class="{ 'text-success-ink': stateOf(index) === 'done', 'text-brand-600 font-semibold': stateOf(index) === 'current', 'text-muted': stateOf(index) === 'todo' }"
            :aria-current="stateOf(index) === 'current' ? 'step' : undefined"
          >
            <span class="step-dot" :class="`is-${stateOf(index)}`" aria-hidden="true">
              <span v-if="stateOf(index) === 'done'" class="i-lucide-check text-sm" />
              <template v-else>{{ index + 1 }}</template>
            </span>
            {{ item.label }}<span v-if="stateOf(index) === 'done'" class="sr-only">（已完成）</span>
          </li>
        </template>
      </ol>

      <section class="onboarding-card" :style="{ '--card-width': `${width}px` }">
        <!-- 手机上「返回」样式的顶栏已经写了标题，卡片头只在电脑上出现 -->
        <div class="onboarding-head" :class="{ 'desktop-only': back }">
          <span class="card-icon" :class="{ 'desktop-only': !iconOnMobile }" aria-hidden="true"><span :class="icon" class="text-[26px]" /></span>
          <div class="flex flex-col gap-1.5 md:gap-1">
            <h1 class="m-0 text-[23px] text-ink font-bold md:text-2xl">{{ title }}</h1>
            <p v-if="description" class="m-0 text-[15px] text-muted leading-[1.7] md:(text-sm leading-normal)">{{ description }}</p>
          </div>
        </div>
        <slot />
        <div v-if="$slots.footer" class="onboarding-footer">
          <slot name="footer" />
        </div>
      </section>
    </main>
  </div>
</template>

<style scoped>
/* 手机是白底；电脑和平板是方格纸底（见下方媒体查询） */
.onboarding {
  background: var(--bg-elevated);
}

.onboarding-header {
  display: flex;
  flex-shrink: 0;
  align-items: center;
  justify-content: space-between;
  gap: 4px;
  height: 56px;
  padding: 0 12px 0 16px;
  border-bottom: 1px solid var(--border-soft);
}

.onboarding-header.has-back {
  padding-left: 8px;
}

.brand {
  display: flex;
}

.brand-mark {
  display: flex;
  flex-shrink: 0;
  align-items: center;
  justify-content: center;
  width: 28px;
  height: 28px;
  border-radius: 8px;
}

.back-link {
  display: flex;
  flex-shrink: 0;
  align-items: center;
  justify-content: center;
  width: 44px;
  height: 44px;
  border-radius: 10px;
  color: var(--text-strong);
}

.back-link:hover {
  color: var(--text-strong);
  text-decoration: none;
}

.logout-button {
  height: 40px;
  padding: 0 8px;
  border: 0;
  background: transparent;
  color: var(--text-muted);
  font: inherit;
  font-size: 14px;
  cursor: pointer;
}

/* 只在电脑或只在手机出现的部分：写在组件样式里，优先级高于组件内各自的 display */
.desktop-only,
.brand.desktop-only,
.onboarding-head.desktop-only,
.card-icon.desktop-only {
  display: none;
}

.onboarding-main {
  display: flex;
  flex: 1;
  flex-direction: column;
  padding: 28px 16px calc(28px + env(safe-area-inset-bottom));
}

/* 有主按钮时底部不留白，按钮区自己贴着屏幕底（见 .onboarding-footer） */
.onboarding-main.has-footer {
  padding-bottom: 0;
}

.onboarding-main.no-step {
  padding-top: 20px;
}

.onboarding-card {
  display: flex;
  flex: 1;
  flex-direction: column;
  gap: 18px;
}

.onboarding-head {
  display: flex;
  flex-direction: column;
  gap: 18px;
}

.card-icon {
  display: flex;
  flex-shrink: 0;
  align-items: center;
  justify-content: center;
  width: 56px;
  height: 56px;
  border-radius: 16px;
  background: var(--brand-soft);
  color: var(--brand-500);
}

/*
 * 手机：主按钮区粘在屏幕底部。内容短时靠 margin-top: auto 落到最底下；内容长时滚动中一直看得见，
 * 滚到最后排在内容后面。不用 fixed，是因为按钮区有几行（选学校有三行）都不会盖住最下面的表单。
 */
.onboarding-footer {
  position: sticky;
  bottom: 0;
  z-index: 10;
  margin: auto -16px 0;
  padding: 12px 16px calc(12px + env(safe-area-inset-bottom));
  border-top: 1px solid var(--border-soft);
  background: var(--bg-elevated);
}

.step-dot {
  display: flex;
  align-items: center;
  justify-content: center;
  width: 24px;
  height: 24px;
  border-radius: 50%;
  font-family: var(--font-num);
  font-size: 13px;
}

.step-dot.is-done {
  background: var(--success);
  color: #fff;
}

.step-dot.is-current {
  background: var(--brand-btn);
  color: #fff;
}

.step-dot.is-todo {
  border: 1px solid var(--border-strong);
  background: var(--bg-elevated);
  color: var(--text-muted);
}

@media (min-width: 768px) {
  .onboarding {
    background-color: var(--bg);
    background-image:
      linear-gradient(color-mix(in srgb, var(--border) 60%, var(--bg)) 1px, transparent 1px),
      linear-gradient(90deg, color-mix(in srgb, var(--border) 60%, var(--bg)) 1px, transparent 1px);
    background-size: 28px 28px;
  }

  .mobile-only {
    display: none;
  }

  .desktop-only {
    display: inline;
  }

  .brand.desktop-only,
  .onboarding-head.desktop-only {
    display: flex;
  }

  .onboarding-header,
  .onboarding-header.has-back {
    height: 64px;
    padding: 0 32px;
    border-bottom: 0;
  }

  .brand-mark {
    width: 32px;
    height: 32px;
    border-radius: 9px;
    font-size: 15px;
  }

  .onboarding-main,
  .onboarding-main.has-footer {
    align-items: center;
    gap: 22px;
    padding: 12px 24px 48px;
  }

  .onboarding-main.no-step {
    padding-top: 24px;
  }

  .onboarding-card {
    flex: none;
    gap: 20px;
    width: 100%;
    max-width: var(--card-width);
    padding: 32px;
    border: 1px solid var(--border);
    border-radius: 18px;
    background: var(--bg-elevated);
    box-shadow: 0 12px 32px rgba(18, 22, 41, 0.06);
  }

  .onboarding-head,
  .onboarding-head.desktop-only {
    flex-direction: row;
    align-items: center;
    gap: 16px;
  }

  .card-icon,
  .card-icon.desktop-only {
    display: flex;
    width: 52px;
    height: 52px;
    border-radius: 14px;
  }

  .onboarding-footer {
    position: static;
    margin: 0;
    padding: 4px 0 0;
    border-top: 0;
    background: none;
  }
}
</style>
