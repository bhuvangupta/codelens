<script lang="ts">
	import { onMount } from 'svelte';
	import { settings } from '$lib/api/client';
	import type { RepositorySettings } from '$lib/api/client';

	let repositories: RepositorySettings[] = $state([]);
	let loading = $state(true);
	let error: string | null = $state(null);
	let updating: string | null = $state(null);

	onMount(async () => {
		await loadRepositories();
	});

	async function loadRepositories() {
		try {
			repositories = await settings.getRepositories();
		} catch (e) {
			error = e instanceof Error ? e.message : 'Failed to load repositories';
		} finally {
			loading = false;
		}
	}

	async function toggleAutoReview(repo: RepositorySettings) {
		updating = repo.id;
		try {
			const updated = await settings.updateRepository(repo.id, {
				autoReviewEnabled: !repo.autoReviewEnabled
			});
			// Update local state
			repositories = repositories.map(r =>
				r.id === repo.id ? updated : r
			);
		} catch (e) {
			error = e instanceof Error ? e.message : 'Failed to update repository';
		} finally {
			updating = null;
		}
	}

	function getProviderIcon(provider: string): string {
		switch (provider) {
			case 'GITHUB': return 'github';
			case 'GITLAB': return 'gitlab';
			default: return 'git';
		}
	}

	function formatDate(dateStr: string): string {
		return new Date(dateStr).toLocaleDateString('en-US', {
			month: 'short',
			day: 'numeric',
			year: 'numeric'
		});
	}
</script>

<svelte:head>
	<title>Repositories - CodeLens</title>
</svelte:head>

{#if loading}
	<div class="flex items-center justify-center h-64">
		<div class="animate-spin rounded-full h-8 w-8 border-b-2 border-primary-700"></div>
	</div>
{:else if error && repositories.length === 0}
	<div class="card p-6 text-center text-red-600">
		{error}
	</div>
{:else}
	<div class="card p-6 mb-6">
		<div class="flex items-center justify-between mb-4">
			<div>
				<h2 class="text-lg font-semibold">Connected Repositories</h2>
				<p class="text-sm text-gray-600">
					Manage auto-review settings for your repositories
				</p>
			</div>
			<span class="badge bg-gray-100 text-gray-700">
				{repositories.length} {repositories.length === 1 ? 'repository' : 'repositories'}
			</span>
		</div>

		{#if error}
			<div class="mb-4 p-3 bg-red-50 border border-red-200 rounded-lg text-red-700 text-sm">
				{error}
				<button onclick={() => error = null} class="ml-2 underline">Dismiss</button>
			</div>
		{/if}

		{#if repositories.length === 0}
			<div class="text-center py-12">
				<svg class="w-16 h-16 mx-auto text-gray-300 mb-4" fill="none" stroke="currentColor" viewBox="0 0 24 24">
					<path stroke-linecap="round" stroke-linejoin="round" stroke-width="1.5" d="M3 7v10a2 2 0 002 2h14a2 2 0 002-2V9a2 2 0 00-2-2h-6l-2-2H5a2 2 0 00-2 2z" />
				</svg>
				<h3 class="text-lg font-medium text-gray-900 mb-2">No repositories yet</h3>
				<p class="text-gray-500 mb-4">
					Repositories are added automatically when you submit PRs for review.
				</p>
				<a href="/reviews/new" class="btn btn-primary">
					Submit your first PR
				</a>
			</div>
		{:else}
			<div class="space-y-3">
				{#each repositories as repo}
					<div class="flex items-center justify-between p-4 bg-gray-50 rounded-lg border border-gray-100">
						<div class="flex items-center gap-4">
							<!-- Provider Icon -->
							<div class="w-10 h-10 rounded-lg bg-white border border-gray-200 flex items-center justify-center">
								{#if repo.provider === 'GITHUB'}
									<svg class="w-6 h-6" viewBox="0 0 24 24" fill="currentColor">
										<path d="M12 0c-6.626 0-12 5.373-12 12 0 5.302 3.438 9.8 8.207 11.387.599.111.793-.261.793-.577v-2.234c-3.338.726-4.033-1.416-4.033-1.416-.546-1.387-1.333-1.756-1.333-1.756-1.089-.745.083-.729.083-.729 1.205.084 1.839 1.237 1.839 1.237 1.07 1.834 2.807 1.304 3.492.997.107-.775.418-1.305.762-1.604-2.665-.305-5.467-1.334-5.467-5.931 0-1.311.469-2.381 1.236-3.221-.124-.303-.535-1.524.117-3.176 0 0 1.008-.322 3.301 1.23.957-.266 1.983-.399 3.003-.404 1.02.005 2.047.138 3.006.404 2.291-1.552 3.297-1.23 3.297-1.23.653 1.653.242 2.874.118 3.176.77.84 1.235 1.911 1.235 3.221 0 4.609-2.807 5.624-5.479 5.921.43.372.823 1.102.823 2.222v3.293c0 .319.192.694.801.576 4.765-1.589 8.199-6.086 8.199-11.386 0-6.627-5.373-12-12-12z"/>
									</svg>
								{:else if repo.provider === 'GITLAB'}
									<svg class="w-6 h-6" viewBox="0 0 24 24" fill="#FC6D26">
										<path d="M22.65 14.39L12 22.13 1.35 14.39a.84.84 0 0 1-.3-.94l1.22-3.78 2.44-7.51A.42.42 0 0 1 4.82 2a.43.43 0 0 1 .58 0 .42.42 0 0 1 .11.18l2.44 7.49h8.1l2.44-7.51A.42.42 0 0 1 18.6 2a.43.43 0 0 1 .58 0 .42.42 0 0 1 .11.18l2.44 7.51L23 13.45a.84.84 0 0 1-.35.94z"/>
									</svg>
								{:else}
									<svg class="w-6 h-6 text-gray-400" fill="none" stroke="currentColor" viewBox="0 0 24 24">
										<path stroke-linecap="round" stroke-linejoin="round" stroke-width="2" d="M3 7v10a2 2 0 002 2h14a2 2 0 002-2V9a2 2 0 00-2-2h-6l-2-2H5a2 2 0 00-2 2z" />
									</svg>
								{/if}
							</div>

							<!-- Repo Info -->
							<div>
								<div class="flex items-center gap-2">
									<p class="font-medium text-gray-900">{repo.fullName}</p>
									{#if repo.isPrivate}
										<span class="badge bg-gray-100 text-gray-600 text-xs">Private</span>
									{/if}
									{#if repo.language}
										<span class="badge bg-blue-50 text-blue-700 text-xs">{repo.language}</span>
									{/if}
								</div>
								{#if repo.description}
									<p class="text-sm text-gray-500 truncate max-w-md">{repo.description}</p>
								{:else}
									<p class="text-sm text-gray-400">Added {formatDate(repo.createdAt)}</p>
								{/if}
							</div>
						</div>

						<!-- Auto Review Toggle -->
						<div class="flex items-center gap-4">
							<div class="text-right">
								<p class="text-sm font-medium text-gray-700">Auto Review</p>
								<p class="text-xs text-gray-500">
									{repo.autoReviewEnabled ? 'Enabled' : 'Disabled'}
								</p>
							</div>
							<button
								onclick={() => toggleAutoReview(repo)}
								disabled={updating === repo.id}
								class="relative inline-flex h-6 w-11 shrink-0 cursor-pointer rounded-full border-2 border-transparent transition-colors duration-200 ease-in-out focus:outline-none focus:ring-2 focus:ring-primary-500 focus:ring-offset-2 {repo.autoReviewEnabled ? 'bg-primary-600' : 'bg-gray-200'}"
								role="switch"
								aria-checked={repo.autoReviewEnabled}
							>
								{#if updating === repo.id}
									<span class="absolute inset-0 flex items-center justify-center">
										<div class="animate-spin rounded-full h-4 w-4 border-b-2 border-white"></div>
									</span>
								{:else}
									<span
										class="pointer-events-none inline-block h-5 w-5 transform rounded-full bg-white shadow ring-0 transition duration-200 ease-in-out {repo.autoReviewEnabled ? 'translate-x-5' : 'translate-x-0'}"
									></span>
								{/if}
							</button>
						</div>
					</div>
				{/each}
			</div>
		{/if}
	</div>

	<!-- Info Box -->
	<div class="p-4 bg-blue-50 border border-blue-200 rounded-lg">
		<div class="flex gap-3">
			<svg class="w-5 h-5 text-blue-600 shrink-0 mt-0.5" fill="none" stroke="currentColor" viewBox="0 0 24 24">
				<path stroke-linecap="round" stroke-linejoin="round" stroke-width="2" d="M13 16h-1v-4h-1m1-4h.01M21 12a9 9 0 11-18 0 9 9 0 0118 0z" />
			</svg>
			<div class="text-sm text-blue-800">
				<p class="font-medium mb-1">About Auto Review</p>
				<p>When enabled, new pull requests in this repository will automatically be reviewed when opened or updated. You can also trigger reviews manually from the Reviews page.</p>
			</div>
		</div>
	</div>
{/if}
