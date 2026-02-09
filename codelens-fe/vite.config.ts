import { sveltekit } from '@sveltejs/kit/vite';
import { defineConfig } from 'vite';

export default defineConfig({
	plugins: [sveltekit()],
	server: {
		host: true,
		port: 5175,
		strictPort: true,
		allowedHosts: true,
		// Disable HMR when accessing via reverse proxy/external hostname
		// Set to object with host config if you want HMR through proxy
		hmr: false
                //hmr: {
                //   host: 'localhost',
                //   clientPort: 5175
                //}
		// Note: API proxy is handled by SvelteKit route /api/[...path]/+server.ts
		// which adds JWT from httpOnly cookie to Authorization header
	}
});
