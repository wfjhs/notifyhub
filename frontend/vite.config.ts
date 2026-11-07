import { defineConfig } from 'vite';
import vue from '@vitejs/plugin-vue';
import { fileURLToPath, URL } from 'node:url';

// 前端独立部署，开发时后端默认在 8082。
// 用 proxy 转发 /api：既绕开跨域，也不用为了调试去改动后端配置。
// 目标地址可用 VITE_API_TARGET 覆盖（换端口验证、或连测试环境时用）。
const apiTarget = process.env.VITE_API_TARGET ?? 'http://localhost:8082';

// 端口用 5174 而不是 5173 —— 5173 留给了项目一（CodeJudge），两个项目可能同时开。
export default defineConfig({
  plugins: [vue()],
  resolve: {
    alias: {
      '@': fileURLToPath(new URL('./src', import.meta.url)),
    },
  },
  server: {
    port: 5174,
    proxy: {
      '/api': {
        target: apiTarget,
        changeOrigin: true,
      },
    },
  },
  build: {
    outDir: 'dist',
    // 分离部署后由网关（nginx）托管，产物里不需要再嵌进后端 jar
    emptyOutDir: true,
  },
});
