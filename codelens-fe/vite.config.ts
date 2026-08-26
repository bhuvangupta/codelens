import { sveltekit } from '@sveltejs/kit/vite';
import { defineConfig } from 'vite';

export default defineConfig(({ command }) => ({
	plugins: [sveltekit()],
	// deploy/remote_deploy.sh ships only the adapter-node output (build/) —
	// no package.json, no node_modules. Vite leaves `dependencies` as bare
	// externals in the SSR bundle, so anything statically imported by a page
	// (dompurify, chart.js, ...) fails to resolve on the server and the route
	// 500s on direct load/refresh. Bundle them in so the output is standalone.
	ssr: command === 'build' ? { noExternal: true } : {},
	server: {
		host: true,
		port: 5174,
		strictPort: true,
		allowedHosts: true,
		// Disable HMR when accessing via reverse proxy/external hostname
		// Set to object with host config if you want HMR through proxy
		hmr: false
                //hmr: {
                //   host: 'localhost',
                //   clientPort: 5174
                //}
		// Note: API proxy is handled by SvelteKit route /api/[...path]/+server.ts
		// which adds JWT from httpOnly cookie to Authorization header
	}
}));
