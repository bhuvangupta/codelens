<script lang="ts">
	import { onMount, onDestroy } from 'svelte';
	import { page } from '$app/stores';
	import { reviews } from '$lib/api/client';
	import type { ReviewDetail, ReviewIssue, ReviewStatus, OptimizationStatus, DiffResponse, FileDiff } from '$lib/api/client';
	import { marked } from 'marked';
	import DiffViewer from '$lib/components/DiffViewer.svelte';

	let review: ReviewDetail | null = null;
	let loading = true;
	let error: string | null = null;

	// Progress tracking
	let progress: ReviewStatus | null = null;
	let pollInterval: ReturnType<typeof setInterval> | null = null;

	// Tab state for issue categories
	let activeIssueTab: 'all' | 'security' | 'bugs' | 'quality' | 'optimizations' = 'all';

	// Optimization state
	let optimizationStatus: OptimizationStatus | null = null;
	let optimizationLoading = false;
	let optimizationPollInterval: ReturnType<typeof setInterval> | null = null;

	// Cancellation state
	let cancelling = false;
	let showCancelConfirm = false;

	// Main view tab state
	let activeMainTab: 'issues' | 'diff' = 'issues';

	// Diff state
	let diffData: DiffResponse | null = null;
	let diffLoading = false;
	let diffError: string | null = null;

	// Configure marked for safe rendering
	marked.setOptions({
		breaks: true,
		gfm: true
	});

	function renderMarkdown(text: string): string {
		if (!text) return '';
		return marked.parse(text) as string;
	}

	/**
	 * Format suggestion text - detects code and renders it properly
	 */
	function formatSuggestion(text: string): { isCode: boolean; formatted: string } {
		if (!text) return { isCode: false, formatted: '' };

		// Check if the suggestion looks like code
		const codeIndicators = [
			/^\s*(if|for|while|switch|try|catch|return|throw|class|function|const|let|var|public|private|protected)\s*[\(\{]/m,
			/\.\w+\([^)]*\)/,  // method calls like .getStatus()
			/[{};]\s*$/m,      // ends with braces or semicolons
			/^\s*\/\//m,       // comments
			/=>/,              // arrow functions
			/\s+==\s+|\s+!=\s+|\s+===\s+/,  // comparisons
			/\w+\s*\.\s*\w+\s*\.\s*\w+/,    // chained method calls
		];

		const looksLikeCode = codeIndicators.some(pattern => pattern.test(text));

		if (looksLikeCode) {
			// Try to format the code with proper indentation
			let formatted = text
				// Add newlines after common statement terminators
				.replace(/;\s*(?=[A-Za-z])/g, ';\n')
				.replace(/\{\s*(?=[A-Za-z])/g, '{\n  ')
				.replace(/\}\s*(?=[A-Za-z])/g, '}\n')
				// Clean up excessive whitespace
				.replace(/\n\s*\n/g, '\n')
				.trim();

			return { isCode: true, formatted };
		}

		return { isCode: false, formatted: text };
	}

	$: reviewId = $page.params.id;

	// Check if review is still in progress
	$: isInProgress = review?.status === 'PENDING' || review?.status === 'IN_PROGRESS';

	onMount(async () => {
		await loadReview();
	});

	onDestroy(() => {
		stopPolling();
		stopOptimizationPolling();
	});

	async function loadReview() {
		try {
			if (!reviewId) {
				error = 'Review ID is required';
				return;
			}
			review = await reviews.get(reviewId);

			// Start polling if review is in progress
			if (review.status === 'PENDING' || review.status === 'IN_PROGRESS') {
				startPolling();
			} else if (review.status === 'COMPLETED') {
				// Load optimization status if review is completed
				await loadOptimizations();
			}
		} catch (e) {
			error = e instanceof Error ? e.message : 'Failed to load review';
		} finally {
			loading = false;
		}
	}

	function startPolling() {
		if (pollInterval) return;

		// Poll every 2 seconds
		pollInterval = setInterval(async () => {
			if (!reviewId) return;
			try {
				progress = await reviews.getStatus(reviewId);

				// If completed, failed, or cancelled - reload full review and stop polling
				if (progress.status === 'COMPLETED' || progress.status === 'FAILED' || progress.status === 'CANCELLED') {
					stopPolling();
					review = await reviews.get(reviewId);
				}
			} catch (e) {
				console.error('Failed to fetch progress:', e);
			}
		}, 2000);
	}

	async function cancelReview() {
		if (!reviewId || cancelling) return;

		cancelling = true;
		showCancelConfirm = false;
		try {
			await reviews.cancel(reviewId);
			// Reload the review to get updated status
			review = await reviews.get(reviewId);
			stopPolling();
		} catch (e) {
			console.error('Failed to cancel review:', e);
			error = e instanceof Error ? e.message : 'Failed to cancel review';
		} finally {
			cancelling = false;
		}
	}

	function stopPolling() {
		if (pollInterval) {
			clearInterval(pollInterval);
			pollInterval = null;
		}
	}

	async function runOptimizationAnalysis() {
		if (!reviewId || optimizationLoading) return;

		optimizationLoading = true;
		try {
			const result = await reviews.optimize(reviewId);
			if (result.status === 'completed') {
				optimizationStatus = {
					completed: true,
					inProgress: false,
					summary: result.summary || '',
					optimizations: result.optimizations || []
				};
			} else {
				// Started - begin polling
				startOptimizationPolling();
			}
		} catch (e) {
			console.error('Failed to run optimization:', e);
			optimizationLoading = false;
		}
	}

	function startOptimizationPolling() {
		if (optimizationPollInterval) return;

		optimizationPollInterval = setInterval(async () => {
			if (!reviewId) return;
			try {
				const status = await reviews.getOptimizations(reviewId);
				optimizationStatus = status; // Always update to show progress

				if (status.completed && !status.inProgress) {
					stopOptimizationPolling();
					optimizationLoading = false;
				}
			} catch (e) {
				console.error('Failed to fetch optimization status:', e);
			}
		}, 2000); // Poll every 2 seconds for smoother progress updates
	}

	function stopOptimizationPolling() {
		if (optimizationPollInterval) {
			clearInterval(optimizationPollInterval);
			optimizationPollInterval = null;
		}
	}

	async function loadOptimizations() {
		if (!reviewId) return;
		try {
			optimizationStatus = await reviews.getOptimizations(reviewId);
			// Start polling if optimization is in progress
			if (optimizationStatus.inProgress) {
				optimizationLoading = true;
				startOptimizationPolling();
			}
		} catch (e) {
			console.error('Failed to load optimizations:', e);
		}
	}

	async function loadDiff() {
		if (!reviewId || diffData) return; // Don't reload if already loaded
		diffLoading = true;
		diffError = null;
		try {
			diffData = await reviews.getDiff(reviewId);
		} catch (e) {
			diffError = e instanceof Error ? e.message : 'Failed to load diff';
			console.error('Failed to load diff:', e);
		} finally {
			diffLoading = false;
		}
	}

	function handleMainTabChange(tab: 'issues' | 'diff') {
		activeMainTab = tab;
		if (tab === 'diff' && !diffData && !diffLoading) {
			loadDiff();
		}
	}

	function formatElapsed(ms: number): string {
		const seconds = Math.floor(ms / 1000);
		if (seconds < 60) return `${seconds}s`;
		const minutes = Math.floor(seconds / 60);
		const remainingSeconds = seconds % 60;
		return `${minutes}m ${remainingSeconds}s`;
	}

	function formatDate(dateStr: string): string {
		return new Date(dateStr).toLocaleDateString('en-US', {
			month: 'long',
			day: 'numeric',
			year: 'numeric',
			hour: '2-digit',
			minute: '2-digit'
		});
	}

	function getStatusBadge(status: string): { class: string; dot: string } {
		switch (status) {
			case 'COMPLETED': return { class: 'bg-stone-700 text-white', dot: 'bg-white' };
			case 'IN_PROGRESS': return { class: 'bg-blue-600 text-white', dot: 'bg-white' };
			case 'PENDING': return { class: 'bg-amber-500 text-white', dot: 'bg-white' };
			case 'FAILED': return { class: 'bg-red-600 text-white', dot: 'bg-white' };
			case 'CANCELLED': return { class: 'bg-gray-500 text-white', dot: 'bg-white' };
			default: return { class: 'bg-gray-500 text-white', dot: 'bg-white' };
		}
	}

	function getSeverityStyle(severity: string): { bg: string; text: string; iconBg: string } {
		switch (severity) {
			case 'CRITICAL': return { bg: 'bg-stone-700', text: 'text-white', iconBg: 'from-stone-600 to-stone-700' };
			case 'HIGH': return { bg: 'bg-stone-600', text: 'text-white', iconBg: 'from-stone-500 to-stone-600' };
			case 'MEDIUM': return { bg: 'bg-stone-400', text: 'text-white', iconBg: 'from-stone-400 to-stone-500' };
			case 'LOW': return { bg: 'bg-stone-200', text: 'text-stone-700', iconBg: 'from-stone-300 to-stone-400' };
			default: return { bg: 'bg-gray-200', text: 'text-gray-700', iconBg: 'from-gray-400 to-gray-500' };
		}
	}

	function getSeverityIcon(severity: string): string {
		switch (severity) {
			case 'CRITICAL': return 'fas fa-exclamation-triangle';
			case 'HIGH': return 'fas fa-shield-alt';
			case 'MEDIUM': return 'fas fa-bug';
			case 'LOW': return 'fas fa-info-circle';
			default: return 'fas fa-info-circle';
		}
	}

	function getCategoryIcon(category: string): string {
		const cat = category?.toLowerCase() || '';
		if (cat === 'security' || cat === 'cve' || cat.includes('xss') || cat.includes('sql') || cat.includes('csrf')) return 'fas fa-shield-alt';
		if (cat.includes('bug') || cat.includes('error')) return 'fas fa-bug';
		if (cat.includes('performance')) return 'fas fa-tachometer-alt';
		if (cat.includes('style') || cat.includes('format')) return 'fas fa-paint-brush';
		return 'fas fa-code';
	}

	// Categorize issues
	$: securityIssues = review?.issues?.filter(i => {
		const cat = i.category?.toLowerCase() || '';
		return cat === 'security' || cat === 'cve' || cat.includes('xss') || cat.includes('sql') || cat.includes('csrf') || cat.includes('vulnerability') || i.cveId;
	}) || [];

	$: bugIssues = review?.issues?.filter(i => {
		const cat = i.category?.toLowerCase() || '';
		const desc = i.description?.toLowerCase() || '';
		return (cat.includes('bug') || cat.includes('error') || cat.includes('hook') || desc.includes('missing dependency'))
			&& !securityIssues.includes(i);
	}) || [];

	$: qualityIssues = review?.issues?.filter(i => !securityIssues.includes(i) && !bugIssues.includes(i)) || [];

	$: filteredIssues = (() => {
		switch (activeIssueTab) {
			case 'security': return securityIssues;
			case 'bugs': return bugIssues;
			case 'quality': return qualityIssues;
			case 'optimizations': return optimizationStatus?.optimizations || [];
			default: return review?.issues || [];
		}
	})();

	// Check if optimization can be run
	$: canRunOptimization = review?.status === 'COMPLETED' && !optimizationStatus?.completed && !optimizationStatus?.inProgress && !optimizationLoading;

	// Group issues by file for Files Changed section
	$: filesWithIssues = (() => {
		const files: Record<string, { issues: number; critical: number }> = {};
		review?.issues?.forEach(issue => {
			const file = issue.filePath || 'Unknown';
			if (!files[file]) files[file] = { issues: 0, critical: 0 };
			files[file].issues++;
			if (issue.severity === 'CRITICAL' || issue.cveId) files[file].critical++;
		});
		return files;
	})();

	function getFileIcon(filePath: string): { icon: string; bg: string; color: string } {
		const ext = filePath.split('.').pop()?.toLowerCase() || '';
		if (ext === 'tsx' || ext === 'jsx') return { icon: 'fab fa-react', bg: 'bg-blue-100', color: 'text-blue-500' };
		if (ext === 'ts' || ext === 'js') return { icon: 'fab fa-js', bg: 'bg-amber-100', color: 'text-amber-600' };
		if (ext === 'json') return { icon: 'fas fa-cube', bg: 'bg-slate-100', color: 'text-slate-500' };
		if (ext === 'java') return { icon: 'fab fa-java', bg: 'bg-red-100', color: 'text-red-500' };
		if (ext === 'py') return { icon: 'fab fa-python', bg: 'bg-blue-100', color: 'text-blue-600' };
		return { icon: 'fas fa-file-code', bg: 'bg-slate-100', color: 'text-slate-500' };
	}
</script>

<svelte:head>
	<title>{review?.prTitle || 'Review'} - CodeLens</title>
	<link rel="stylesheet" href="https://cdnjs.cloudflare.com/ajax/libs/font-awesome/6.5.0/css/all.min.css">
</svelte:head>

<style>
	.hover-lift { transition: transform 0.2s ease, box-shadow 0.2s ease; }
	.hover-lift:hover { transform: translateY(-2px); box-shadow: 0 12px 24px -8px rgba(0, 0, 0, 0.15); }
	.gradient-border { position: relative; background: linear-gradient(135deg, #451a03, #5c2a0a); padding: 2px; border-radius: 16px; overflow: hidden; }
	.gradient-border > div { background: linear-gradient(to bottom right, #0f172a, #1e293b); border-radius: 14px; overflow: hidden; }
	.issue-card { transition: all 0.2s ease; }
	.issue-card:hover { background-color: #f8fafc; }
	.tab-active { position: relative; }
	.tab-active::after { content: ''; position: absolute; bottom: 0; left: 0; right: 0; height: 2px; background: linear-gradient(to right, #451a03, #5c2a0a); border-radius: 2px 2px 0 0; }
	.code-block { font-family: 'Monaco', 'Menlo', 'Ubuntu Mono', monospace; font-size: 13px; }
	.break-words { word-break: break-word; overflow-wrap: anywhere; }
</style>

{#if loading}
	<div class="flex items-center justify-center h-64">
		<div class="animate-spin rounded-full h-8 w-8 border-b-2 border-amber-700"></div>
	</div>
{:else if error}
	<div class="p-8">
		<div class="bg-white rounded-2xl border border-slate-200/50 p-6 text-center text-red-600">
			{error}
		</div>
	</div>
{:else if review}
	<!-- Header -->
	<header class="bg-white/80 backdrop-blur-xl border-b border-slate-200/50 px-8 py-4 sticky top-0 z-10">
		<div class="flex items-center justify-between">
			<div class="flex items-center gap-4">
				<a href="/reviews" class="p-2 text-slate-400 hover:text-slate-600 hover:bg-slate-100 rounded-xl transition-all">
					<i class="fas fa-arrow-left"></i>
				</a>
				<div>
					<div class="flex items-center gap-3 flex-wrap">
						<h1 class="text-xl font-bold text-slate-900">PR #{review.prNumber}</h1>
						<span class="inline-flex items-center gap-1.5 px-2.5 py-1 {getStatusBadge(review.status).class} rounded-full text-xs font-medium">
							<span class="w-1.5 h-1.5 {getStatusBadge(review.status).dot} rounded-full"></span>
							{review.status}
						</span>
						{#if review.llmProvider}
							<span class="inline-flex items-center gap-1.5 px-2.5 py-1 bg-stone-100 text-stone-700 rounded-lg text-xs font-medium">
								<i class="fas fa-robot"></i> {review.llmProvider}
							</span>
						{/if}
					</div>
					<p class="text-slate-500 text-sm mt-0.5">{review.prTitle}</p>
					{#if review.prUrl}
						<a href={review.prUrl} target="_blank" rel="noopener" class="text-blue-600 hover:text-blue-800 text-xs mt-1 inline-block truncate max-w-md">
							{review.prUrl}
						</a>
					{:else if review.commitUrl}
						<a href={review.commitUrl} target="_blank" rel="noopener" class="text-blue-600 hover:text-blue-800 text-xs mt-1 inline-block truncate max-w-md">
							{review.commitUrl}
						</a>
					{/if}
				</div>
			</div>
			<div class="flex items-center gap-3">
				{#if isInProgress}
					<button
						on:click={() => showCancelConfirm = true}
						disabled={cancelling}
						class="flex items-center gap-2 px-4 py-2.5 text-red-600 hover:text-red-700 border border-red-200 hover:border-red-300 hover:bg-red-50 rounded-xl transition-all disabled:opacity-50"
					>
						{#if cancelling}
							<div class="animate-spin rounded-full h-4 w-4 border-b-2 border-red-600"></div>
						{:else}
							<i class="fas fa-times-circle"></i>
						{/if}
						<span class="hidden sm:inline">Cancel</span>
					</button>
				{:else if canRunOptimization}
					<button
						on:click={runOptimizationAnalysis}
						class="flex items-center gap-2 px-4 py-2.5 bg-indigo-600 hover:bg-indigo-700 text-white rounded-xl transition-all hover-lift"
					>
						<i class="fas fa-bolt"></i>
						<span class="hidden sm:inline">Analyze Optimizations</span>
					</button>
				{:else if optimizationLoading || optimizationStatus?.inProgress}
					<div class="flex items-center gap-2 px-4 py-2.5 bg-indigo-100 text-indigo-700 rounded-xl">
						<div class="animate-spin rounded-full h-4 w-4 border-b-2 border-indigo-700"></div>
						<span class="hidden sm:inline">
							{#if optimizationStatus?.progress}
								{optimizationStatus.progress}%
							{:else}
								Analyzing...
							{/if}
						</span>
					</div>
				{/if}
				<a href={review.prUrl} target="_blank" rel="noopener"
				   class="flex items-center gap-2 px-4 py-2.5 text-slate-600 hover:text-slate-900 border border-slate-200 hover:border-slate-300 rounded-xl transition-all hover-lift">
					<i class="fab fa-github"></i>
					<span class="hidden sm:inline">View on GitHub</span>
				</a>
			</div>
		</div>
	</header>

	<div class="p-8">
		<!-- Progress Bar (shown during review) -->
		{#if isInProgress}
			<div class="bg-white rounded-2xl border border-slate-200/50 p-6 mb-6">
				<div class="flex items-center justify-between mb-3">
					<div class="flex items-center gap-3">
						<div class="animate-spin rounded-full h-5 w-5 border-b-2 border-amber-700"></div>
						<span class="font-medium text-slate-900">
							{#if progress?.currentFile}
								Reviewing: {progress.currentFile}
							{:else}
								Analyzing code...
							{/if}
						</span>
					</div>
					<div class="text-sm text-slate-500">
						{#if progress?.elapsedMs}
							{formatElapsed(progress.elapsedMs)}
						{/if}
					</div>
				</div>
				<div class="relative">
					<div class="overflow-hidden h-3 text-xs flex rounded-full bg-slate-200">
						<div
							class="transition-all duration-500 ease-out shadow-none flex flex-col text-center whitespace-nowrap text-white justify-center bg-gradient-to-r from-amber-700 to-amber-900"
							style="width: {progress?.progress || 0}%"
						></div>
					</div>
				</div>
				<div class="flex items-center justify-between mt-3 text-sm text-slate-600">
					<span>
						{#if progress?.totalFiles}
							{progress.filesReviewed || 0} of {progress.totalFiles} files reviewed
						{:else}
							Initializing...
						{/if}
					</span>
					<span class="font-medium">{progress?.progress || 0}%</span>
				</div>
			</div>
		{/if}

		<!-- Error Message (shown when review failed) -->
		{#if review.status === 'FAILED' && review.errorMessage}
			<div class="bg-red-50 border border-red-200 rounded-2xl p-6 mb-6">
				<div class="flex items-start gap-4">
					<div class="w-12 h-12 bg-red-100 rounded-xl flex items-center justify-center flex-shrink-0">
						<i class="fas fa-exclamation-circle text-red-600 text-xl"></i>
					</div>
					<div class="flex-1">
						<h3 class="text-lg font-semibold text-red-900 mb-2">Review Failed</h3>
						<p class="text-red-700">{review.errorMessage}</p>
					</div>
				</div>
			</div>
		{/if}

		<!-- Summary Stats -->
		<div class="grid grid-cols-2 md:grid-cols-5 gap-4 mb-6">
			<div class="bg-white rounded-2xl p-5 border border-slate-200/50 hover-lift">
				<div class="flex items-center gap-3 mb-2">
					<div class="w-10 h-10 bg-slate-100 rounded-xl flex items-center justify-center">
						<i class="fas fa-file-code text-slate-600"></i>
					</div>
				</div>
				<p class="text-2xl font-bold text-slate-900">{review.filesReviewed || 0}</p>
				<p class="text-sm text-slate-500">Files Changed</p>
			</div>
			<div class="bg-white rounded-2xl p-5 border border-slate-200/50 hover-lift">
				<div class="flex items-center gap-3 mb-2">
					<div class="w-10 h-10 bg-green-100 rounded-xl flex items-center justify-center">
						<i class="fas fa-plus text-green-600"></i>
					</div>
				</div>
				<p class="text-2xl font-bold text-green-700">+{review.linesAdded || 0}</p>
				<p class="text-sm text-slate-500">Lines Added</p>
			</div>
			<div class="bg-white rounded-2xl p-5 border border-slate-200/50 hover-lift">
				<div class="flex items-center gap-3 mb-2">
					<div class="w-10 h-10 bg-red-100 rounded-xl flex items-center justify-center">
						<i class="fas fa-minus text-red-600"></i>
					</div>
				</div>
				<p class="text-2xl font-bold text-red-500">-{review.linesRemoved || 0}</p>
				<p class="text-sm text-slate-500">Lines Removed</p>
			</div>
			<div class="bg-white rounded-2xl p-5 border border-slate-200/50 hover-lift">
				<div class="flex items-center gap-3 mb-2">
					<div class="w-10 h-10 bg-amber-100 rounded-xl flex items-center justify-center">
						<i class="fas fa-exclamation-triangle text-amber-600"></i>
					</div>
				</div>
				<p class="text-2xl font-bold text-amber-700">{review.totalIssues || 0}</p>
				<p class="text-sm text-slate-500">Issues Found</p>
			</div>
			<div class="bg-white rounded-2xl p-5 border border-slate-200/50 hover-lift">
				<div class="flex items-center gap-3 mb-2">
					<div class="w-10 h-10 bg-gradient-to-br from-amber-800/10 to-amber-900/10 rounded-xl flex items-center justify-center">
						<i class="fas fa-robot text-amber-800"></i>
					</div>
				</div>
				<p class="text-2xl font-bold bg-gradient-to-r from-amber-800 to-amber-900 bg-clip-text text-transparent uppercase">{review.llmProvider || 'AI'}</p>
				<p class="text-sm text-slate-500">AI Model</p>
			</div>
		</div>

		<!-- Token Usage (for completed reviews) -->
		{#if review.status === 'COMPLETED' && (review.inputTokens || review.outputTokens)}
			<div class="bg-white rounded-2xl border border-slate-200/50 p-5 mb-6">
				<div class="flex items-center gap-3 mb-4">
					<div class="w-10 h-10 bg-indigo-100 rounded-xl flex items-center justify-center">
						<i class="fas fa-coins text-indigo-600"></i>
					</div>
					<h3 class="font-semibold text-slate-900">Token Usage</h3>
				</div>
				<div class="grid grid-cols-3 gap-4">
					<div class="bg-slate-50 rounded-xl p-4">
						<p class="text-sm text-slate-500 mb-1">Input Tokens</p>
						<p class="text-xl font-bold text-slate-900">{(review.inputTokens || 0).toLocaleString()}</p>
					</div>
					<div class="bg-slate-50 rounded-xl p-4">
						<p class="text-sm text-slate-500 mb-1">Output Tokens</p>
						<p class="text-xl font-bold text-slate-900">{(review.outputTokens || 0).toLocaleString()}</p>
					</div>
					<div class="bg-slate-50 rounded-xl p-4">
						<p class="text-sm text-slate-500 mb-1">Total Tokens</p>
						<p class="text-xl font-bold text-indigo-600">{((review.inputTokens || 0) + (review.outputTokens || 0)).toLocaleString()}</p>
					</div>
				</div>
				{#if review.estimatedCost}
					<div class="mt-4 pt-4 border-t border-slate-100 flex items-center justify-between">
						<span class="text-sm text-slate-500">Estimated Cost</span>
						<span class="font-semibold text-slate-900">${review.estimatedCost.toFixed(4)}</span>
					</div>
				{/if}
			</div>
		{/if}

		<!-- AI Summary -->
		{#if review.summary}
			<div class="gradient-border mb-6">
				<div class="p-6">
					<div class="flex items-start gap-4">
						<div class="w-12 h-12 bg-gradient-to-br from-amber-800 to-amber-900 rounded-xl flex items-center justify-center flex-shrink-0 shadow-lg shadow-amber-800/25">
							<i class="fas fa-sparkles text-white text-lg"></i>
						</div>
						<div class="flex-1 min-w-0 overflow-hidden">
							<h3 class="text-lg font-semibold text-white mb-3">AI Summary</h3>
							<div class="text-slate-300 leading-relaxed prose prose-invert prose-sm max-w-none break-words">
								{@html renderMarkdown(review.summary)}
							</div>
							<div class="flex items-center gap-4 mt-4">
								{#if securityIssues.length > 0}
									<div class="flex items-center gap-2">
										<div class="w-2 h-2 bg-red-400 rounded-full"></div>
										<span class="text-sm text-slate-400">{securityIssues.length} Security</span>
									</div>
								{/if}
								{#if bugIssues.length > 0}
									<div class="flex items-center gap-2">
										<div class="w-2 h-2 bg-amber-400 rounded-full"></div>
										<span class="text-sm text-slate-400">{bugIssues.length} Bug{bugIssues.length > 1 ? 's' : ''}</span>
									</div>
								{/if}
								{#if qualityIssues.length > 0}
									<div class="flex items-center gap-2">
										<div class="w-2 h-2 bg-slate-400 rounded-full"></div>
										<span class="text-sm text-slate-400">{qualityIssues.length} Quality</span>
									</div>
								{/if}
							</div>
						</div>
					</div>
				</div>
			</div>
		{/if}

		<!-- Main View Tabs -->
		<div class="flex gap-2 mb-6">
			<button
				on:click={() => handleMainTabChange('issues')}
				class="flex items-center gap-2 px-5 py-2.5 rounded-xl transition-all {activeMainTab === 'issues' ? 'bg-amber-800 text-white shadow-lg shadow-amber-800/25' : 'bg-white text-slate-600 hover:bg-slate-50 border border-slate-200'}"
			>
				<i class="fas fa-exclamation-triangle"></i>
				Issues
				<span class="px-2 py-0.5 {activeMainTab === 'issues' ? 'bg-white/20' : 'bg-slate-100'} rounded-full text-xs font-medium">{review.totalIssues || 0}</span>
			</button>
			<button
				on:click={() => handleMainTabChange('diff')}
				class="flex items-center gap-2 px-5 py-2.5 rounded-xl transition-all {activeMainTab === 'diff' ? 'bg-amber-800 text-white shadow-lg shadow-amber-800/25' : 'bg-white text-slate-600 hover:bg-slate-50 border border-slate-200'}"
			>
				<i class="fas fa-code-compare"></i>
				Diff
				<span class="px-2 py-0.5 {activeMainTab === 'diff' ? 'bg-white/20' : 'bg-slate-100'} rounded-full text-xs font-medium">{review.filesReviewed || 0}</span>
			</button>
		</div>

		<!-- Diff View -->
		{#if activeMainTab === 'diff'}
			<div class="bg-white rounded-2xl border border-slate-200/50 mb-6 p-6">
				{#if diffError}
					<div class="text-center py-8 text-red-600">
						<i class="fas fa-exclamation-circle text-4xl text-red-300 mb-3"></i>
						<p>{diffError}</p>
					</div>
				{:else}
					<DiffViewer files={diffData?.files || []} loading={diffLoading} />
				{/if}
			</div>
		{:else}
			<!-- Tabs and Issues -->
			<div class="bg-white rounded-2xl border border-slate-200/50 mb-6 overflow-hidden">
			<div class="border-b border-slate-100">
				<nav class="flex gap-1 px-2 pt-2">
					<button
						class="px-5 py-3 rounded-t-xl transition-colors {activeIssueTab === 'all' ? 'text-amber-800 font-medium tab-active' : 'text-slate-500 hover:text-slate-700'}"
						on:click={() => activeIssueTab = 'all'}
					>
						<i class="fas fa-list mr-2 opacity-70"></i>
						All Issues
						<span class="ml-2 bg-slate-100 text-slate-600 text-xs px-2 py-0.5 rounded-full">{review.issues?.length || 0}</span>
					</button>
					<button
						class="px-5 py-3 rounded-t-xl transition-colors {activeIssueTab === 'security' ? 'text-amber-800 font-medium tab-active' : 'text-slate-500 hover:text-slate-700'}"
						on:click={() => activeIssueTab = 'security'}
					>
						<i class="fas fa-shield-alt mr-2 opacity-70"></i>
						Security
						<span class="ml-2 {securityIssues.length > 0 ? 'bg-red-100 text-red-700' : 'bg-slate-100 text-slate-600'} text-xs px-2 py-0.5 rounded-full">{securityIssues.length}</span>
					</button>
					<button
						class="px-5 py-3 rounded-t-xl transition-colors {activeIssueTab === 'bugs' ? 'text-amber-800 font-medium tab-active' : 'text-slate-500 hover:text-slate-700'}"
						on:click={() => activeIssueTab = 'bugs'}
					>
						<i class="fas fa-bug mr-2 opacity-70"></i>
						Bugs
						<span class="ml-2 {bugIssues.length > 0 ? 'bg-amber-100 text-amber-700' : 'bg-slate-100 text-slate-600'} text-xs px-2 py-0.5 rounded-full">{bugIssues.length}</span>
					</button>
					<button
						class="px-5 py-3 rounded-t-xl transition-colors {activeIssueTab === 'quality' ? 'text-amber-800 font-medium tab-active' : 'text-slate-500 hover:text-slate-700'}"
						on:click={() => activeIssueTab = 'quality'}
					>
						<i class="fas fa-code mr-2 opacity-70"></i>
						Code Quality
						<span class="ml-2 bg-slate-100 text-slate-600 text-xs px-2 py-0.5 rounded-full">{qualityIssues.length}</span>
					</button>
					{#if optimizationStatus?.completed || optimizationStatus?.inProgress || optimizationLoading}
						<button
							class="px-5 py-3 rounded-t-xl transition-colors {activeIssueTab === 'optimizations' ? 'text-indigo-700 font-medium tab-active' : 'text-slate-500 hover:text-slate-700'}"
							on:click={() => activeIssueTab = 'optimizations'}
						>
							<i class="fas fa-bolt mr-2 opacity-70"></i>
							Optimizations
							{#if optimizationLoading || optimizationStatus?.inProgress}
								<span class="ml-2 animate-spin rounded-full h-3 w-3 border-b-2 border-indigo-600 inline-block"></span>
							{:else}
								<span class="ml-2 {(optimizationStatus?.optimizations?.length || 0) > 0 ? 'bg-indigo-100 text-indigo-700' : 'bg-slate-100 text-slate-600'} text-xs px-2 py-0.5 rounded-full">{optimizationStatus?.optimizations?.length || 0}</span>
							{/if}
						</button>
					{/if}
				</nav>
			</div>

			<!-- Optimization Progress Bar -->
			{#if activeIssueTab === 'optimizations' && optimizationStatus?.inProgress}
				<div class="p-6 border-b border-slate-100 bg-gradient-to-r from-indigo-50 to-slate-50">
					<div class="flex items-center justify-between mb-3">
						<div class="flex items-center gap-3">
							<div class="animate-spin rounded-full h-5 w-5 border-b-2 border-indigo-700"></div>
							<span class="font-medium text-indigo-900">
								{#if optimizationStatus.currentFile}
									Analyzing: {optimizationStatus.currentFile}
								{:else}
									Analyzing code for optimizations...
								{/if}
							</span>
						</div>
						<div class="text-sm text-indigo-600">
							{#if optimizationStatus.elapsedMs}
								{formatElapsed(optimizationStatus.elapsedMs)}
							{/if}
						</div>
					</div>
					<div class="relative">
						<div class="overflow-hidden h-3 text-xs flex rounded-full bg-indigo-200">
							<div
								class="transition-all duration-500 ease-out shadow-none flex flex-col text-center whitespace-nowrap text-white justify-center bg-gradient-to-r from-indigo-500 to-indigo-700"
								style="width: {optimizationStatus.progress || 0}%"
							></div>
						</div>
					</div>
					<div class="flex items-center justify-between mt-3 text-sm text-indigo-700">
						<span>
							{#if optimizationStatus.totalFiles}
								{optimizationStatus.filesAnalyzed || 0} of {optimizationStatus.totalFiles} files analyzed
							{:else}
								Initializing...
							{/if}
						</span>
						<span class="font-medium">{optimizationStatus.progress || 0}%</span>
					</div>
				</div>
			{/if}

			<!-- Optimization Summary -->
			{#if activeIssueTab === 'optimizations' && optimizationStatus?.summary && !optimizationStatus?.inProgress}
				<div class="p-6 border-b border-slate-100 bg-gradient-to-r from-indigo-50 to-slate-50">
					<div class="flex items-start gap-4">
						<div class="w-10 h-10 bg-indigo-600 rounded-xl flex items-center justify-center flex-shrink-0">
							<i class="fas fa-bolt text-white"></i>
						</div>
						<div>
							<h4 class="font-semibold text-indigo-900 mb-2">Optimization Analysis Summary</h4>
							<p class="text-indigo-700 text-sm whitespace-pre-line">{optimizationStatus.summary}</p>
						</div>
					</div>
				</div>
			{/if}

			<!-- Issues List -->
			<div class="divide-y divide-slate-100">
				{#each filteredIssues as issue}
					<div class="p-6 issue-card">
						<div class="flex items-start gap-4">
							{#if activeIssueTab === 'optimizations'}
								<div class="w-11 h-11 bg-gradient-to-br from-indigo-500 to-indigo-600 rounded-xl flex items-center justify-center flex-shrink-0 shadow-lg shadow-indigo-500/25">
									<i class="fas fa-bolt text-white"></i>
								</div>
							{:else}
								<div class="w-11 h-11 bg-gradient-to-br {getSeverityStyle(issue.severity).iconBg} rounded-xl flex items-center justify-center flex-shrink-0 shadow-lg shadow-stone-500/25">
									<i class="{getSeverityIcon(issue.severity)} text-white"></i>
								</div>
							{/if}
							<div class="flex-1 min-w-0 overflow-hidden">
								<div class="flex items-center gap-2 mb-2 flex-wrap">
									<span class="px-2.5 py-1 {activeIssueTab === 'optimizations' ? 'bg-indigo-600 text-white' : getSeverityStyle(issue.severity).bg + ' ' + getSeverityStyle(issue.severity).text} rounded-lg text-xs font-semibold">{issue.severity}</span>
									{#if issue.cveId}
										<span class="px-2.5 py-1 bg-red-700 text-white rounded-lg text-xs font-semibold">{issue.cveId}</span>
									{/if}
									<span class="px-2.5 py-1 {activeIssueTab === 'optimizations' ? 'bg-indigo-100 text-indigo-700' : 'bg-slate-100 text-slate-700'} rounded-lg text-xs font-medium">
										{#if activeIssueTab === 'optimizations'}
											{issue.category?.replace('OPTIMIZATION', '').replace(/-/g, ' ').trim() || 'Optimization'}
										{:else}
											{issue.source === 'AI' ? 'AI Review' : issue.category || 'Static Analysis'}
										{/if}
									</span>
									<span class="text-slate-400 text-sm ml-auto truncate max-w-[200px]" title="{issue.filePath}:{issue.lineNumber}">{issue.filePath.split('/').pop()}:{issue.lineNumber}</span>
								</div>
								<h4 class="font-semibold text-slate-900 mb-2 break-words">{issue.description}</h4>

								{#if issue.cvssScore}
									<p class="text-slate-600 text-sm mb-4">
										CVSS Score: <span class="font-bold text-stone-700">{issue.cvssScore}</span>
									</p>
								{/if}

								{#if issue.aiExplanation && activeIssueTab === 'optimizations'}
									<div class="bg-slate-50 border border-slate-200 rounded-xl p-4 mt-3 mb-3">
										<div class="text-slate-700 text-sm prose prose-sm max-w-none">
											{@html renderMarkdown(issue.aiExplanation)}
										</div>
									</div>
								{/if}

								{#if issue.suggestion}
									<div class="bg-gradient-to-br {activeIssueTab === 'optimizations' ? 'from-indigo-50 to-slate-50 border-indigo-200' : 'from-amber-50 to-slate-50 border-amber-200'} border rounded-xl p-4 mt-3">
										<div class="flex items-center gap-2 mb-2">
											<div class="w-6 h-6 {activeIssueTab === 'optimizations' ? 'bg-indigo-600' : 'bg-amber-800'} rounded-lg flex items-center justify-center">
												<i class="fas fa-lightbulb text-white text-xs"></i>
											</div>
											<span class="font-semibold {activeIssueTab === 'optimizations' ? 'text-indigo-900' : 'text-amber-900'}">Suggested Fix</span>
										</div>
										{#if formatSuggestion(issue.suggestion).isCode}
										<pre class="bg-slate-800 text-slate-100 rounded-lg p-3 text-sm overflow-x-auto whitespace-pre-wrap break-words"><code>{formatSuggestion(issue.suggestion).formatted}</code></pre>
									{:else}
										<p class="{activeIssueTab === 'optimizations' ? 'text-indigo-800' : 'text-amber-800'} text-sm whitespace-pre-wrap">{issue.suggestion}</p>
									{/if}
									</div>
								{/if}
							</div>
						</div>
					</div>
				{:else}
					<div class="p-8 text-center text-slate-500">
						{#if activeIssueTab === 'optimizations' && (optimizationLoading || optimizationStatus?.inProgress)}
							<!-- Progress is shown above, just show a placeholder here -->
							<i class="fas fa-bolt text-4xl text-indigo-300 mb-3"></i>
							<p>Optimization analysis in progress...</p>
						{:else if activeIssueTab === 'optimizations'}
							<i class="fas fa-rocket text-4xl text-indigo-300 mb-3"></i>
							<p>No optimization suggestions found</p>
						{:else}
							<i class="fas fa-check-circle text-4xl text-green-300 mb-3"></i>
							<p>No issues found in this category</p>
						{/if}
					</div>
				{/each}
			</div>
		</div>

		<!-- Files Changed -->
		{#if Object.keys(filesWithIssues).length > 0}
			<div class="bg-white rounded-2xl border border-slate-200/50 overflow-hidden">
				<div class="px-6 py-4 border-b border-slate-100 flex items-center justify-between">
					<h3 class="font-semibold text-slate-900 flex items-center gap-2">
						<i class="fas fa-folder-open text-slate-400"></i>
						Files with Issues
						<span class="text-slate-400 font-normal">({Object.keys(filesWithIssues).length})</span>
					</h3>
				</div>
				<div class="divide-y divide-slate-100">
					{#each Object.entries(filesWithIssues) as [file, stats]}
						<div class="px-6 py-4 flex items-center justify-between hover:bg-slate-50 transition-colors">
							<div class="flex items-center gap-3">
								<div class="w-8 h-8 {getFileIcon(file).bg} rounded-lg flex items-center justify-center">
									<i class="{getFileIcon(file).icon} {getFileIcon(file).color}"></i>
								</div>
								<span class="text-sm font-medium text-slate-700">{file}</span>
							</div>
							<div class="flex items-center gap-4">
								<span class="px-2.5 py-1 {stats.critical > 0 ? 'bg-red-100 text-red-700' : 'bg-stone-200 text-stone-700'} rounded-lg text-xs font-medium">
									{stats.issues} issue{stats.issues > 1 ? 's' : ''}
									{#if stats.critical > 0}
										<span class="ml-1">({stats.critical} critical)</span>
									{/if}
								</span>
							</div>
						</div>
					{/each}
				</div>
			</div>
		{/if}
		{/if}

		<!-- Review metadata -->
		<div class="mt-6 text-sm text-slate-500 text-center">
			<p>Created: {formatDate(review.createdAt)}</p>
			{#if review.completedAt}
				<p>Completed: {formatDate(review.completedAt)}</p>
			{/if}
			{#if review.cancelledAt}
				<p>Cancelled: {formatDate(review.cancelledAt)}</p>
				{#if review.cancellationReason}
					<p class="text-slate-400">Reason: {review.cancellationReason}</p>
				{/if}
			{/if}
			<p class="mt-1 font-mono text-xs text-slate-400">
				Review ID: {review.id}
			</p>
		</div>
	</div>

	<!-- Cancel Confirmation Modal -->
	{#if showCancelConfirm}
		<div class="fixed inset-0 bg-black/50 flex items-center justify-center z-50" on:click={() => showCancelConfirm = false} on:keydown={(e) => e.key === 'Escape' && (showCancelConfirm = false)} role="dialog" aria-modal="true">
			<div class="bg-white rounded-2xl shadow-xl max-w-md w-full mx-4 p-6" on:click|stopPropagation on:keydown|stopPropagation role="document">
				<div class="flex items-center gap-4 mb-4">
					<div class="w-12 h-12 bg-red-100 rounded-xl flex items-center justify-center">
						<i class="fas fa-exclamation-triangle text-red-600 text-xl"></i>
					</div>
					<div>
						<h3 class="text-lg font-semibold text-slate-900">Cancel Review?</h3>
						<p class="text-slate-500 text-sm">This action cannot be undone.</p>
					</div>
				</div>
				<p class="text-slate-600 mb-6">
					Are you sure you want to cancel this review? Any progress made so far will be lost.
				</p>
				<div class="flex justify-end gap-3">
					<button
						on:click={() => showCancelConfirm = false}
						class="px-4 py-2 text-slate-600 hover:text-slate-900 border border-slate-200 hover:border-slate-300 rounded-xl transition-all"
					>
						Keep Reviewing
					</button>
					<button
						on:click={cancelReview}
						class="px-4 py-2 bg-red-600 hover:bg-red-700 text-white rounded-xl transition-all"
					>
						Cancel Review
					</button>
				</div>
			</div>
		</div>
	{/if}
{/if}
