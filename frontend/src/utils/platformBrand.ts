/** 中性文字角标由本项目绘制，仅用于平台识别，不使用第三方 Logo 图片，不表示官方合作。 */
export interface PlatformBrand {
  /** 角标里的字，1～2 个字符最好看 */
  mark: string
  /** 角标底色 */
  color: string
  /** 角标文字颜色，浅底平台需要深色字 */
  ink?: string
  /**
   * 兜底显示名。平台名的**权威来源始终是后端的 /platforms/capabilities**，
   * 这里只在页面还没拉到能力列表时顶一下，免得界面上直接显示 `LEETCODE_CN` 这种标识符。
   */
  name: string
}

const BRANDS: Record<string, PlatformBrand> = {
  CODEFORCES: { mark: 'CF', color: '#1F8ACB', name: 'Codeforces' },
  ATCODER: { mark: 'At', color: '#222222', name: 'AtCoder' },
  LUOGU: { mark: '洛', color: '#3498DB', name: '洛谷' },
  LEETCODE_CN: { mark: '力', color: '#FFA116', ink: '#26211A', name: '力扣（中国站）' },
  NOWCODER: { mark: '牛', color: '#2B7FFF', name: '牛客' },
  HDU: { mark: 'HD', color: '#B03A2E', name: '杭电 OJ' },
  QOJ: { mark: 'Q', color: '#6C5CE7', name: 'QOJ' },
  MATIJI: { mark: '码', color: '#F2683C', name: '码蹄集' },
  LANQIAO: { mark: '蓝', color: '#0C7EF2', name: '蓝桥云课' }
}

/** 未登记的平台给一个中性角标，取名字首字，不硬凑颜色。 */
const FALLBACK: PlatformBrand = { mark: '?', color: '#8A94A6', name: '未知平台' }

export function platformBrand(platform: string | null | undefined): PlatformBrand {
  if (!platform) return FALLBACK
  const brand = BRANDS[platform.toUpperCase()]
  if (brand) return brand
  const first = Array.from(platform.trim())[0]
  return first ? { ...FALLBACK, mark: first.toUpperCase(), name: platform } : FALLBACK
}

/** 兜底显示名：页面已经拿到 /platforms/capabilities 时，优先用那边的 displayName。 */
export function platformName(platform: string | null | undefined): string {
  return platform ? platformBrand(platform).name : ''
}

/**
 * 有本地图标的平台。文件在 `frontend/public/platform-logos/`，
 * 名字是平台标识小写、下划线换成连字符（LEETCODE_CN → leetcode-cn.svg）。
 * 新增平台时把图标放进去并在这里登记；没登记的就显示字标，不会留破图。
 */
const WITH_LOGO = new Set([
  'CODEFORCES',
  'ATCODER',
  'LUOGU',
  'LEETCODE_CN',
  'NOWCODER',
  'HDU',
  'MATIJI',
  'LANQIAO'
])

export function platformLogoUrl(platform: string | null | undefined): string | null {
  if (!platform) return null
  const key = platform.toUpperCase()
  if (!WITH_LOGO.has(key)) return null
  return `/platform-logos/${key.toLowerCase().replace(/_/g, '-')}.svg`
}
