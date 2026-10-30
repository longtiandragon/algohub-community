import { defineStore } from 'pinia'
import { computed, ref, watch } from 'vue'

export type ThemeMode = 'system' | 'light' | 'dark'
export type Theme = 'light' | 'dark'

export const THEME_MODES: { value: ThemeMode; label: string; icon: string; hint: string }[] = [
  { value: 'system', label: '跟随系统', icon: 'i-lucide-monitor', hint: '和手机、电脑的深浅色设置一致' },
  { value: 'light', label: '浅色', icon: 'i-lucide-sun', hint: '' },
  { value: 'dark', label: '深色', icon: 'i-lucide-moon', hint: '' }
]

const STORAGE_KEY = 'algohub-theme'

/**
 * 本机记的选择：light / dark / system，没有记录算跟随系统。
 * 第 8 步以前，第一次打开就会把当时系统的深浅色记下来；这些老记录会被当成手动选过浅色或深色。
 */
function readSaved(): ThemeMode {
  try {
    const saved = localStorage.getItem(STORAGE_KEY)
    return saved === 'light' || saved === 'dark' ? saved : 'system'
  } catch {
    return 'system'
  }
}

/**
 * 主题：跟随系统 / 浅色 / 深色。页面颜色都靠 <html data-theme> 切换变量。
 * 跟随系统时监听系统的深浅色切换，手机晚上自动变深色时这里跟着变。
 * main.ts 在挂载前就创建这个 store，首屏直接是对的主题，不会先亮一下再变暗。
 */
export const useThemeStore = defineStore('theme', () => {
  const media = typeof window.matchMedia === 'function' ? window.matchMedia('(prefers-color-scheme: dark)') : null
  const mode = ref<ThemeMode>(readSaved())
  const systemDark = ref(media?.matches ?? false)
  /** 实际显示的深浅色 */
  const theme = computed<Theme>(() => (mode.value === 'system' ? (systemDark.value ? 'dark' : 'light') : mode.value))

  const onSystemChange = (event: MediaQueryListEvent) => {
    systemDark.value = event.matches
  }
  if (media) {
    // Safari 14 以前只有 addListener
    if (typeof media.addEventListener === 'function') media.addEventListener('change', onSystemChange)
    else media.addListener(onSystemChange)
  }

  watch(theme, (value) => document.documentElement.setAttribute('data-theme', value), { immediate: true })

  function setMode(next: ThemeMode) {
    mode.value = next
    try {
      localStorage.setItem(STORAGE_KEY, next)
    } catch {
      // 隐私模式下存不了，只影响下次打开
    }
  }

  return { mode, theme, setMode }
})
