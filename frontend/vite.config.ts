import { defineConfig } from 'vite'
import react from '@vitejs/plugin-react'

export default defineConfig({
  plugins: [react()],
  server: {
    host: true,
    port: 5173,
    proxy: {
      // ponytail: API_PROXY_TARGET lets docker-compose point this at the "api"
      // service by name; local `npm run dev` keeps the localhost default.
      '/api': process.env.API_PROXY_TARGET || 'http://localhost:8080'
    }
  }
})
