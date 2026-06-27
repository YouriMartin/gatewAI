import { defineConfig } from 'vite';
import { svelte } from '@sveltejs/vite-plugin-svelte';

// Build output goes straight into the Spring Boot static resources so the SPA
// is bundled into the jar (mono-repo, self-contained on-premise artifact).
export default defineConfig({
  plugins: [svelte()],
  build: {
    outDir: '../../../target/classes/static',
    emptyOutDir: true,
  },
  server: {
    // Dev only: proxy API calls to the running Spring Boot backend.
    proxy: {
      '/v1': 'http://localhost:8080',
    },
  },
});
