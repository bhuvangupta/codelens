import { env } from '$env/dynamic/private';
import type { RequestHandler } from './$types';

const BACKEND = env.BACKEND_URL || 'http://localhost:9292';

export const GET: RequestHandler = async ({ params, url, request }) => {
	const { provider } = params;

	// Validate provider - allow google, github, and google-* for domain-specific OAuth
	if (provider !== 'google' && provider !== 'github' && !provider.startsWith('google-')) {
		return new Response(JSON.stringify({ error: 'Invalid provider' }), {
			status: 400,
			headers: { 'Content-Type': 'application/json' }
		});
	}

	// Proxy to Spring Boot's OAuth2 callback endpoint
	const targetUrl = `${BACKEND}/auth/callback/${provider}${url.search}`;

	console.log(`[OAuth ${provider}] Proxying to:`, targetUrl);
	console.log(`[OAuth ${provider}] BACKEND_URL:`, BACKEND);

	try {
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

		console.log(`[OAuth ${provider}] Backend response status:`, response.status);
		console.log(`[OAuth ${provider}] Backend response headers:`, Object.fromEntries(response.headers.entries()));

		// Build response headers
		const headers = new Headers();

		// Forward redirect location
		const location = response.headers.get('location');
		if (location) {
			console.log(`[OAuth ${provider}] Redirect location:`, location);
			headers.set('Location', location);
		}

		// Forward Set-Cookie headers
		const setCookies = response.headers.getSetCookie?.() || [];
		console.log(`[OAuth ${provider}] Set-Cookie headers:`, setCookies.length);
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
		console.log(`[OAuth ${provider}] Response body length:`, body.length);
		if (response.status >= 400) {
			console.log(`[OAuth ${provider}] Error response:`, body.substring(0, 500));
		}
		headers.set('Content-Type', response.headers.get('Content-Type') || 'text/html');

		return new Response(body, {
			status: response.status,
			headers
		});
	} catch (error) {
		console.error(`[OAuth ${provider}] Fetch error:`, error);
		return new Response(JSON.stringify({ error: 'OAuth callback failed', details: String(error) }), {
			status: 500,
			headers: { 'Content-Type': 'application/json' }
		});
	}
};
