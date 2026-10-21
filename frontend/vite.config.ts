import { defineConfig } from 'vite';
import react from '@vitejs/plugin-react';

// https://vitejs.dev/config/
export default defineConfig({
  plugins: [react()],
  server: {
    port: 5173,
    proxy: {
      // Dev proxy: same-origin API calls avoid CORS entirely in local development.
      // NOTE: keys use trailing slashes so SPA routes (/billing, /bills, /users,
      // /products, /categories, /dashboard) are NOT captured — vite proxy keys are
      // plain path prefixes, and '/bill' would also match '/billing'.
      '/api/': { target: 'http://localhost:8000', changeOrigin: true },
      '/user/': { target: 'http://localhost:8000', changeOrigin: true },
      '/product/': { target: 'http://localhost:8000', changeOrigin: true },
      '/category/': { target: 'http://localhost:8000', changeOrigin: true },
      '/bill/': { target: 'http://localhost:8000', changeOrigin: true },
      '/dashboard/': { target: 'http://localhost:8000', changeOrigin: true },
    },
  },
  build: {
    outDir: 'dist',
    sourcemap: false,
    chunkSizeWarningLimit: 900,
  },
  test: {
    environment: 'jsdom',
    globals: true,
    include: ['src/tests/**/*.test.{ts,tsx}'],
  },
} as never);
