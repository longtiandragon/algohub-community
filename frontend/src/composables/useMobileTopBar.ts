import { computed, nextTick, onMounted, ref, watch } from 'vue'
import { useBreakpoint } from '@/composables/useBreakpoint'

/**
 * 手机顶栏右侧的操作区（MobileTopBar 里的 #mobile-topbar-actions）。
 * inTopbar：现在是手机宽度、而且页面在外壳里（整页没有顶栏），页面的操作按钮应该送到顶栏。
 * 窗口在手机和平板宽度之间切换时，顶栏跟着出现或消失，这里重新找一次。
 */
export function useMobileTopBar() {
  const { isMobile } = useBreakpoint()
  const target = ref<HTMLElement | null>(null)

  function find() {
    target.value = document.getElementById('mobile-topbar-actions')
  }
  onMounted(find)
  watch(isMobile, () => nextTick(find))

  const inTopbar = computed(() => isMobile.value && target.value !== null)
  return { target, inTopbar }
}
