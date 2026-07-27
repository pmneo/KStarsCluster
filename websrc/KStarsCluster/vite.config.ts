import { defineConfig } from 'vite'
import react from '@vitejs/plugin-react'

// KStarsCluster serves this app's build output as static resources from an
// embedded Jetty server (see ServerRunner.java) — build straight into the
// Maven resources dir so `mvn package` picks it up without a copy step.
export default defineConfig({
  plugins: [react()],
  build: {
    outDir: '../../src/main/resources/web',
    emptyOutDir: true,
  },
  server: {
    proxy: {
      '/cmd': 'http://localhost:9080',
      '/logging': { target: 'ws://localhost:9080', ws: true },
      '/status': { target: 'ws://localhost:9080', ws: true },
    },
  },
})
