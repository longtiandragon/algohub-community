import {
  defineConfig,
  presetIcons,
  presetWind3,
  transformerDirectives,
  transformerVariantGroup
} from 'unocss'

// 样式写法见 docs/2026-09-27-页面设计规划.md 第二节「实现方式：UnoCSS」。
// 颜色都指向 styles.css 里的 CSS 变量，深浅色靠 html[data-theme] 切变量，不写 dark: 变体。
// 不引 reset（会改掉 Element Plus 的默认样式），不用 attributify（和组件的 size、type 属性撞名）。
// 没有 reset，`border` 只设宽度不设线型：画实线要写 `border border-solid`（虚线 `border-dashed`）。
export default defineConfig({
  presets: [
    presetWind3(),
    // 图标：<span class="i-lucide-house" />，纯 CSS，只生成用到的
    presetIcons({
      extraProperties: {
        display: 'inline-block',
        'vertical-align': 'middle',
        'flex-shrink': '0'
      }
    })
  ],
  transformers: [transformerVariantGroup(), transformerDirectives()],
  content: {
    pipeline: {
      // 前一项是 UnoCSS 的默认范围；导航配置等写在 .ts 里的类名（如图标名）也要生成，只加 src 下的
      include: [/\.(vue|svelte|[jt]sx|mdx?|astro|elm|php|phtml|html)($|\?)/, 'src/**/*.ts']
    }
  },
  // 断点整个替换（theme 里写会和默认的 sm、2xl 合并）：不带前缀的是手机，md 平板、lg 笔记本、xl 桌面
  extendTheme: (theme) => ({
    ...theme,
    breakpoints: {
      md: '768px',
      lg: '1024px',
      xl: '1280px'
    }
  }),
  // 常用组合：num 给排名、题数、时间这类数字（Outfit + 等宽数字），mono 给题号、账号、验证串
  shortcuts: {
    num: 'font-num tabular-nums',
    mono: 'font-mono'
  },
  theme: {
    fontFamily: {
      sans: 'var(--font-sans)',
      num: 'var(--font-num)',
      mono: 'var(--font-mono)'
    },
    colors: {
      brand: {
        DEFAULT: 'var(--brand-500)',
        400: 'var(--brand-400)',
        500: 'var(--brand-500)',
        600: 'var(--brand-600)',
        soft: 'var(--brand-soft)',
        btn: 'var(--brand-btn)'
      },
      page: 'var(--bg)',
      surface: {
        DEFAULT: 'var(--bg-elevated)',
        2: 'var(--bg-subtle)'
      },
      line: {
        DEFAULT: 'var(--border)',
        strong: 'var(--border-strong)',
        soft: 'var(--border-soft)'
      },
      ink: 'var(--text-strong)',
      body: 'var(--text)',
      muted: 'var(--text-muted)',
      // 只给图标和禁用态
      faint: 'var(--text-faint)',
      // 状态色：DEFAULT 给图标和圆点，soft 是浅底，ink 是浅底上的文字
      success: { DEFAULT: 'var(--success)', soft: 'var(--success-soft)', ink: 'var(--success-ink)' },
      warning: {
        DEFAULT: 'var(--warning)',
        soft: 'var(--warning-soft)',
        tint: 'var(--warning-tint)',
        ink: 'var(--warning-ink)',
        line: 'var(--warning-line)'
      },
      danger: { DEFAULT: 'var(--danger)', soft: 'var(--danger-soft)', tint: 'var(--danger-tint)', ink: 'var(--danger-ink)' },
      info: { DEFAULT: 'var(--info)', soft: 'var(--info-soft)', ink: 'var(--info-ink)' },
      medal: { gold: 'var(--medal-gold)', silver: 'var(--medal-silver)', bronze: 'var(--medal-bronze)' },
      skeleton: 'var(--skeleton)',
      heat: { 0: 'var(--heat-0)', 1: 'var(--heat-1)', 2: 'var(--heat-2)', 3: 'var(--heat-3)', 4: 'var(--heat-4)' }
    },
    // 圆角按用途取名：rounded-tag 6、rounded-control 10（按钮、输入框、小容器）、rounded-card 14、rounded-sheet 20
    borderRadius: {
      tag: 'var(--radius-xs)',
      control: 'var(--radius-sm)',
      card: 'var(--radius)',
      sheet: 'var(--radius-lg)'
    }
  }
})
