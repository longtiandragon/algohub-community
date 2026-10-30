import { createApp } from 'vue'
import { createPinia } from 'pinia'
// Element Plus 按需引入（vite.config.ts），中文语言包在 App.vue 的 el-config-provider 上。
// 下面这些样式插件管不到，或者必须赶在 styles.css 之前：
//  - 组件库的变量表，以及 styles.css 要覆盖的组件（卡片、表格、空状态、按钮、标签、头像、输入框、下拉框）：
//    按需引入时它们随页面分块晚加载，会把 styles.css 里同等优先级的覆盖（品牌色、表格配色等）盖回默认值。
//    styles.css 再覆盖别的组件时，把它的样式也加在这里；
//  - 用 JS 调用的提示消息、确认框（写在代码里的 import 插件管不到样式），以及 v-loading 遮罩。
import 'element-plus/es/components/base/style/css'
import 'element-plus/es/components/card/style/css'
import 'element-plus/es/components/table/style/css'
import 'element-plus/es/components/empty/style/css'
import 'element-plus/es/components/button/style/css'
import 'element-plus/es/components/tag/style/css'
import 'element-plus/es/components/avatar/style/css'
import 'element-plus/es/components/input/style/css'
import 'element-plus/es/components/select/style/css'
import 'element-plus/es/components/message/style/css'
import 'element-plus/es/components/message-box/style/css'
import 'element-plus/es/components/loading/style/css'
// 数字字体 Outfit、等宽字体 JetBrains Mono：只引拉丁子集，打包进前端，运行时不请求外部字体
import '@fontsource/outfit/latin-500.css'
import '@fontsource/outfit/latin-600.css'
import '@fontsource/outfit/latin-700.css'
import '@fontsource/jetbrains-mono/latin-500.css'
import '@fontsource/jetbrains-mono/latin-600.css'
import './styles.css'
// 工具类放在最后引入，才能覆盖前面的组件库与全局样式
import 'virtual:uno.css'

import App from './App.vue'
import router from './router'
import { useAuthStore } from './stores/auth'
import { useThemeStore } from './stores/theme'

const app = createApp(App)
const pinia = createPinia()
app.use(pinia)
// 挂载前先按本机记的选择（没选过就跟随系统）设好主题，避免浅色闪一下再变深色
useThemeStore(pinia)
app.use(router)

// 启动时用 HttpOnly 刷新令牌换一次访问令牌，确认登录状态后再挂载，避免首屏误跳转；
// 也等第一个路由解析完：否则挂载那一刻路由还是空的，登录页这类整页会先画出侧栏和底栏，顺带去读账号状态。
const auth = useAuthStore()
auth
  .bootstrap()
  .then(() => router.isReady())
  .catch(() => { /* App 显示会话恢复错误及重试入口，保留刷新 Cookie。 */ })
  .finally(() => app.mount('#app'))
