/** 只放行 http(s) 链接：题目、比赛、导航这些地址来自各平台或管理员填写，挡掉 javascript: 之类 */
export function httpUrl(url: string | null | undefined): string | undefined {
  return url && /^https?:\/\//i.test(url.trim()) ? url.trim() : undefined
}
