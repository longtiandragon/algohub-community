import { onBeforeUnmount, reactive, watchEffect } from 'vue'

/**
 * 同一个路由里的「子页」（如设置与安全的 ?section=profile）改手机顶栏的标题和返回目标。
 * 顶栏（MobileTopBar）优先用这里的值，没有时照旧用路由的 meta。页面离开时自动清掉。
 */
export const topBarOverride = reactive<{ title: string | null; parent: string | null; parentTitle: string | null }>({
  title: null,
  parent: null,
  parentTitle: null
})

/** get() 返回 null 表示不覆盖（比如设置的分组列表本身） */
export function useTopBarOverride(get: () => { title: string; parent: string; parentTitle: string } | null) {
  const stop = watchEffect(() => {
    const value = get()
    topBarOverride.title = value?.title ?? null
    topBarOverride.parent = value?.parent ?? null
    topBarOverride.parentTitle = value?.parentTitle ?? null
  })
  onBeforeUnmount(() => {
    stop()
    topBarOverride.title = null
    topBarOverride.parent = null
    topBarOverride.parentTitle = null
  })
}
