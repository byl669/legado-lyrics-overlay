import { createApp } from 'vue'
import App from './App.vue'
import router from '@/router'
import store from '@/store'
import 'element-plus/theme-chalk/dark/css-vars.css'
import '@/assets/oled.css'
import { syncDocumentTheme } from '@/utils/readerTheme'

createApp(App).use(store).use(router).mount('#app')
// 书架 同步Element PLUS 夜间模式 & OLED 纯黑
watch(
  () => useBookStore().config.theme,
  theme => syncDocumentTheme(theme),
  { immediate: true },
)

window.addEventListener('vite:preloadError', event => {
  event.preventDefault()
})
