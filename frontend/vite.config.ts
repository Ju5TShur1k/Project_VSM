import { defineConfig, loadEnv } from 'vite'
import react from '@vitejs/plugin-react'

export default defineConfig(({ mode }) => {
  // Empty prefix: loadEnv returns every var, including process env from docker-compose.
  const env = loadEnv(mode, '.', '')
  return {
    plugins: [react()],
    server: {
      host: true,
      port: 5173,
      proxy: {
        // ponytail: API_PROXY_TARGET lets docker-compose point this at the "api"
        // service by name; local `npm run dev` keeps the localhost default.
        '/api': env.API_PROXY_TARGET || 'http://localhost:8080'
      }
    }
  }
})
