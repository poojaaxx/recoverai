import react from '@vitejs/plugin-react'
import { defineConfig } from 'vite'

// https://vite.dev/config/
export default defineConfig({
  plugins: [react()],
  // Read .env from the monorepo root so backend and frontend share one file.
  envDir: '../',
})
