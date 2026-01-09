<script lang="ts">
	import { goto } from '$app/navigation';
	import { reviews } from '$lib/api/client';

	let url = '';
	let includeOptimization = false;
	let ticketContent = '';
	let ticketId = '';
	let loading = false;
	let error: string | null = null;

	// Detect URL type
	function getUrlType(url: string): 'pr' | 'commit' | 'invalid' {
		const trimmed = url.trim();
		if (!trimmed) return 'invalid';

		// Check for commit URLs
		// GitHub: https://github.com/owner/repo/commit/sha
		// GitLab: https://gitlab.com/owner/repo/-/commit/sha
		if (trimmed.includes('/commit/')) {
			if (trimmed.includes('github.com') || trimmed.includes('gitlab.com') || trimmed.includes('gitlab')) {
				return 'commit';
			}
		}

		// Check for PR/MR URLs
		// GitHub: https://github.com/owner/repo/pull/123
		// GitLab: https://gitlab.com/owner/repo/-/merge_requests/123
		if (trimmed.includes('/pull/') || trimmed.includes('/merge_requests/')) {
			if (trimmed.includes('github.com') || trimmed.includes('gitlab.com') || trimmed.includes('gitlab')) {
				return 'pr';
			}
		}

		return 'invalid';
	}

	$: urlType = getUrlType(url);

	async function handleSubmit() {
		if (!url.trim()) {
			error = 'Please enter a URL';
			return;
		}

		if (urlType === 'invalid') {
			error = 'Please enter a valid GitHub or GitLab PR or commit URL';
			return;
		}

		loading = true;
		error = null;

		try {
			let review;
			const options = {
				includeOptimization,
				ticketContent: ticketContent.trim() || undefined,
				ticketId: ticketId.trim() || undefined
			};
			if (urlType === 'commit') {
				review = await reviews.submitCommit(url, options);
			} else {
				review = await reviews.submit(url, options);
			}
			goto(`/reviews/${review.id}`);
		} catch (e) {
			error = e instanceof Error ? e.message : 'Failed to submit review';
			loading = false;
		}
	}
</script>

<svelte:head>
	<title>New Review - CodeLens</title>
</svelte:head>

<div class="p-8 max-w-2xl mx-auto">
	<div class="mb-8">
		<h1 class="text-2xl font-bold text-gray-900">Submit New Review</h1>
		<p class="text-gray-600">Enter a GitHub or GitLab pull request or commit URL for AI review</p>
	</div>

	<div class="card p-8">
		<form on:submit|preventDefault={handleSubmit}>
			<div class="mb-6">
				<label for="url" class="block text-sm font-medium text-gray-700 mb-2">
					PR or Commit URL
				</label>
				<input
					type="url"
					id="url"
					bind:value={url}
					placeholder="https://github.com/owner/repo/pull/123"
					class="input"
					disabled={loading}
				/>
				{#if urlType === 'commit'}
					<p class="mt-2 text-sm text-green-600 flex items-center gap-1">
						<svg xmlns="http://www.w3.org/2000/svg" class="h-4 w-4" fill="none" viewBox="0 0 24 24" stroke="currentColor">
							<path stroke-linecap="round" stroke-linejoin="round" stroke-width="2" d="M5 13l4 4L19 7" />
						</svg>
						Single commit URL detected
					</p>
				{:else if urlType === 'pr'}
					<p class="mt-2 text-sm text-green-600 flex items-center gap-1">
						<svg xmlns="http://www.w3.org/2000/svg" class="h-4 w-4" fill="none" viewBox="0 0 24 24" stroke="currentColor">
							<path stroke-linecap="round" stroke-linejoin="round" stroke-width="2" d="M5 13l4 4L19 7" />
						</svg>
						Pull request URL detected
					</p>
				{:else}
					<p class="mt-2 text-sm text-gray-500">
						Supported formats:
					</p>
					<ul class="text-sm text-gray-500 list-disc list-inside">
						<li>GitHub PR: https://github.com/owner/repo/pull/123</li>
						<li>GitHub Commit: https://github.com/owner/repo/commit/abc123</li>
						<li>GitLab MR: https://gitlab.com/owner/repo/-/merge_requests/123</li>
						<li>GitLab Commit: https://gitlab.com/owner/repo/-/commit/abc123</li>
					</ul>
				{/if}
			</div>

			<!-- Ticket Scope Validation -->
			<div class="mb-6">
				<label for="ticketContent" class="block text-sm font-medium text-gray-700 mb-2">
					<svg xmlns="http://www.w3.org/2000/svg" class="h-4 w-4 inline mr-1 text-gray-400" fill="none" viewBox="0 0 24 24" stroke="currentColor">
						<path stroke-linecap="round" stroke-linejoin="round" stroke-width="2" d="M9 5H7a2 2 0 00-2 2v12a2 2 0 002 2h10a2 2 0 002-2V7a2 2 0 00-2-2h-2M9 5a2 2 0 002 2h2a2 2 0 002-2M9 5a2 2 0 012-2h2a2 2 0 012 2" />
					</svg>
					Ticket/Story Content (optional)
				</label>
				<textarea
					id="ticketContent"
					bind:value={ticketContent}
					placeholder="Paste your Jira ticket, GitHub issue, or story description here to validate PR scope alignment..."
					class="input min-h-[100px] resize-y"
					disabled={loading}
					rows="4"
				></textarea>
				<p class="mt-1 text-xs text-gray-500">
					If provided, CodeLens will verify that the PR changes align with the ticket scope.
				</p>
			</div>

			<div class="mb-6">
				<label for="ticketId" class="block text-sm font-medium text-gray-700 mb-2">
					Ticket ID (optional)
				</label>
				<input
					type="text"
					id="ticketId"
					bind:value={ticketId}
					placeholder="e.g., JIRA-123, #456"
					class="input"
					disabled={loading}
				/>
			</div>

			<!-- Optimization Analysis Option -->
			<div class="mb-6">
				<label class="flex items-start gap-3 p-4 bg-indigo-50 border border-indigo-200 rounded-lg cursor-pointer hover:bg-indigo-100 transition-colors">
					<input
						type="checkbox"
						bind:checked={includeOptimization}
						class="w-5 h-5 text-indigo-600 rounded focus:ring-indigo-500 mt-0.5"
						disabled={loading}
					/>
					<div>
						<span class="font-medium text-indigo-900">Include Optimization Analysis</span>
						<p class="text-sm text-indigo-700 mt-1">
							Analyze code for performance improvements: algorithm efficiency, database query patterns, memory usage, caching opportunities, and refactoring suggestions.
						</p>
					</div>
				</label>
			</div>

			{#if error}
				<div class="mb-6 p-4 bg-red-50 border border-red-200 rounded-lg text-red-700 text-sm">
					{error}
				</div>
			{/if}

			<button
				type="submit"
				class="btn btn-primary w-full flex items-center justify-center gap-2"
				disabled={loading}
			>
				{#if loading}
					<div class="animate-spin rounded-full h-4 w-4 border-b-2 border-white"></div>
					Submitting...
				{:else}
					<svg xmlns="http://www.w3.org/2000/svg" class="h-5 w-5" fill="none" viewBox="0 0 24 24" stroke="currentColor">
						<path stroke-linecap="round" stroke-linejoin="round" stroke-width="2" d="M9 5H7a2 2 0 00-2 2v12a2 2 0 002 2h10a2 2 0 002-2V7a2 2 0 00-2-2h-2M9 5a2 2 0 002 2h2a2 2 0 002-2M9 5a2 2 0 012-2h2a2 2 0 012 2m-6 9l2 2 4-4" />
					</svg>
					Start Review
				{/if}
			</button>
		</form>
	</div>

	<!-- What to expect -->
	<div class="mt-8 card p-6">
		<h2 class="text-lg font-semibold mb-4">What happens next?</h2>
		<div class="space-y-4">
			<div class="flex gap-4">
				<div class="w-8 h-8 bg-primary-100 rounded-full flex items-center justify-center text-primary-700 font-bold shrink-0">
					1
				</div>
				<div>
					<h3 class="font-medium">Fetch PR Data</h3>
					<p class="text-sm text-gray-500">We'll fetch the diff and changed files from your PR</p>
				</div>
			</div>
			<div class="flex gap-4">
				<div class="w-8 h-8 bg-primary-100 rounded-full flex items-center justify-center text-primary-700 font-bold shrink-0">
					2
				</div>
				<div>
					<h3 class="font-medium">AI Analysis</h3>
					<p class="text-sm text-gray-500">Our AI will review the code for bugs, security issues, and best practices</p>
				</div>
			</div>
			<div class="flex gap-4">
				<div class="w-8 h-8 bg-primary-100 rounded-full flex items-center justify-center text-primary-700 font-bold shrink-0">
					3
				</div>
				<div>
					<h3 class="font-medium">Static Analysis</h3>
					<p class="text-sm text-gray-500">Run PMD, Checkstyle, ESLint, and CVE detection on your code</p>
				</div>
			</div>
			<div class="flex gap-4">
				<div class="w-8 h-8 bg-primary-100 rounded-full flex items-center justify-center text-primary-700 font-bold shrink-0">
					4
				</div>
				<div>
					<h3 class="font-medium">Review Results</h3>
					<p class="text-sm text-gray-500">Get a comprehensive report with inline comments on your PR</p>
				</div>
			</div>
		</div>
	</div>
</div>
