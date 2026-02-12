import type { Handle } from '@sveltejs/kit';
import { env } from '$env/dynamic/private';
import { dev } from '$app/environment';
import { redirect } from '@sveltejs/kit';

const BACKEND = env.BACKEND_URL || 'http://localhost:9292';

// Routes that require authentication
const PROTECTED_ROUTES = [
	'/dashboard',
	'/reviews',
	'/analytics',
	'/settings'
];

// Routes that are always public
const PUBLIC_ROUTES = [
	'/',
	'/auth',
	'/privacy',
	'/terms',
	'/health',
	'/codemedic'
];

// Decode JWT payload (without verification - backend does that)
function decodeJwtPayload(token: string): Record<string, unknown> | null {
	try {
		const parts = token.split('.');
		if (parts.length !== 3) return null;
		const payload = parts[1];
		const decoded = atob(payload.replace(/-/g, '+').replace(/_/g, '/'));
		return JSON.parse(decoded);
	} catch {
		return null;
	}
}

// Check if token is expired
function isTokenExpired(token: string): boolean {
	const payload = decodeJwtPayload(token);
	if (!payload || !payload.exp) return true;
	const expiry = (payload.exp as number) * 1000;
	return Date.now() >= expiry - 60000; // 1 minute buffer
}

// TEMPORARILY DISABLED: Authentication - remove this block to re-enable login
const BYPASS_AUTH = true;
const MOCK_USER = {
	email: 'dev@localhost',
	name: 'Dev User',
	picture: '',
	id: 'dev-user'
};

export const handle: Handle = async ({ event, resolve }) => {
	// Bypass authentication for development
	if (BYPASS_AUTH) {
		event.locals.user = MOCK_USER;
		return resolve(event);
	}

	const accessToken = event.cookies.get('access_token');
	const refreshToken = event.cookies.get('refresh_token');

	let user: { email: string; name: string; picture: string; id: string } | null = null;

	if (accessToken) {
		// Check if access token is expired
		if (isTokenExpired(accessToken)) {
			// Try to refresh
			if (refreshToken && !isTokenExpired(refreshToken)) {
				try {
					const response = await fetch(`${BACKEND}/api/auth/refresh`, {
						method: 'POST',
						headers: { 'Content-Type': 'application/json' },
						body: JSON.stringify({ refreshToken })
					});

					if (response.ok) {
						const data = await response.json();
						event.cookies.set('access_token', data.accessToken, {
							path: '/',
							httpOnly: true,
							secure: !dev,
							sameSite: 'lax',
							maxAge: data.expiresIn || 3600
						});

						const payload = decodeJwtPayload(data.accessToken);
						if (payload) {
							user = {
								email: payload.sub as string,
								name: (payload.name as string) || '',
								picture: (payload.picture as string) || '',
								id: (payload.providerId as string) || ''
							};
						}
					} else {
						// Refresh failed, clear cookies
						event.cookies.delete('access_token', { path: '/' });
						event.cookies.delete('refresh_token', { path: '/' });
					}
				} catch (error) {
					console.error('Token refresh error:', error);
				}
			} else {
				// No valid refresh token, clear cookies
				event.cookies.delete('access_token', { path: '/' });
				event.cookies.delete('refresh_token', { path: '/' });
			}
		} else {
			// Access token still valid, decode user info
			const payload = decodeJwtPayload(accessToken);
			if (payload) {
				user = {
					email: payload.sub as string,
					name: (payload.name as string) || '',
					picture: (payload.picture as string) || '',
					id: (payload.providerId as string) || ''
				};
			}
		}
	}

	// Set user in locals for access in load functions
	event.locals.user = user;

	// Check if route requires authentication
	const path = event.url.pathname;
	const isProtectedRoute = PROTECTED_ROUTES.some(route => path === route || path.startsWith(route + '/'));
	const isPublicRoute = PUBLIC_ROUTES.some(route => path === route || path.startsWith(route + '/'));

	// Redirect unauthenticated users from protected routes to login
	if (!user && isProtectedRoute) {
		const redirectUrl = encodeURIComponent(path);
		throw redirect(302, `/?auth=required&redirect=${redirectUrl}`);
	}

	return resolve(event);
};

export const handleError = ({ error, event }) => {
	console.error('Server error:', error);
	return {
		message: 'Internal Error',
		code: 'UNKNOWN'
	};
};
