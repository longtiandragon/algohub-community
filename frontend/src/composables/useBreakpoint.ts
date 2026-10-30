import { computed, reactive } from 'vue'

/** 断点和 uno.config.ts 一致：不带前缀的是手机，md 平板，lg 笔记本，xl 桌面。 */
export const BREAKPOINTS = { md: 768, lg: 1024, xl: 1280 } as const

type Breakpoint = keyof typeof BREAKPOINTS

// 全站共用一组媒体查询监听，窗口大小变化时各组件跟着变；不轮询、不监听 resize
const state = reactive<Record<Breakpoint, boolean>>({ md: true, lg: true, xl: true })
let started = false

function start() {
  if (started || typeof window === 'undefined' || !window.matchMedia) return
  started = true
  for (const name of Object.keys(BREAKPOINTS) as Breakpoint[]) {
    const query = window.matchMedia(`(min-width: ${BREAKPOINTS[name]}px)`)
    state[name] = query.matches
    query.addEventListener('change', (event) => {
      state[name] = event.matches
    })
  }
}

/** 当前窗口不小于各断点吗；isMobile 即 md 以下（手机布局）。 */
export function useBreakpoint() {
  start()
  return {
    md: computed(() => state.md),
    lg: computed(() => state.lg),
    xl: computed(() => state.xl),
    isMobile: computed(() => !state.md)
  }
}
