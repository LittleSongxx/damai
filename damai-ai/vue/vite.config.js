import { fileURLToPath, URL } from 'node:url'

import { defineConfig } from 'vite'
import vue from '@vitejs/plugin-vue'

const proxyTarget = process.env.VITE_DAMAI_AI_PROXY_TARGET || 'http://127.0.0.1:6089'

export default defineConfig(() => ({
  plugins: [
    vue(),
  ],
  resolve: {
    alias: {
      '@': fileURLToPath(new URL('./src', import.meta.url))
    },
  },
  server: {
    host: '127.0.0.1',
    port: 5174,
    strictPort: true,
    proxy: {
      '/damai-ai-dev': {
        target: proxyTarget,
        changeOrigin: true,
        rewrite: (path) => path.replace(/^\/damai-ai-dev/, '')
      }
    }
  },
  optimizeDeps: {
    exclude: ['@pdftron/webviewer']
  },
  build: {
    rollupOptions: {
      output: {
        manualChunks(id) {
          if (id.includes('node_modules/highlight.js')) {
            return 'syntax-highlight'
          }
          if (id.includes('node_modules/marked') || id.includes('node_modules/dompurify')) {
            return 'markdown-rendering'
          }
          if (id.includes('node_modules/@pdftron/webviewer')) {
            return 'webviewer'
          }
        }
      }
    }
  }
}))
