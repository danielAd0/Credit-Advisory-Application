import react from '@vitejs/plugin-react';
import { defineConfig } from 'vite';

// https://vite.dev/config/
export default defineConfig({
  plugins: [react()],
  server: {
    // Forward API calls to Spring Boot, so the browser sees one origin
    // (session cookie and CSRF cookie just work, no CORS needed).
    proxy: {
      '/api': 'http://localhost:8080',
    },
  },
});
