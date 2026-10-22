/// <reference types="vitest" />
import { defineConfig } from 'vite';
import react from '@vitejs/plugin-react';

// The app is served by Even Hand's own server on 127.0.0.1 (eh-app LocalServer): the build lands in
// target/web, which Maven packages into the jar at /web. During development, `npm run dev` proxies the
// API to a running `java -jar even-hand.jar serve`.
export default defineConfig({
  plugins: [react()],
  base: '/',
  build: {
    outDir: 'target/web',
    emptyOutDir: true,
    sourcemap: false,
    assetsDir: 'assets',
    chunkSizeWarningLimit: 600,
  },
  server: {
    port: 5173,
    proxy: {
      '/status': 'http://127.0.0.1:8642',
      '/inspect': 'http://127.0.0.1:8642',
      '/analyse': 'http://127.0.0.1:8642',
      '/job': 'http://127.0.0.1:8642',
      '/template.csv': 'http://127.0.0.1:8642',
    },
  },
  test: {
    environment: 'jsdom',
    globals: true,
    setupFiles: ['src/test/setup.ts'],
  },
});
