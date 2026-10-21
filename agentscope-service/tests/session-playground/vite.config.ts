// Copyright 2024-2026 the original author or authors.
// Licensed under the Apache License, Version 2.0.
import { defineConfig } from 'vitest/config';
import react from '@vitejs/plugin-react';

// Independent entry and output: building this tool does not replace the Console assets.
const target = process.env.PLAYGROUND_API_TARGET || 'http://127.0.0.1:18081';
const proxy = { '/api': { target, changeOrigin: true } };
export default defineConfig({
  plugins: [react()],
  define: { __PLAYGROUND_API_TARGET__: JSON.stringify(target) },
  build: { outDir: 'dist', emptyOutDir: true },
  test: { include: ['src/**/*.test.ts'] },
  server: { host: '127.0.0.1', port: 18082, strictPort: true, proxy },
  preview: { host: '127.0.0.1', port: 18082, strictPort: true, proxy },
});
