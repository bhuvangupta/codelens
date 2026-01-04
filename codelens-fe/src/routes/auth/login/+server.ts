import { redirect } from '@sveltejs/kit';
import { env as privateEnv } from '$env/dynamic/private';
import { env as publicEnv } from '$env/dynamic/public';
import type { RequestHandler } from './$types';

export const GET: RequestHandler = async ({ url, cookies }) => {
	// Get provider from query param (default to google)
	const provider = url.searchParams.get('provider') || 'google';

	// Get redirect path if provided
	const redirectPath = url.searchParams.get('redirect');

	// Validate provider
	if (!['google', 'github'].includes(provider)) {
		throw redirect(302, '/?error=invalid_provider');
	}

	// Store redirect path in cookie for after OAuth completes
	if (redirectPath) {
		cookies.set('auth_redirect', redirectPath, {
			path: '/',
			httpOnly: true,
			maxAge: 300 // 5 minutes
		});
	} else {
		cookies.delete('auth_redirect', { path: '/' });
	}

	// Determine OAuth URL:
	// - If PUBLIC_API_URL is set and not localhost, we're in production → use relative path
	// - Otherwise, we're in dev → use BACKEND_URL (different port)
	const publicApiUrl = publicEnv.PUBLIC_API_URL || '';
	const backend = privateEnv.BACKEND_URL || 'http://localhost:9292';

	// Production if PUBLIC_API_URL is set and doesn't contain localhost
	const isProduction = publicApiUrl &&
		!publicApiUrl.includes('localhost') &&
		!publicApiUrl.includes('127.0.0.1');

	let oauthUrl: string;
	if (isProduction) {
		// Production: relative path (nginx routes to backend)
		oauthUrl = `/oauth2/authorization/${provider}`;
	} else {
		// Development: need full backend URL (different port)
		oauthUrl = `${backend}/oauth2/authorization/${provider}`;
	}

	console.log('[Auth Login] PUBLIC_API_URL:', publicApiUrl, 'BACKEND_URL:', backend, 'isProduction:', isProduction);
	console.log('[Auth Login] Redirecting to:', oauthUrl);

	// Redirect to Spring Boot OAuth2 endpoint for the specified provider
	throw redirect(302, oauthUrl);
};
