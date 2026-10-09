import { defineConfig } from 'vite'
import vue from '@vitejs/plugin-vue'

/**
 * 前端构建配置。
 *
 * 两个关键决策：
 *
 * 1. **构建产物直接输出到后端的 static 目录**（`../src/main/resources/static`）。
 *    这样 `java -jar` 单进程就能提供完整界面 —— 这是"三步快速开始"
 *    （ollama pull → java -jar → 打开浏览器）能成立的前提。
 *    `emptyOutDir: true` 会清掉该目录，正好把 P0 阶段的占位页替换掉。
 *
 * 2. **开发期走 Vite 自带服务器 + 代理**，不依赖后端提供静态资源：
 *    改前端代码即时热更新，不必重新打包 jar。
 *    开发时访问 http://localhost:5173，`/api` 会被转发到 8080。
 */
export default defineConfig({
  plugins: [vue()],
  build: {
    outDir: '../src/main/resources/static',
    emptyOutDir: true,
    // 产物内联阈值调低：界面不大，减少碎文件请求
    assetsInlineLimit: 8192,
    chunkSizeWarningLimit: 1200
  },
  server: {
    port: 5173,
    proxy: {
      '/api': {
        target: 'http://localhost:8080',
        changeOrigin: true
      }
    }
  }
})
