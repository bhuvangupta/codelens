import { browser } from '$app/environment';

/**
 * Get the appropriate API base URL based on context
 *
 * - SSR (server-side): Uses proxy route '/api' which forwards to BACKEND_URL
 * - Client (browser): Uses proxy route '/api' for normal calls
 * - Client direct: Uses PUBLIC_API_URL for WebSockets, large uploads, etc.
 */

// Proxy route - works for both SSR and client (recommended for most API calls)
export const API_BASE = '/api';

// Direct backend URL - only use for WebSockets, streaming, large uploads
export function getDirectBackendUrl(): string {
	if (browser) {
		// Client-side: use relative path (same domain via nginx)
		// Or use window location origin for WebSocket connections
		return window.location.origin;
	}
	// Server-side: this shouldn't be called, use BACKEND_URL via proxy instead
	return 'http://localhost:9292';
}

// WebSocket URL for real-time features
export function getWebSocketUrl(path: string): string {
	const baseUrl = getDirectBackendUrl();
	const wsProtocol = baseUrl.startsWith('https') ? 'wss' : 'ws';
	const httpUrl = new URL(baseUrl);
	return `${wsProtocol}://${httpUrl.host}${path}`;
}
