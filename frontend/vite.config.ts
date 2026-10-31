import { defineConfig } from 'vite';
import react from '@vitejs/plugin-react';

// 开发期 5173 端口，/api 代理到 Spring Boot 8080（前端页面方案第 2 节）
// 目标用 127.0.0.1 而非 localhost：Node 会先解析到 IPv6 ::1 导致连接被拒（F3 实测踩坑）
export default defineConfig({
  plugins: [react()],
  server: {
    port: 5173,
    proxy: {
      '/api': {
        target: 'http://127.0.0.1:8080',
        changeOrigin: true,
      },
    },
  },
});
