import react from '@vitejs/plugin-react'
import { defineConfig, loadEnv } from 'vite'

export default defineConfig(({ mode }) => {
  const env = loadEnv(mode, process.cwd(), '')

  return {
    plugins: [react()],
    server: env.ADMIN_API_PROXY_TARGET
      ? {
          proxy: {
            '/api': env.ADMIN_API_PROXY_TARGET,
          },
        }
      : undefined,
  }
})
