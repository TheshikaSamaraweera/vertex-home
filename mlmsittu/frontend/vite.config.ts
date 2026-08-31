import { defineConfig } from 'vite';
import react from '@vitejs/plugin-react';
import tailwindcss from '@tailwindcss/vite';

export default defineConfig({
  plugins: [react(), tailwindcss()],
  server: {
    port: 5173,
    proxy: {
      // Everything under /api and the auth endpoints goes to Spring on 8080.
      //
      // This is architecture 6.1's "recommended" arrangement reproduced for development: the
      // browser sees one origin, so the session cookie is first-party, SameSite=Lax works as
      // intended, and there is no CORS configuration anywhere in the project. In production
      // Cloudflare does the same routing.
      //
      // The alternative — running the frontend on its own origin — would force SameSite=None,
      // which requires Secure, which requires HTTPS in local development. Not worth it.
      '/api': {
        target: 'http://localhost:8080',
        changeOrigin: false,
      },
      '/v3/api-docs': { target: 'http://localhost:8080', changeOrigin: false },
    },
  },
  build: {
    outDir: 'dist',
    sourcemap: true,
  },
});
