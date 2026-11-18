import { defineConfig } from 'vite'
import react from '@vitejs/plugin-react'

export default defineConfig({
  plugins: [react()],
  server: {
    port: 5173,
    proxy: { '/api': 'http://localhost:8080' },
  },
  // Let Rollup preserve shared dependency ordering; route-level lazy imports
  // provide the split without introducing cycles inside Ant Design.
  build: { outDir: 'dist', sourcemap: true, manifest: true },
})

