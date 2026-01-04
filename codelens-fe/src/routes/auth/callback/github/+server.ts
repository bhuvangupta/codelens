import { env } from '$env/dynamic/private';
import type { RequestHandler } from './$types';

const BACKEND = env.BACKEND_URL || 'http://localhost:9292';

export const GET: RequestHandler = async ({ url, request }) => {
	// Proxy to Spring Boot's OAuth2 callback endpoint for GitHub
	// Spring Security is configured with loginProcessingUrl("/auth/callback/*")
	const targetUrl = `${BACKEND}/auth/callback/github${url.search}`;

	const response = await fetch(targetUrl, {
		method: 'GET',
		headers: {
			'Host': new URL(BACKEND).host,
			'X-Forwarded-Host': url.host,
			'X-Forwarded-Proto': url.protocol.replace(':', ''),
			'X-Forwarded-Port': url.port || (url.protocol === 'https:' ? '443' : '80'),
			'Cookie': request.headers.get('cookie') || ''
		},
		redirect: 'manual'
	});

	// Build response headers
	const headers = new Headers();

	// Forward redirect location
	const location = response.headers.get('location');
	if (location) {
		headers.set('Location', location);
	}

	// Forward Set-Cookie headers
	const setCookies = response.headers.getSetCookie?.() || [];
	setCookies.forEach(cookie => {
		headers.append('Set-Cookie', cookie);
	});

	// Forward redirects (including final redirect to /auth/callback with tokens)
	if (response.status >= 300 && response.status < 400 && location) {
		return new Response(null, {
			status: response.status,
			headers
		});
	}

	// Forward other responses
	const body = await response.text();
	headers.set('Content-Type', response.headers.get('Content-Type') || 'text/html');

	return new Response(body, {
		status: response.status,
		headers
	});
};
