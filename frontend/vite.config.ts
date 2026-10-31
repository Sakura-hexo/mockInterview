import { defineConfig } from 'vite';
import react from '@vitejs/plugin-react';

// 开发期 5173 端口，/api 代理到 Spring Boot 8080（前端页面方案第 2 节）
export default defineConfig({
  plugins: [react()],
  server: {
    port: 5173,
    proxy: {
      '/api': {
        target: 'http://localhost:8080',
        changeOrigin: true,
      },
    },
  },
});
