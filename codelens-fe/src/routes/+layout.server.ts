import type { LayoutServerLoad } from './$types';
import { env } from '$env/dynamic/public';

export const load: LayoutServerLoad = async ({ locals }) => {
	return {
		user: locals.user,
		// Pass login config to client (env/dynamic/public doesn't work on client)
		loginConfig: {
			googleEnabled: env.PUBLIC_GOOGLE_LOGIN_ENABLED !== 'false',
			githubEnabled: env.PUBLIC_GITHUB_LOGIN_ENABLED === 'true'
		}
	};
};
