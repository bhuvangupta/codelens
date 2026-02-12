<script lang="ts">
	import { onMount, onDestroy } from 'svelte';
	import { analytics, reviews, llm, settings, user as userApi } from '$lib/api/client';
	import type { DashboardStats, Review, LlmProvider, ActivityItem, PendingMembership } from '$lib/api/client';

	let { data } = $props<{ user: { email: string; name: string; picture: string; id: string; } | null }>();
	let stats: DashboardStats | null = $state(null);
	let recentReviews: Review[] = $state([]);
	let aiModels: (LlmProvider & { isDefault?: boolean })[] = $state([]);
	let activities: ActivityItem[] = $state([]);
	let pendingMembership: PendingMembership | null = $state(null);
	let loading = $state(true);
	let error: string | null = $state(null);
	let greeting = $state('Good morning');
	let pollInterval: ReturnType<typeof setInterval> | null = null;

	async function loadDashboardData() {
		try {
			const [statsData, reviewsData, activityData] = await Promise.all([
				analytics.getDashboard(),
				reviews.getRecent(5),
				analytics.getActivity(5).catch(() => [])
			]);
			stats = statsData;
			recentReviews = reviewsData;
			activities = activityData;
		} catch (e) {
			console.error('Failed to refresh dashboard:', e);
		}
	}

	function startPolling() {
		if (pollInterval) return;
		pollInterval = setInterval(loadDashboardData, 5000);
	}

	function stopPolling() {
		if (pollInterval) {
			clearInterval(pollInterval);
			pollInterval = null;
		}
	}

	// Start/stop polling based on in-progress reviews
	$effect(() => {
		const hasInProgress = (stats?.inProgressReviews ?? 0) > 0 ||
			(stats?.pendingReviews ?? 0) > 0 ||
			recentReviews.some(r => r.status === 'IN_PROGRESS' || r.status === 'PENDING');
		if (hasInProgress) {
			startPolling();
		} else {
			stopPolling();
		}
	});

	onMount(async () => {
		// Set greeting based on time
		const hour = new Date().getHours();
		greeting = hour < 12 ? 'Good morning' : hour < 18 ? 'Good afternoon' : 'Good evening';

		try {
			const [statsData, reviewsData, modelsData, activityData, userSettings, userData] = await Promise.all([
				analytics.getDashboard(),
				reviews.getRecent(5),
				llm.getProviders().catch(() => []),
				analytics.getActivity(5).catch(() => []),
				settings.getUser().catch(() => null),
				userApi.sync().catch(() => null)
			]);
			stats = statsData;
			recentReviews = reviewsData;
			// Map providers to include isDefault flag
			const defaultProvider = userSettings?.defaultLlmProvider;
			aiModels = (Array.isArray(modelsData) ? modelsData : []).map(p => ({
				...p,
				isDefault: p.name === defaultProvider
			}));
			activities = activityData;
			// Check for pending membership
			if (userData?.pendingMembership) {
				pendingMembership = userData.pendingMembership;
			}
		} catch (e) {
			error = e instanceof Error ? e.message : 'Failed to load dashboard';
		} finally {
			loading = false;
		}
	});

	onDestroy(() => {
		stopPolling();
	});

	function getStatusBadge(status: string) {
		switch (status) {
			case 'COMPLETED':
				return { bg: 'bg-stone-700', text: 'text-white', icon: true, label: 'Completed' };
			case 'IN_PROGRESS':
				return { bg: 'bg-primary/10', text: 'text-primary', pulse: true, label: 'In Progress' };
			case 'PENDING':
				return { bg: 'bg-stone-100', text: 'text-stone-600', pulse: true, label: 'Pending' };
			case 'FAILED':
				return { bg: 'bg-stone-200', text: 'text-stone-600', warning: true, label: 'Failed' };
			default:
				return { bg: 'bg-gray-100', text: 'text-gray-600', label: status };
		}
	}

	function formatTimeAgo(dateStr: string): string {
		const date = new Date(dateStr);
		const now = new Date();
		const diffMs = now.getTime() - date.getTime();
		const diffMins = Math.floor(diffMs / 60000);
		const diffHours = Math.floor(diffMs / 3600000);
		const diffDays = Math.floor(diffMs / 86400000);

		if (diffMins < 1) return 'Just now';
		if (diffMins < 60) return `${diffMins} mins ago`;
		if (diffHours < 24) return `${diffHours} hours ago`;
		return `${diffDays} days ago`;
	}

	function getReviewNumberBg(status: string) {
		if (status === 'COMPLETED') return 'bg-stone-700';
		if (status === 'FAILED') return 'bg-stone-400';
		return 'bg-gradient-to-br from-primary to-secondary';
	}
</script>

<svelte:head>
	<title>Dashboard - CodeLens</title>
</svelte:head>

<div class="max-w-6xl mx-auto p-4 sm:p-6 lg:p-8">
	<!-- Welcome Section -->
	<div class="mb-6 lg:mb-8">
		<div class="flex flex-col sm:flex-row sm:items-start sm:justify-between gap-4">
			<div>
				<h1 class="text-2xl sm:text-3xl font-bold text-slate-900 mb-2">
					{greeting}, {data?.user?.name?.split(' ')[0] || 'there'}!
				</h1>
				<p class="text-slate-500 text-base lg:text-lg">Here's what's happening with your code reviews today.</p>
			</div>
		</div>
	</div>

	<!-- Pending Membership Banner -->
	{#if pendingMembership}
		<div class="mb-6 p-4 bg-amber-50 border-2 border-amber-200 rounded-2xl">
			<div class="flex items-center gap-4">
				<div class="w-12 h-12 bg-amber-200 rounded-xl flex items-center justify-center shrink-0">
					<svg class="w-6 h-6 text-amber-700" fill="none" stroke="currentColor" viewBox="0 0 24 24">
						<path stroke-linecap="round" stroke-linejoin="round" stroke-width="2" d="M12 8v4l3 3m6-3a9 9 0 11-18 0 9 9 0 0118 0z" />
					</svg>
				</div>
				<div class="flex-1">
					<h3 class="font-semibold text-amber-800">Pending Organization Approval</h3>
					<p class="text-sm text-amber-700">
						Your request to join <strong>{pendingMembership.organizationName}</strong> is pending approval from an administrator.
						You can still submit reviews while waiting.
					</p>
				</div>
			</div>
		</div>
	{/if}

	{#if loading}
		<div class="flex items-center justify-center h-64">
			<div class="animate-spin rounded-full h-8 w-8 border-b-2 border-primary"></div>
		</div>
	{:else if error}
		<div class="card p-6 text-center text-red-600">
			{error}
		</div>
	{:else}
		<!-- Stats Grid - Bento Style -->
		<div class="grid grid-cols-1 sm:grid-cols-2 lg:grid-cols-12 gap-4 mb-6 lg:mb-8">
			<!-- Main Stat - Large -->
			<div class="sm:col-span-2 lg:col-span-4 lg:row-span-2 bg-gradient-to-br from-primary to-secondary rounded-3xl p-5 sm:p-6 text-white hover-lift cursor-pointer">
				<div class="flex items-center justify-between mb-4">
					<span class="text-white/80 font-medium">Reviews Completed</span>
					<div class="w-10 h-10 bg-white/20 rounded-xl flex items-center justify-center">
						<svg class="w-5 h-5" fill="none" stroke="currentColor" viewBox="0 0 24 24">
							<path stroke-linecap="round" stroke-linejoin="round" stroke-width="2" d="M9 12l2 2 4-4m6 2a9 9 0 11-18 0 9 9 0 0118 0z" />
						</svg>
					</div>
				</div>
				<div class="text-5xl font-bold mb-2">{stats?.completedReviews || 0}</div>
				<p class="text-white/70 text-sm mb-4">Total completed</p>
				<div class="flex items-center gap-2 text-sm">
					<span class="px-2 py-1 bg-white/20 rounded-lg">
						{stats?.completedThisWeek || 0} this week
					</span>
				</div>
			</div>

			<!-- Pending Reviews -->
			<div class="lg:col-span-4 bg-white rounded-3xl p-5 sm:p-6 border border-stone-200 hover-lift cursor-pointer">
				<div class="flex items-center justify-between mb-4">
					<div class="w-12 h-12 bg-stone-100 rounded-2xl flex items-center justify-center">
						<svg class="w-6 h-6 text-stone-500" fill="none" stroke="currentColor" viewBox="0 0 24 24">
							<path stroke-linecap="round" stroke-linejoin="round" stroke-width="2" d="M12 8v4l3 3m6-3a9 9 0 11-18 0 9 9 0 0118 0z" />
						</svg>
					</div>
					<span class="text-xs font-medium text-primary bg-primary/10 px-3 py-1 rounded-full">Needs attention</span>
				</div>
				<div class="text-3xl font-bold text-slate-900 mb-1">{stats?.pendingReviews || 0}</div>
				<p class="text-slate-500 text-sm">Pending reviews</p>
			</div>

			<!-- In Progress -->
			<div class="lg:col-span-4 bg-white rounded-3xl p-5 sm:p-6 border border-stone-200 hover-lift cursor-pointer">
				<div class="flex items-center justify-between mb-4">
					<div class="w-12 h-12 bg-stone-100 rounded-2xl flex items-center justify-center">
						<svg class="w-6 h-6 text-stone-500" class:animate-spin={(stats?.inProgressReviews ?? 0) > 0} fill="none" stroke="currentColor" viewBox="0 0 24 24">
							<path stroke-linecap="round" stroke-linejoin="round" stroke-width="2" d="M4 4v5h.582m15.356 2A8.001 8.001 0 004.582 9m0 0H9m11 11v-5h-.581m0 0a8.003 8.003 0 01-15.357-2m15.357 2H15" />
						</svg>
					</div>
					{#if (stats?.inProgressReviews ?? 0) > 0}
						<span class="activity-dot w-2 h-2 bg-primary rounded-full animate-pulse"></span>
					{/if}
				</div>
				<div class="text-3xl font-bold text-slate-900 mb-1">{stats?.inProgressReviews || 0}</div>
				<p class="text-slate-500 text-sm">In progress</p>
			</div>

			<!-- Issues Found -->
			<div class="sm:col-span-2 lg:col-span-4 bg-white rounded-3xl p-5 sm:p-6 border border-stone-200 hover-lift cursor-pointer">
				<div class="flex items-center gap-4">
					<div class="relative">
						<svg class="w-16 h-16 stat-ring">
							<circle cx="32" cy="32" r="28" fill="none" stroke="#e7e5e4" stroke-width="6"/>
							<circle cx="32" cy="32" r="28" fill="none" stroke="url(#gradient)" stroke-width="6"
									stroke-dasharray="176" stroke-dashoffset="44" stroke-linecap="round"/>
							<defs>
								<linearGradient id="gradient" x1="0%" y1="0%" x2="100%" y2="0%">
									<stop offset="0%" stop-color="#78350f"/>
									<stop offset="100%" stop-color="#92400e"/>
								</linearGradient>
							</defs>
						</svg>
						<div class="absolute inset-0 flex items-center justify-center">
							<span class="text-lg font-bold text-slate-900">{stats?.totalIssues || 0}</span>
						</div>
					</div>
					<div>
						<p class="text-sm text-slate-500 mb-1">Issues found today</p>
						<div class="flex items-center gap-2">
							<span class="text-xs px-2 py-0.5 bg-stone-200 text-stone-700 rounded-full">{stats?.issuesBySeverity?.critical || 0} critical</span>
							<span class="text-xs px-2 py-0.5 bg-stone-100 text-stone-600 rounded-full">{(stats?.issuesBySeverity?.high || 0) + (stats?.issuesBySeverity?.medium || 0)} warnings</span>
						</div>
					</div>
				</div>
			</div>

			<!-- Time Saved -->
			<div class="sm:col-span-2 lg:col-span-4 bg-stone-800 rounded-3xl p-5 sm:p-6 text-white hover-lift cursor-pointer">
				<div class="flex items-center justify-between mb-3">
					<span class="text-stone-300 font-medium">Time Saved</span>
					<svg class="w-5 h-5 text-stone-400" fill="none" stroke="currentColor" viewBox="0 0 24 24">
						<path stroke-linecap="round" stroke-linejoin="round" stroke-width="2" d="M12 8v4l3 3m6-3a9 9 0 11-18 0 9 9 0 0118 0z" />
					</svg>
				</div>
				<div class="text-3xl font-bold mb-1">{stats?.timeSavedHours || 0} hrs</div>
				<p class="text-stone-400 text-sm">This month vs manual review</p>
			</div>
		</div>

		<!-- Two Column Layout -->
		<div class="grid grid-cols-1 lg:grid-cols-3 gap-6">
			<!-- Recent Activity -->
			<div class="lg:col-span-2">
				<div class="flex items-center justify-between mb-4">
					<h2 class="text-lg font-semibold text-slate-900">Recent Reviews</h2>
					<a href="/reviews" class="text-sm text-primary hover:text-secondary font-medium flex items-center gap-1">
						View all
						<svg class="w-4 h-4" fill="none" stroke="currentColor" viewBox="0 0 24 24">
							<path stroke-linecap="round" stroke-linejoin="round" stroke-width="2" d="M9 5l7 7-7 7" />
						</svg>
					</a>
				</div>

				<div class="space-y-3">
					{#each recentReviews as review}
						{@const badge = getStatusBadge(review.status)}
						<a href="/reviews/{review.id}" class="block bg-white rounded-2xl p-5 border border-stone-200 hover-lift cursor-pointer group">
							<div class="flex items-start gap-4">
								<div class="w-10 h-10 {getReviewNumberBg(review.status)} rounded-xl flex items-center justify-center text-white font-bold text-sm">
									#{review.prNumber}
								</div>
								<div class="flex-1 min-w-0">
									<div class="flex items-center gap-2 mb-1 flex-wrap">
										<h3 class="font-medium text-slate-900 group-hover:text-primary transition-colors">{review.prTitle || `PR #${review.prNumber}`}</h3>
										<span class="px-2 py-0.5 {badge.bg} {badge.text} rounded-full text-xs font-medium flex items-center gap-1">
											{#if badge.icon}
												<svg class="w-2.5 h-2.5" fill="currentColor" viewBox="0 0 24 24">
													<path d="M9 16.17L4.83 12l-1.42 1.41L9 19 21 7l-1.41-1.41L9 16.17z" />
												</svg>
											{:else if badge.warning}
												<svg class="w-2.5 h-2.5" fill="currentColor" viewBox="0 0 24 24">
													<path d="M1 21h22L12 2 1 21zm12-3h-2v-2h2v2zm0-4h-2v-4h2v4z" />
												</svg>
											{:else if badge.pulse}
												<span class="w-1.5 h-1.5 {review.status === 'IN_PROGRESS' ? 'bg-primary' : 'bg-stone-400'} rounded-full animate-pulse"></span>
											{/if}
											{badge.label}
										</span>
									</div>
									<p class="text-sm text-slate-500 mb-2 truncate">{review.repositoryName || review.prUrl}</p>
									<div class="flex items-center gap-4 text-xs text-slate-400">
										<span class="flex items-center gap-1">
											<svg class="w-3.5 h-3.5" fill="none" stroke="currentColor" viewBox="0 0 24 24">
												<path stroke-linecap="round" stroke-linejoin="round" stroke-width="2" d="M9.75 17L9 20l-1 1h8l-1-1-.75-3M3 13h18M5 17h14a2 2 0 002-2V5a2 2 0 00-2-2H5a2 2 0 00-2 2v10a2 2 0 002 2z" />
											</svg>
											{review.llmProvider || 'GLM'}
										</span>
										<span class="flex items-center gap-1">
											<svg class="w-3.5 h-3.5" fill="none" stroke="currentColor" viewBox="0 0 24 24">
												<path stroke-linecap="round" stroke-linejoin="round" stroke-width="2" d="M12 8v4l3 3m6-3a9 9 0 11-18 0 9 9 0 0118 0z" />
											</svg>
											{formatTimeAgo(review.createdAt)}
										</span>
										{#if review.totalIssues > 0}
											<span class="text-slate-500">{review.totalIssues} issues</span>
										{/if}
										{#if review.status === 'FAILED'}
											<button class="text-primary font-medium hover:text-secondary">Retry</button>
										{/if}
									</div>
								</div>
								<svg class="w-5 h-5 text-slate-300 group-hover:text-primary transition-colors flex-shrink-0" fill="none" stroke="currentColor" viewBox="0 0 24 24">
									<path stroke-linecap="round" stroke-linejoin="round" stroke-width="2" d="M9 5l7 7-7 7" />
								</svg>
							</div>
						</a>
					{:else}
						<div class="bg-white rounded-2xl p-8 border border-stone-200 text-center">
							<div class="w-16 h-16 bg-stone-100 rounded-2xl flex items-center justify-center mx-auto mb-4">
								<svg class="w-8 h-8 text-stone-400" fill="none" stroke="currentColor" viewBox="0 0 24 24">
									<path stroke-linecap="round" stroke-linejoin="round" stroke-width="2" d="M9 12h6m-6 4h6m2 5H7a2 2 0 01-2-2V5a2 2 0 012-2h5.586a1 1 0 01.707.293l5.414 5.414a1 1 0 01.293.707V19a2 2 0 01-2 2z" />
								</svg>
							</div>
							<h3 class="font-medium text-slate-900 mb-1">No reviews yet</h3>
							<p class="text-sm text-slate-500 mb-4">Submit your first PR to get started with AI code reviews.</p>
							<a href="/reviews/new" class="btn btn-primary inline-flex items-center gap-2">
								<svg class="w-4 h-4" fill="none" stroke="currentColor" viewBox="0 0 24 24">
									<path stroke-linecap="round" stroke-linejoin="round" stroke-width="2" d="M12 4v16m8-8H4" />
								</svg>
								Submit PR
							</a>
						</div>
					{/each}
				</div>
			</div>

			<!-- Right Sidebar -->
			<div class="space-y-6">
				<!-- AI Models Status -->
				{#if aiModels.length > 0}
					{@const defaultModel = aiModels.find(m => m.isDefault)}
					{@const otherModels = aiModels.filter(m => !m.isDefault && m.available)}
					<div class="bg-white rounded-2xl p-5 border border-stone-200">
						<h3 class="font-semibold text-slate-900 mb-4">AI Model</h3>
						{#if defaultModel}
							<div class="flex items-center justify-between p-3 bg-primary/5 rounded-xl border border-primary/20 mb-3">
								<div class="flex items-center gap-3">
									<div class="w-8 h-8 bg-primary rounded-lg flex items-center justify-center">
										<span class="font-bold text-white text-sm">{defaultModel.name[0].toUpperCase()}</span>
									</div>
									<div>
										<span class="text-sm font-medium text-slate-900 capitalize">{defaultModel.name}</span>
										<span class="text-xs text-primary ml-2">Active</span>
									</div>
								</div>
								<span class="w-2 h-2 bg-primary rounded-full animate-pulse" title="Active"></span>
							</div>
						{/if}
						{#if otherModels.length > 0}
							<p class="text-xs text-slate-400 mb-2">Also available:</p>
							<div class="flex flex-wrap gap-2">
								{#each otherModels as model}
									<span class="text-xs px-2 py-1 bg-stone-100 text-slate-600 rounded capitalize">{model.name}</span>
								{/each}
							</div>
						{/if}
					</div>
				{/if}

				<!-- Quick Tip -->
				<div class="gradient-border">
					<div class="gradient-border-inner p-5">
						<div class="flex items-start gap-3">
							<div class="w-10 h-10 bg-gradient-to-br from-primary/10 to-secondary/10 rounded-xl flex items-center justify-center flex-shrink-0">
								<svg class="w-5 h-5 text-primary" fill="none" stroke="currentColor" viewBox="0 0 24 24">
									<path stroke-linecap="round" stroke-linejoin="round" stroke-width="2" d="M9.663 17h4.673M12 3v1m6.364 1.636l-.707.707M21 12h-1M4 12H3m3.343-5.657l-.707-.707m2.828 9.9a5 5 0 117.072 0l-.548.547A3.374 3.374 0 0014 18.469V19a2 2 0 11-4 0v-.531c0-.895-.356-1.754-.988-2.386l-.548-.547z" />
								</svg>
							</div>
							<div>
								<h4 class="font-medium text-slate-900 mb-1">Pro Tip</h4>
								<p class="text-sm text-slate-500">Enable auto-post comments to get AI feedback directly on your PRs without manual copy-paste.</p>
								<a href="/settings" class="text-sm text-primary font-medium mt-2 inline-flex items-center gap-1 hover:text-secondary">
									Configure
									<svg class="w-3.5 h-3.5" fill="none" stroke="currentColor" viewBox="0 0 24 24">
										<path stroke-linecap="round" stroke-linejoin="round" stroke-width="2" d="M9 5l7 7-7 7" />
									</svg>
								</a>
							</div>
						</div>
					</div>
				</div>

				<!-- Team Activity -->
				{#if activities.length > 0}
					<div class="bg-white rounded-2xl p-5 border border-stone-200">
						<h3 class="font-semibold text-slate-900 mb-4">Recent Activity</h3>
						<div class="space-y-4">
							{#each activities as activity}
								<div class="flex items-center gap-3">
									{#if activity.userAvatar}
										<img src={activity.userAvatar} alt={activity.userName} class="w-8 h-8 rounded-lg">
									{:else}
										<div class="w-8 h-8 bg-primary/20 rounded-lg flex items-center justify-center">
											<span class="font-medium text-primary text-sm">{activity.userName[0]?.toUpperCase() || '?'}</span>
										</div>
									{/if}
									<div class="flex-1 min-w-0">
										<p class="text-sm text-slate-700 truncate"><span class="font-medium">{activity.userName}</span> {activity.action}</p>
										<p class="text-xs text-slate-400">{activity.timeAgo}</p>
									</div>
								</div>
							{/each}
						</div>
					</div>
				{/if}
			</div>
		</div>
	{/if}
</div>
