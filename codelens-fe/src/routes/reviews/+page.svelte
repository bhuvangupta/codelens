<script lang="ts">
	import { onMount, onDestroy } from 'svelte';
	import { reviews } from '$lib/api/client';
	import type { Review } from '$lib/api/client';

	let reviewsList: Review[] = [];
	let loading = true;
	let error: string | null = null;
	let statusFilter = 'all';
	let pollInterval: ReturnType<typeof setInterval> | null = null;

	// View mode: 'my' (default) or 'all'
	let viewMode: 'my' | 'all' = 'my';

	// Repository filter
	let repositories: string[] = [];
	let selectedRepo = '';

	async function loadReviews() {
		try {
			const repo = selectedRepo || undefined;
			if (viewMode === 'my') {
				reviewsList = await reviews.getMy(repo);
			} else {
				reviewsList = await reviews.getRecent(50, repo);
			}
		} catch (e) {
			console.error('Failed to refresh reviews:', e);
		}
	}

	async function loadRepositories() {
		try {
			repositories = await reviews.getRepositories(viewMode === 'all');
		} catch (e) {
			console.error('Failed to load repositories:', e);
		}
	}

	function startPolling() {
		if (pollInterval) return;
		pollInterval = setInterval(loadReviews, 5000);
	}

	function stopPolling() {
		if (pollInterval) {
			clearInterval(pollInterval);
			pollInterval = null;
		}
	}

	// Start/stop polling based on in-progress reviews
	$: hasInProgress = reviewsList.some(r => r.status === 'IN_PROGRESS' || r.status === 'PENDING');
	$: if (hasInProgress) {
		startPolling();
	} else {
		stopPolling();
	}

	async function handleViewModeChange(mode: 'my' | 'all') {
		viewMode = mode;
		selectedRepo = '';
		loading = true;
		await Promise.all([loadReviews(), loadRepositories()]);
		loading = false;
	}

	async function handleRepoChange() {
		loading = true;
		await loadReviews();
		loading = false;
	}

	onMount(async () => {
		try {
			await Promise.all([loadReviews(), loadRepositories()]);
		} catch (e) {
			error = e instanceof Error ? e.message : 'Failed to load reviews';
		} finally {
			loading = false;
		}
	});

	onDestroy(() => {
		stopPolling();
	});

	$: filteredReviews = statusFilter === 'all'
		? reviewsList
		: reviewsList.filter(r => r.status === statusFilter);

	function formatDate(dateStr: string): string {
		return new Date(dateStr).toLocaleDateString('en-US', {
			month: 'short',
			day: 'numeric',
			year: 'numeric'
		});
	}

	function getStatusColor(status: string): string {
		switch (status) {
			case 'COMPLETED': return 'bg-green-100 text-green-800';
			case 'IN_PROGRESS': return 'bg-blue-100 text-blue-800';
			case 'PENDING': return 'bg-yellow-100 text-yellow-800';
			case 'FAILED': return 'bg-red-100 text-red-800';
			default: return 'bg-gray-100 text-gray-800';
		}
	}

	function getSeverityBadges(review: Review) {
		const badges = [];
		if (review.criticalIssues > 0) badges.push({ label: `${review.criticalIssues} Critical`, class: 'badge-critical' });
		if (review.highIssues > 0) badges.push({ label: `${review.highIssues} High`, class: 'badge-high' });
		if (review.mediumIssues > 0) badges.push({ label: `${review.mediumIssues} Medium`, class: 'badge-medium' });
		if (review.lowIssues > 0) badges.push({ label: `${review.lowIssues} Low`, class: 'badge-low' });
		return badges;
	}
</script>

<svelte:head>
	<title>Reviews - CodeLens</title>
</svelte:head>

<div class="p-8">
	<div class="flex items-center justify-between mb-8">
		<div>
			<h1 class="text-2xl font-bold text-gray-900">Reviews</h1>
			<p class="text-gray-600">{viewMode === 'my' ? 'Your code reviews' : 'All code reviews'}</p>
		</div>
		<a href="/reviews/new" class="btn btn-primary flex items-center gap-2">
			<svg xmlns="http://www.w3.org/2000/svg" class="h-5 w-5" fill="none" viewBox="0 0 24 24" stroke="currentColor">
				<path stroke-linecap="round" stroke-linejoin="round" stroke-width="2" d="M12 4v16m8-8H4" />
			</svg>
			New Review
		</a>
	</div>

	<!-- View Mode Toggle & Filters -->
	<div class="flex flex-wrap items-center gap-4 mb-6">
		<!-- My / All Toggle -->
		<div class="flex rounded-lg border border-gray-200 overflow-hidden">
			<button
				class="px-4 py-2 text-sm font-medium transition-colors"
				class:bg-primary-700={viewMode === 'my'}
				class:text-white={viewMode === 'my'}
				class:bg-white={viewMode !== 'my'}
				class:text-gray-700={viewMode !== 'my'}
				on:click={() => handleViewModeChange('my')}
			>
				My Reviews
			</button>
			<button
				class="px-4 py-2 text-sm font-medium transition-colors border-l border-gray-200"
				class:bg-primary-700={viewMode === 'all'}
				class:text-white={viewMode === 'all'}
				class:bg-white={viewMode !== 'all'}
				class:text-gray-700={viewMode !== 'all'}
				on:click={() => handleViewModeChange('all')}
			>
				All Reviews
			</button>
		</div>

		<!-- Repository Filter -->
		{#if repositories.length > 0}
			<select
				bind:value={selectedRepo}
				on:change={handleRepoChange}
				class="px-4 py-2 rounded-lg border border-gray-200 text-sm font-medium bg-white text-gray-700 focus:ring-2 focus:ring-primary-500 focus:border-primary-500 outline-none"
			>
				<option value="">All Repositories</option>
				{#each repositories as repo}
					<option value={repo}>{repo}</option>
				{/each}
			</select>
		{/if}

		<div class="flex-1"></div>

		<!-- Status Filters -->
		<div class="flex gap-2">
			<button
				class="px-4 py-2 rounded-lg text-sm font-medium transition-colors"
				class:bg-primary-700={statusFilter === 'all'}
				class:text-white={statusFilter === 'all'}
				class:bg-gray-100={statusFilter !== 'all'}
				class:text-gray-700={statusFilter !== 'all'}
				on:click={() => statusFilter = 'all'}
			>
				All
			</button>
			<button
				class="px-4 py-2 rounded-lg text-sm font-medium transition-colors"
				class:bg-primary-700={statusFilter === 'COMPLETED'}
				class:text-white={statusFilter === 'COMPLETED'}
				class:bg-gray-100={statusFilter !== 'COMPLETED'}
				class:text-gray-700={statusFilter !== 'COMPLETED'}
				on:click={() => statusFilter = 'COMPLETED'}
			>
				Completed
			</button>
			<button
				class="px-4 py-2 rounded-lg text-sm font-medium transition-colors"
				class:bg-primary-700={statusFilter === 'IN_PROGRESS'}
				class:text-white={statusFilter === 'IN_PROGRESS'}
				class:bg-gray-100={statusFilter !== 'IN_PROGRESS'}
				class:text-gray-700={statusFilter !== 'IN_PROGRESS'}
				on:click={() => statusFilter = 'IN_PROGRESS'}
			>
				In Progress
			</button>
			<button
				class="px-4 py-2 rounded-lg text-sm font-medium transition-colors"
				class:bg-primary-700={statusFilter === 'PENDING'}
				class:text-white={statusFilter === 'PENDING'}
				class:bg-gray-100={statusFilter !== 'PENDING'}
				class:text-gray-700={statusFilter !== 'PENDING'}
				on:click={() => statusFilter = 'PENDING'}
			>
				Pending
			</button>
		</div>
	</div>

	{#if loading}
		<div class="flex items-center justify-center h-64">
			<div class="animate-spin rounded-full h-8 w-8 border-b-2 border-primary-700"></div>
		</div>
	{:else if error}
		<div class="card p-6 text-center text-red-600">
			{error}
		</div>
	{:else}
		<div class="card divide-y divide-gray-100">
			{#each filteredReviews as review}
				<a href="/reviews/{review.id}" class="block p-6 hover:bg-gray-50 transition-colors">
					<div class="flex items-start justify-between gap-4">
						<div class="flex-1 min-w-0">
							<div class="flex items-center gap-2 mb-1">
								<h3 class="font-medium text-gray-900 truncate">
									{review.prTitle || `PR #${review.prNumber}`}
								</h3>
								<span class="badge {getStatusColor(review.status)}">{review.status}</span>
								{#if review.llmProvider}
									<span class="badge bg-purple-100 text-purple-800 text-xs capitalize">{review.llmProvider}</span>
								{/if}
							</div>
							<p class="text-sm text-gray-500 truncate mb-2">{review.prUrl}</p>
							{#if review.status === 'COMPLETED'}
								<div class="flex flex-wrap gap-2">
									{#each getSeverityBadges(review) as badge}
										<span class="badge {badge.class}">{badge.label}</span>
									{/each}
								</div>
							{/if}
						</div>
						<div class="text-right shrink-0">
							{#if review.prAuthor}
								<p class="text-sm font-medium text-gray-700">@{review.prAuthor}</p>
							{/if}
							<p class="text-sm text-gray-500">{formatDate(review.createdAt)}</p>
							{#if review.filesReviewed > 0}
								<p class="text-xs text-gray-400">{review.filesReviewed} files reviewed</p>
							{/if}
							<p class="text-xs font-mono text-gray-300 mt-1">{review.id.substring(0, 8)}</p>
						</div>
					</div>
				</a>
			{:else}
				<div class="p-12 text-center">
					{#if reviewsList.length === 0}
						<p class="text-gray-500 mb-4">{viewMode === 'my' ? "You haven't submitted any reviews yet" : "No reviews found"}</p>
						<a href="/reviews/new" class="btn btn-primary">Submit your first PR</a>
					{:else}
						<p class="text-gray-500">No {statusFilter === 'IN_PROGRESS' ? 'in-progress' : statusFilter === 'PENDING' ? 'pending' : statusFilter.toLowerCase()} reviews</p>
					{/if}
				</div>
			{/each}
		</div>
	{/if}
</div>
