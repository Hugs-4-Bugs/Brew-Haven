import { defineConfig } from 'vite';
import react from '@vitejs/plugin-react';

// https://vitejs.dev/config/
export default defineConfig({
  plugins: [react()],
  server: {
    port: 3000,
    // Platform preview gateway (preview-<bot-id>.space-z.ai) rewrites the Host
    // header to the Alibaba FC runtime domain (*.fcapp.run) before reaching this
    // dev server; without allowedHosts Vite answers 403 "Blocked request".
    // Dot-prefix entries allow any subdomain of the two platform domains only.
    allowedHosts: ['.fcapp.run', '.space-z.ai'],
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
      // Revenue-platform backend routes (additive, same trailing-slash convention).
      '/campaign/': { target: 'http://localhost:8000', changeOrigin: true },
      '/analytics/': { target: 'http://localhost:8000', changeOrigin: true },
      '/customer/': { target: 'http://localhost:8000', changeOrigin: true },
      '/feedback/': { target: 'http://localhost:8000', changeOrigin: true },
      '/events/': { target: 'http://localhost:8000', changeOrigin: true },
      '/automation/': { target: 'http://localhost:8000', changeOrigin: true },
      // Payment platform routes (checkout pricing / intents / webhook land on the backend).
      '/checkout/': { target: 'http://localhost:8000', changeOrigin: true },
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
