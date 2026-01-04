import { writable, derived } from 'svelte/store';
import { reviews as reviewsApi, type Review, type ReviewDetail } from '$api/client';

interface ReviewsState {
	reviews: Review[];
	currentReview: ReviewDetail | null;
	loading: boolean;
	error: string | null;
}

function createReviewsStore() {
	const { subscribe, set, update } = writable<ReviewsState>({
		reviews: [],
		currentReview: null,
		loading: false,
		error: null
	});

	return {
		subscribe,

		fetchRecent: async () => {
			update((state) => ({ ...state, loading: true, error: null }));
			try {
				const reviews = await reviewsApi.getRecent();
				update((state) => ({ ...state, reviews, loading: false }));
			} catch (e) {
				update((state) => ({
					...state,
					error: e instanceof Error ? e.message : 'Failed to fetch reviews',
					loading: false
				}));
			}
		},

		fetchReview: async (id: string) => {
			update((state) => ({ ...state, loading: true, error: null }));
			try {
				const review = await reviewsApi.get(id);
				update((state) => ({ ...state, currentReview: review, loading: false }));
			} catch (e) {
				update((state) => ({
					...state,
					error: e instanceof Error ? e.message : 'Failed to fetch review',
					loading: false
				}));
			}
		},

		submitReview: async (prUrl: string) => {
			update((state) => ({ ...state, loading: true, error: null }));
			try {
				const review = await reviewsApi.submit(prUrl);
				update((state) => ({
					...state,
					reviews: [review, ...state.reviews],
					loading: false
				}));
				return review;
			} catch (e) {
				update((state) => ({
					...state,
					error: e instanceof Error ? e.message : 'Failed to submit review',
					loading: false
				}));
				throw e;
			}
		},

		clearCurrent: () => {
			update((state) => ({ ...state, currentReview: null }));
		},

		clearError: () => {
			update((state) => ({ ...state, error: null }));
		}
	};
}

export const reviewsStore = createReviewsStore();

export const recentReviews = derived(reviewsStore, ($store) => $store.reviews);
export const currentReview = derived(reviewsStore, ($store) => $store.currentReview);
export const isLoading = derived(reviewsStore, ($store) => $store.loading);
export const reviewError = derived(reviewsStore, ($store) => $store.error);
