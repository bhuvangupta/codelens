import { env } from '$env/dynamic/private';
import type { RequestHandler } from './$types';

const BACKEND = env.BACKEND_URL || 'http://localhost:9292';

export const GET: RequestHandler = async ({ params, request, cookies, locals }) => {
	return proxyRequest('GET', params.path, request, cookies, locals);
};

export const POST: RequestHandler = async ({ params, request, cookies, locals }) => {
	return proxyRequest('POST', params.path, request, cookies, locals);
};

export const PUT: RequestHandler = async ({ params, request, cookies, locals }) => {
	return proxyRequest('PUT', params.path, request, cookies, locals);
};

export const DELETE: RequestHandler = async ({ params, request, cookies, locals }) => {
	return proxyRequest('DELETE', params.path, request, cookies, locals);
};

export const PATCH: RequestHandler = async ({ params, request, cookies, locals }) => {
	return proxyRequest('PATCH', params.path, request, cookies, locals);
};

async function proxyRequest(
	method: string,
	path: string,
	request: Request,
	cookies: { get: (name: string) => string | undefined },
	locals: App.Locals
): Promise<Response> {
	const url = new URL(request.url);
	const targetUrl = `${BACKEND}/api/${path}${url.search}`;

	// Get JWT from httpOnly cookie
	const accessToken = cookies.get('access_token');

	// Build headers
	const headers: Record<string, string> = {
		'Content-Type': 'application/json'
	};

	// Add Authorization header if we have a token
	if (accessToken) {
		headers['Authorization'] = `Bearer ${accessToken}`;
	}

	// Forward specific headers from original request
	const forwardHeaders = ['x-requested-with', 'accept', 'accept-language'];
	forwardHeaders.forEach((header) => {
		const value = request.headers.get(header);
		if (value) headers[header] = value;
	});

	// Build request options
	const options: RequestInit = {
		method,
		headers
	};

	// Add body for methods that support it
	if (['POST', 'PUT', 'PATCH'].includes(method)) {
		try {
			const body = await request.text();
			if (body) {
				options.body = body;
			}
		} catch {
			// No body
		}
	}

	try {
		const response = await fetch(targetUrl, options);

		// Create response with same status and headers
		const responseHeaders = new Headers();
		response.headers.forEach((value, key) => {
			// Don't forward certain headers
			if (!['transfer-encoding', 'connection', 'www-authenticate'].includes(key.toLowerCase())) {
				responseHeaders.set(key, value);
			}
		});

		// Suppress 401 in bypass mode
		if (response.status === 401 && locals.user?.id === 'dev-user') {
			console.warn(`Suppressing 401 for ${path} in dev mode`);
			return new Response(JSON.stringify({ message: 'Auth bypassed', content: [], totalElements: 0 }), {
				status: 200,
				headers: { 'Content-Type': 'application/json' }
			});
		}

		const responseBody = await response.text();

		return new Response(responseBody, {
			status: response.status,
			statusText: response.statusText,
			headers: responseHeaders
		});
	} catch (error) {
		console.error('Proxy error:', error);
		return new Response(JSON.stringify({ error: 'Backend unavailable' }), {
			status: 503,
			headers: { 'Content-Type': 'application/json' }
		});
	}
}
