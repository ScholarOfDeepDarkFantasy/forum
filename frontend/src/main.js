import { createApp } from 'vue'
import { createPinia } from 'pinia'
// Element Plus 的组件由 unplugin-vue-components 在构建期按需注入（见 vite.config.js）。
// v-loading 是指令，插件解析不到，必须手工注册（ElLoading.directive）。
import { ElLoading } from 'element-plus'
// 这里只保留「编译器看不见」的样式：基础 CSS 变量、暗色变量、4 条编程式 API 的样式。
// base.css 每个组件的 style/css 也会引一次，重复 import 会被 Vite 去重；
// 显式写在这里是为了让「变量来源」在入口可见（tokens.css 覆盖 --el-color-primary 依赖它先加载）。
import 'element-plus/theme-chalk/base.css'
import 'element-plus/theme-chalk/dark/css-vars.css'
// 编程式 API 不出现在模板里，插件解析不到，必须手工引样式。清单与源码一一对应：
//   ElMessage      → src/App.vue、src/api/index.js 与 7 个视图
//   ElMessageBox   → views/AdminView.vue、views/PostDetailView.vue
//   ElNotification → composables/useNotificationStream.js
//   ElLoading      → 本文件的 app.directive('loading', ElLoading.directive)
// 将来新增 ElImageViewer 等编程式调用时，这里要补一条 style/css。
import 'element-plus/es/components/message/style/css'
import 'element-plus/es/components/message-box/style/css'
import 'element-plus/es/components/notification/style/css'
import 'element-plus/es/components/loading/style/css'
import App from './App.vue'
import router from './router'
import './styles/tokens.css'
import './styles/common.css'
import './styles/markdown.css'
import 'highlight.js/styles/github.css'
import './styles/highlight-dark.css'
import './style.css'
import { initTheme } from './composables/useTheme'

initTheme()

const app = createApp(App)

app.use(createPinia())
app.use(router)
// 语言包不再在这里注入：改成 App.vue 根部的 <el-config-provider :locale="zhCn">。
// 按需引入后不再有 Element Plus 的全量注册，全局配置唯一的落点就是组件树。
app.directive('loading', ElLoading.directive)

app.mount('#app')
