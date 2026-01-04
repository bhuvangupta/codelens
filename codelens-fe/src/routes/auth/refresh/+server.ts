import { json } from '@sveltejs/kit';
import { env } from '$env/dynamic/private';
import { dev } from '$app/environment';
import type { RequestHandler } from './$types';

const BACKEND = env.BACKEND_URL || 'http://localhost:9292';

// Decode JWT payload (without verification)
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

export const POST: RequestHandler = async ({ cookies }) => {
	const accessToken = cookies.get('access_token');
	const refreshToken = cookies.get('refresh_token');

	// If access token is still valid, return success
	if (accessToken && !isTokenExpired(accessToken)) {
		return json({ success: true, refreshed: false });
	}

	// No refresh token available
	if (!refreshToken) {
		return json({ success: false, error: 'No refresh token' }, { status: 401 });
	}

	// Check if refresh token is expired
	if (isTokenExpired(refreshToken)) {
		cookies.delete('access_token', { path: '/' });
		cookies.delete('refresh_token', { path: '/' });
		return json({ success: false, error: 'Refresh token expired' }, { status: 401 });
	}

	try {
		const response = await fetch(`${BACKEND}/api/auth/refresh`, {
			method: 'POST',
			headers: { 'Content-Type': 'application/json' },
			body: JSON.stringify({ refreshToken })
		});

		if (!response.ok) {
			cookies.delete('access_token', { path: '/' });
			cookies.delete('refresh_token', { path: '/' });
			return json({ success: false, error: 'Refresh failed' }, { status: 401 });
		}

		const data = await response.json();

		// Set new access token cookie
		cookies.set('access_token', data.accessToken, {
			path: '/',
			httpOnly: true,
			secure: !dev,
			sameSite: 'lax',
			maxAge: data.expiresIn || 3600
		});

		return json({ success: true, refreshed: true });
	} catch (error) {
		console.error('Token refresh error:', error);
		return json({ success: false, error: 'Refresh request failed' }, { status: 500 });
	}
};
