/** 没有头像图时的字标：按用户编号轮换几种浅底 + 深一档的字，深浅色主题都跟着变 */
const TONES = [
  'bg-brand-soft text-brand-600',
  'bg-success-soft text-success-ink',
  'bg-warning-tint text-warning-ink',
  'bg-info-soft text-info-ink',
  'bg-danger-tint text-danger-ink'
]

export function avatarTone(userId: number) {
  return TONES[Math.abs(userId) % TONES.length]
}

export function initialOf(name: string | null | undefined) {
  return Array.from((name ?? '').trim())[0] ?? '?'
}

/** Codeforces 官方图片域名偶尔不可达时，改走其主站提供的同一路径。 */
export function displayAvatarUrl(url: string | null | undefined) {
  if (!url) return undefined
  return url.replace(/^https?:\/\/userpic\.codeforces\.org\//i, 'https://codeforces.com/userpic.codeforces.org/')
}
