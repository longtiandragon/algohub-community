/**
 * 难度分级配色。
 *
 * 各 OJ 最有辨识度的一件事就是「难度有颜色」：Codeforces 的 rating 色、
 * AtCoder Problems 的估计难度色、洛谷的评级色。全站难度都用一个灰标签时，
 * 一屏题目看下来分不出深浅，这里按各站自己的分档上色。
 *
 * 两条纪律：
 * - **颜色分档照抄各站自己的口径**，不自己发明难度体系，也不跨平台换算：
 *   Codeforces 的 1500 和 AtCoder 的 1500 不是一回事，两边各上各的色；
 * - AtCoder 的难度是 AtCoder Problems 的**第三方估计值**，不是官方难度，
 *   标签的 title 里必须写明，不能让人误以为是官方评分。
 */

export interface DifficultyStyle {
  /** 标签上显示的文字 */
  text: string
  /** 主色（文字与描边用） */
  color: string
  /** 悬停说明：难度来源与口径 */
  title: string
  /** 没有难度信息时为 true，页面据此显示成「未评级」而不是编一个等级 */
  unknown: boolean
}

const NEUTRAL = '#8A94A6'

/**
 * Codeforces 官方 rating 配色。题目难度沿用同一套色带，
 * 这是 CF 生态里通行的表示法（cf-tool、CF-Predictor 等都这么显示）。
 */
const CF_BANDS: [number, string][] = [
  [1200, '#8A94A6'],
  [1400, '#2F9E44'],
  [1600, '#0CA678'],
  [1900, '#1971C2'],
  [2100, '#9C36B5'],
  [2400, '#E8590C'],
  [Infinity, '#E03131']
]

/**
 * AtCoder Problems 对很简单的题给出的原始估计可能是负数，它自己的网站显示前会换算：
 * 400 以下按 400 / e^(1 - d/400) 压到 0～400 之间。这里照做，不显示负难度。
 */
export function clipAtcoder(value: number): number {
  return value >= 400 ? Math.round(value) : Math.round(400 / Math.exp(1 - value / 400))
}

/** AtCoder Problems 的难度色带（按换算后的值取色）。 */
const ATCODER_BANDS: [number, string][] = [
  [400, '#8A94A6'],
  [800, '#8A5A2B'],
  [1200, '#2F9E44'],
  [1600, '#0CA678'],
  [2000, '#1971C2'],
  [2400, '#B59F00'],
  [2800, '#E8590C'],
  [Infinity, '#E03131']
]

/** 洛谷现行九档评级名与配色（名称取自官网配置，颜色取自洛谷主页），键就是后端写进 difficulty_label 的评级名。 */
const LUOGU_TIERS: Record<string, string> = {
  暂无评定: '#BFBFBF',
  入门: '#FE4C61',
  '普及−': '#F39C11',
  普及: '#FFC116',
  '普及+/提高−': '#52C41A',
  提高: '#13C2C2',
  '提高+/省选−': '#3498DB',
  '省选/NOI−': '#9D3DCF',
  'NOI/NOI+/CTS': '#0E1D69'
}

/** 早于 V19 迁移写入的旧标签，兼容一下，免得没重新同步的库显示成无色。 */
const LEGACY_LUOGU = /^洛谷难度\s*(\d)$/
const LEGACY_TIER_NAMES = [
  '暂无评定',
  '入门',
  '普及−',
  '普及',
  '普及+/提高−',
  '提高',
  '提高+/省选−',
  '省选/NOI−',
  'NOI/NOI+/CTS'
]

function pick(bands: [number, string][], value: number): string {
  for (const [upper, color] of bands) {
    if (value < upper) return color
  }
  return bands[bands.length - 1][1]
}

export function difficultyStyle(
  platform: string | null | undefined,
  value: number | null | undefined,
  label: string | null | undefined
): DifficultyStyle {
  const platformKey = (platform ?? '').toUpperCase()
  const displayValue = typeof value === 'number' ? value
    : platformKey === 'CODEFORCES' ? Number(/^CF\s+(\d+)$/.exec(label ?? '')?.[1])
      : platformKey === 'ATCODER' ? Number(/^AtCoder Problems 估计\s+(-?\d+)$/.exec(label ?? '')?.[1])
        : platformKey === 'NOWCODER' ? Number(/^(\d+)$/.exec(label ?? '')?.[1])
        : NaN

  if (platformKey === 'CODEFORCES' && Number.isFinite(displayValue)) {
    return {
      text: String(displayValue),
      color: pick(CF_BANDS, displayValue),
      title: `Codeforces 官方难度 ${displayValue}；只在 Codeforces 内部比较，不与其他平台换算`,
      unknown: false
    }
  }

  if (platformKey === 'ATCODER' && Number.isFinite(displayValue)) {
    const shown = clipAtcoder(displayValue)
    return {
      text: String(shown),
      color: pick(ATCODER_BANDS, shown),
      title:
        `AtCoder Problems 估计难度 ${shown}${shown !== displayValue ? `（原始估计 ${displayValue}，低于 400 按 AtCoder Problems 的方式换算）` : ''}` +
        '；第三方估计值，不是 AtCoder 官方难度，只在 AtCoder 内部比较，不与其他平台换算',
      unknown: false
    }
  }

  if (platformKey === 'NOWCODER' && Number.isFinite(displayValue) && displayValue >= 0) {
    return {
      text: String(displayValue),
      color: NEUTRAL,
      title: `牛客源站难度 ${displayValue}；保留源站数值，只在牛客内部比较，不与其他平台换算`,
      unknown: false
    }
  }

  if (platformKey === 'LUOGU' && label) {
    const tier = LUOGU_TIERS[label]
    if (tier) {
      return {
        text: label,
        color: tier,
        title: `洛谷官方评级「${label}」；只在洛谷内部比较，不与其他平台换算`,
        unknown: false
      }
    }
    const legacy = LEGACY_LUOGU.exec(label)
    if (legacy) {
      const index = Number(legacy[1])
      const name = LEGACY_TIER_NAMES[index]
      if (name) {
        return {
          text: name,
          color: LUOGU_TIERS[name],
          title: `洛谷官方评级「${name}」；只在洛谷内部比较，不与其他平台换算`,
          unknown: false
        }
      }
    }
  }

  if (label && !(platformKey === 'NOWCODER' && ['-1', '未评级', '未评分'].includes(label))) {
    return {
      text: label,
      color: NEUTRAL,
      title: `${label}（该平台未提供可分档的难度，按原样显示）`,
      unknown: false
    }
  }

  return {
    text: '未评级',
    color: NEUTRAL,
    title: '该平台没有提供这道题的难度，不猜测',
    unknown: true
  }
}

/** 十六进制色加透明度，用于标签的浅底与描边。 */
export function withAlpha(hex: string, alpha: number): string {
  const value = hex.replace('#', '')
  const r = parseInt(value.slice(0, 2), 16)
  const g = parseInt(value.slice(2, 4), 16)
  const b = parseInt(value.slice(4, 6), 16)
  return `rgba(${r}, ${g}, ${b}, ${alpha})`
}

type Rgb = [number, number, number]

const hexToRgb = (hex: string): Rgb => {
  const value = hex.replace('#', '')
  return [0, 2, 4].map((i) => parseInt(value.slice(i, i + 2), 16)) as Rgb
}

const rgbToHex = (rgb: Rgb): string => `#${rgb.map((v) => Math.round(v).toString(16).padStart(2, '0')).join('')}`

const mix = (a: Rgb, b: Rgb, weightA: number): Rgb => a.map((v, i) => v * weightA + b[i] * (1 - weightA)) as Rgb

function luminance(rgb: Rgb): number {
  const [r, g, b] = rgb.map((v) => {
    const s = v / 255
    return s <= 0.03928 ? s / 12.92 : ((s + 0.055) / 1.055) ** 2.4
  })
  return 0.2126 * r + 0.7152 * g + 0.0722 * b
}

function contrast(a: Rgb, b: Rgb): number {
  const [light, dark] = [luminance(a), luminance(b)].sort((x, y) => y - x)
  return (light + 0.05) / (dark + 0.05)
}

function toHsl([r, g, b]: Rgb): [number, number, number] {
  const [rn, gn, bn] = [r / 255, g / 255, b / 255]
  const max = Math.max(rn, gn, bn)
  const min = Math.min(rn, gn, bn)
  const l = (max + min) / 2
  if (max === min) return [0, 0, l]
  const d = max - min
  const s = l > 0.5 ? d / (2 - max - min) : d / (max + min)
  const h = max === rn ? (gn - bn) / d + (gn < bn ? 6 : 0) : max === gn ? (bn - rn) / d + 2 : (rn - gn) / d + 4
  return [h / 6, s, l]
}

function fromHsl([h, s, l]: [number, number, number]): Rgb {
  if (s === 0) return [l * 255, l * 255, l * 255]
  const q = l < 0.5 ? l * (1 + s) : l + s - l * s
  const p = 2 * l - q
  const channel = (t: number) => {
    const u = t < 0 ? t + 1 : t > 1 ? t - 1 : t
    if (u < 1 / 6) return p + (q - p) * 6 * u
    if (u < 1 / 2) return q
    if (u < 2 / 3) return p + (q - p) * (2 / 3 - u) * 6
    return p
  }
  return [channel(h + 1 / 3) * 255, channel(h) * 255, channel(h - 1 / 3) * 255]
}

/** 卡片底色（styles.css 的 --bg-elevated），标签的浅底叠在它上面 */
const CARD: Record<'light' | 'dark', Rgb> = { light: [255, 255, 255], dark: [21, 25, 35] }
const inkCache = new Map<string, { light: string; dark: string }>()

/**
 * 难度标签的文字色：色相沿用各站的难度色，浅色主题往深调、深色主题往浅调，
 * 直到在标签底色（难度色 13% 叠在卡片上）上的对比度够 4.5:1（按 4.8 算，标签也会出现在浅灰的行上）。
 * 两种主题各算一份，由 CSS 按主题选用，切换主题不用重新渲染。
 */
export function difficultyInk(color: string): { light: string; dark: string } {
  const cached = inkCache.get(color)
  if (cached) return cached
  const base = hexToRgb(color)
  const solve = (theme: 'light' | 'dark') => {
    const background = mix(base, CARD[theme], 0.13)
    const [h, s, l] = toHsl(base)
    let lightness = l
    let rgb = base
    const step = theme === 'light' ? -0.02 : 0.02
    while (contrast(rgb, background) < 4.8 && lightness > 0 && lightness < 1) {
      lightness = Math.min(1, Math.max(0, lightness + step))
      rgb = fromHsl([h, s, lightness])
    }
    return rgbToHex(rgb)
  }
  const inks = { light: solve('light'), dark: solve('dark') }
  inkCache.set(color, inks)
  return inks
}
