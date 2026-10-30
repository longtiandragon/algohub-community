import fs from 'node:fs'
import { fileURLToPath, URL } from 'node:url'
import { defineConfig } from 'vite'
import vue from '@vitejs/plugin-vue'
import UnoCSS from 'unocss/vite'
import AutoImport from 'unplugin-auto-import/vite'
import Components from 'unplugin-vue-components/vite'
import { ElementPlusResolver } from 'unplugin-vue-components/resolvers'

// 本地开发：前端 5174，/api 代理到本机后端 8080。浏览器不直接采集第三方 OJ。
// 5173 留给本机另一个项目，不要占用；启动脚本也用 5174（可用 -FrontendPort 覆盖）。
// 截图脚本（scripts/shots.mjs）用 ALGOHUB_API_TARGET 把代理指向示例后端 8081。
const apiTarget = process.env.ALGOHUB_API_TARGET || 'http://127.0.0.1:8080'

// Element Plus 各组件的样式入口（按需引入插件注入的就是这些模块），开发时一启动就预构建。
// Vite 启动时扫描依赖不会跑这个插件，这些模块只能在打开页面时才被发现，
// 第一次打开用到新组件的页面就会重新预构建、整页刷新。通配符匹配不到（包的 exports 把 .mjs 藏起来了），所以按目录列出。
const elementPlusComponents = fileURLToPath(new URL('./node_modules/element-plus/es/components', import.meta.url))
const elementPlusStyles = fs.existsSync(elementPlusComponents)
  ? fs.readdirSync(elementPlusComponents)
      .filter((name) => fs.existsSync(`${elementPlusComponents}/${name}/style/css.mjs`))
      .map((name) => `element-plus/es/components/${name}/style/css`)
  : []

export default defineConfig({
  plugins: [
    vue(),
    // UnoCSS 的配置在 uno.config.ts
    UnoCSS(),
    // Element Plus 按需引入：模板里用到的组件、v-loading 指令和它们的样式由插件引入。
    // 只解析 Element Plus，自己的组件照旧显式 import（dirs 为空）。
    // 用 JS 调用的 ElMessage、ElMessageBox 插件管不到样式，在 main.ts 里引入。
    Components({
      dirs: [],
      resolvers: [ElementPlusResolver({ importStyle: 'css' })],
      dts: 'components.d.ts'
    }),
    AutoImport({
      resolvers: [ElementPlusResolver({ importStyle: 'css' })],
      dts: 'auto-imports.d.ts'
    })
  ],
  optimizeDeps: {
    include: ['element-plus/es', ...elementPlusStyles]
  },
  resolve: {
    alias: {
      '@': fileURLToPath(new URL('./src', import.meta.url))
    }
  },
  server: {
    host: '127.0.0.1',
    port: 5174,
    proxy: {
      '/api': {
        target: apiTarget,
        changeOrigin: false
      }
    }
  }
})
