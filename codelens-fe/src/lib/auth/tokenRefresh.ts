/**
 * Proactive token refresh utility.
 * Periodically checks if the access token needs refreshing and refreshes it before expiry.
 */

import { browser } from '$app/environment';

let refreshInterval: ReturnType<typeof setInterval> | null = null;
let lastRefreshTime = 0;

const REFRESH_CHECK_INTERVAL = 5 * 60 * 1000; // Check every 5 minutes
const MIN_REFRESH_INTERVAL = 60 * 1000; // Don't refresh more than once per minute

/**
 * Trigger a proactive token refresh.
 */
async function refreshTokenProactively(): Promise<boolean> {
	// Don't refresh too frequently
	if (Date.now() - lastRefreshTime < MIN_REFRESH_INTERVAL) {
		return true;
	}

	try {
		const response = await fetch('/auth/refresh', {
			method: 'POST',
			credentials: 'include'
		});

		if (response.ok) {
			const data = await response.json();
			if (data.success) {
				lastRefreshTime = Date.now();
				return true;
			}
		}

		// If refresh failed, the user will be logged out on next page load
		return false;
	} catch {
		return false;
	}
}

/**
 * Start proactive token refresh. Call this when the user is authenticated.
 */
export function startProactiveRefresh(): void {
	if (!browser || refreshInterval) return;

	// Do an initial refresh check
	refreshTokenProactively();

	// Set up periodic refresh
	refreshInterval = setInterval(() => {
		// Only refresh if the page is visible
		if (document.visibilityState === 'visible') {
			refreshTokenProactively();
		}
	}, REFRESH_CHECK_INTERVAL);

	// Also refresh when the page becomes visible after being hidden
	document.addEventListener('visibilitychange', handleVisibilityChange);
}

/**
 * Stop proactive token refresh. Call this on logout.
 */
export function stopProactiveRefresh(): void {
	if (!browser) return;

	if (refreshInterval) {
		clearInterval(refreshInterval);
		refreshInterval = null;
	}
	document.removeEventListener('visibilitychange', handleVisibilityChange);
}

function handleVisibilityChange(): void {
	if (document.visibilityState === 'visible') {
		// Page became visible, check if we need to refresh
		refreshTokenProactively();
	}
}
