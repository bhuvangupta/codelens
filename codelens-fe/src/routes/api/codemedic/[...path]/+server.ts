import { env } from '$env/dynamic/private';
import { PUBLIC_CODEMEDIC_PORT } from '$env/static/public';
import type { RequestHandler } from './$types';

const PORT = PUBLIC_CODEMEDIC_PORT || '8000';
const BACKEND = `http://localhost:${PORT}`;

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

async function proxyRequest(
	method: string,
	path: string,
	request: Request,
	cookies: { get: (name: string) => string | undefined }
): Promise<Response> {
	const url = new URL(request.url);
	const targetUrl = `${BACKEND}/${path}${url.search}`;

	// Prepare headers
	const headers: Record<string, string> = {};
	
	// Forward Content-Type if present (critical for multipart/form-data)
	const contentType = request.headers.get('Content-Type');
	if (contentType) {
		headers['Content-Type'] = contentType;
	}

	// Request content
	const options: RequestInit = {
		method,
		headers
	};

	// Body - use arrayBuffer to support binary data (like file uploads)
	if (['POST', 'PUT', 'PATCH'].includes(method)) {
		try {
			const body = await request.arrayBuffer();
			if (body.byteLength > 0) {
				options.body = body;
			}
		} catch {
			// No body or error reading body
		}
	}

	try {
		const response = await fetch(targetUrl, options);
		
		// Handle streaming response (SSE)
		const responseContentType = response.headers.get('Content-Type');
		if (responseContentType && responseContentType.includes('text/event-stream')) {
			// Forward headers
			const responseHeaders = new Headers();
			response.headers.forEach((value, key) => {
				if (!['transfer-encoding', 'connection', 'content-length'].includes(key.toLowerCase())) {
					responseHeaders.set(key, value);
				}
			});
			responseHeaders.set('Content-Type', responseContentType);
			// Important: set cache-control for SSE
			responseHeaders.set('Cache-Control', 'no-cache');
			responseHeaders.set('Connection', 'keep-alive');

			return new Response(response.body, {
				status: response.status,
				statusText: response.statusText,
				headers: responseHeaders
			});
		}

		// Handle normal response
		const responseBody = await response.arrayBuffer();

		// Forward headers
		const responseHeaders = new Headers();
		response.headers.forEach((value, key) => {
			if (!['transfer-encoding', 'connection', 'content-length'].includes(key.toLowerCase())) {
				responseHeaders.set(key, value);
			}
		});

		if (responseContentType) {
			responseHeaders.set('Content-Type', responseContentType);
		}

		return new Response(responseBody, {
			status: response.status,
			statusText: response.statusText,
			headers: responseHeaders
		});
	} catch (error) {
		console.error('CodeMedic Proxy error:', error);
		return new Response(JSON.stringify({ error: 'CodeMedic Backend unavailable' }), {
			status: 503,
			headers: { 'Content-Type': 'application/json' }
		});
	}
}
