import { defineConfig } from 'vite'
import vue from '@vitejs/plugin-vue'

const BACKEND = 'http://127.0.0.1:8080'

/**
 * 前后端统一约定：所有接口都挂在 /api 前缀下（运维 API 在 /api/ops 下），
 * 因此只需要一条 /api 代理，全部转发到本机 8080 的 Spring Boot 服务。
 */
const proxy = {
  '/api': { target: BACKEND, changeOrigin: true }
}

export default defineConfig({
  plugins: [vue()],
  server: {
    // 固定 IPv4 回环：Windows 下默认解析到 ::1 会导致浏览器/curl 访问 127.0.0.1:5173 失败
    host: '127.0.0.1',
    port: 5173,
    strictPort: false,
    proxy
  },
  preview: {
    port: 4173,
    proxy
  },
  build: {
    outDir: 'dist',
    sourcemap: false,
    chunkSizeWarningLimit: 1024
  }
})
