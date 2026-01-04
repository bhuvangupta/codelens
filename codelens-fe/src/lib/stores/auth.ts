import { writable, derived } from 'svelte/store';
import type { User } from '$api/client';

interface AuthState {
	user: User | null;
	loading: boolean;
	error: string | null;
}

function createAuthStore() {
	const { subscribe, set, update } = writable<AuthState>({
		user: null,
		loading: true,
		error: null
	});

	return {
		subscribe,

		setUser: (user: User | null) => {
			update((state) => ({ ...state, user, loading: false, error: null }));
		},

		setLoading: (loading: boolean) => {
			update((state) => ({ ...state, loading }));
		},

		setError: (error: string) => {
			update((state) => ({ ...state, error, loading: false }));
		},

		logout: () => {
			set({ user: null, loading: false, error: null });
		}
	};
}

export const auth = createAuthStore();

export const isAuthenticated = derived(auth, ($auth) => $auth.user !== null);
export const currentUser = derived(auth, ($auth) => $auth.user);
