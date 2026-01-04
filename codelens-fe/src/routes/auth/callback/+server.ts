import { redirect } from '@sveltejs/kit';
import { dev } from '$app/environment';
import type { RequestHandler } from './$types';

export const GET: RequestHandler = async ({ url, cookies }) => {
	console.log('[Auth Callback] URL:', url.toString());
	console.log('[Auth Callback] Search params:', Object.fromEntries(url.searchParams.entries()));

	const accessToken = url.searchParams.get('access_token');
	const refreshToken = url.searchParams.get('refresh_token');
	const error = url.searchParams.get('error');

	if (error) {
		console.log('[Auth Callback] Error received:', error);
		throw redirect(303, `/?error=${encodeURIComponent(error)}`);
	}

	if (!accessToken) {
		console.log('[Auth Callback] No access token received');
		throw redirect(303, '/?error=no_token');
	}

	console.log('[Auth Callback] Tokens received, setting cookies');

	// Store tokens in httpOnly secure cookies
	// In dev mode, secure must be false for localhost
	cookies.set('access_token', accessToken, {
		path: '/',
		httpOnly: true,
		secure: !dev,
		sameSite: 'lax',
		maxAge: 60 * 60 // 1 hour
	});

	if (refreshToken) {
		cookies.set('refresh_token', refreshToken, {
			path: '/',
			httpOnly: true,
			secure: !dev,
			sameSite: 'lax',
			maxAge: 60 * 60 * 24 * 7 // 7 days
		});
	}

	// Check for redirect path stored before OAuth
	const redirectPath = cookies.get('auth_redirect');
	if (redirectPath) {
		cookies.delete('auth_redirect', { path: '/' });
		throw redirect(303, redirectPath);
	}

	// Default redirect to dashboard
	throw redirect(303, '/dashboard');
};
