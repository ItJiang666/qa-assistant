import { createApp } from 'vue'
import ElementPlus from 'element-plus'
import zhCn from 'element-plus/es/locale/lang/zh-cn'
import 'element-plus/dist/index.css'
import App from './App.vue'
import './style.css'

// 用中文语言包：日期选择、分页、上传等组件的内置文案才会是中文
createApp(App).use(ElementPlus, { locale: zhCn }).mount('#app')
