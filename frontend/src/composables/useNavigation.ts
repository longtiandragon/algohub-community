import { shallowRef } from 'vue'
import type { RouteLocationNormalized, Router } from 'vue-router'

// 路由的异步页面代码尚未下载时，旧路由仍然有效；提前向外壳报告目标。
export const pendingNavigation = shallowRef<RouteLocationNormalized | null>(null)

export function finishNavigation(to: RouteLocationNormalized) {
  if (pendingNavigation.value?.fullPath === to.fullPath) pendingNavigation.value = null
}

const prefetched = new Set<string>()
export function prefetchPage(router: Router, path: string) {
  for (const record of router.resolve(path).matched) {
    const component = record.components?.default
    if (typeof component !== 'function' || prefetched.has(record.path)) continue
    prefetched.add(record.path)
    // 只下载页面模块，不挂载页面，也不触发其业务接口。
    Promise.resolve().then(() => (component as () => Promise<unknown>)()).catch(() => {
      prefetched.delete(record.path)
    })
  }
}
