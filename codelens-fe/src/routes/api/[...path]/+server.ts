import { env } from '$env/dynamic/private';
import type { RequestHandler } from './$types';

const BACKEND = env.BACKEND_URL || 'http://localhost:9292';

export const GET: RequestHandler = async ({ params, request, cookies }) => {
	return proxyRequest('GET', params.path, request, cookies);
};

export const POST: RequestHandler = async ({ params, request, cookies }) => {
	return proxyRequest('POST', params.path, request, cookies);
};

export const PUT: RequestHandler = async ({ params, request, cookies }) => {
	return proxyRequest('PUT', params.path, request, cookies);
};

export const DELETE: RequestHandler = async ({ params, request, cookies }) => {
	return proxyRequest('DELETE', params.path, request, cookies);
};

export const PATCH: RequestHandler = async ({ params, request, cookies }) => {
	return proxyRequest('PATCH', params.path, request, cookies);
};

async function proxyRequest(
	method: string,
	path: string,
	request: Request,
	cookies: { get: (name: string) => string | undefined }
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
			if (!['transfer-encoding', 'connection'].includes(key.toLowerCase())) {
				responseHeaders.set(key, value);
			}
		});

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
