<script lang="ts">
	import { onMount } from 'svelte';
	import { analytics } from '$lib/api/client';
	import type { DashboardStats, TrendData, LlmUsage, LlmQuotaStatus, IssueAnalytics, TopRepository, TopIssueType, MonthlyTrendResponse } from '$lib/api/client';

	let stats: DashboardStats | null = $state(null);
	let trends: TrendData | null = $state(null);
	let monthlyTrends: MonthlyTrendResponse | null = $state(null);
	let llmUsage: LlmUsage | null = $state(null);
	let llmQuota: LlmQuotaStatus | null = $state(null);
	let issueAnalytics: IssueAnalytics | null = $state(null);
	let topRepositories: TopRepository[] = $state([]);
	let topIssueTypes: TopIssueType[] = $state([]);
	let loading = $state(true);
	let error: string | null = $state(null);
	let period = $state(30);

	onMount(async () => {
		await loadData();
	});

	async function loadData() {
		loading = true;
		try {
			const [statsData, trendsData, monthlyData, llmData, quotaData, issuesData, reposData, issueTypesData] = await Promise.all([
				analytics.getDashboard(),
				analytics.getTrends(period),
				analytics.getAllTrends(6),
				analytics.getLlmUsage(period),
				analytics.getLlmQuota(),
				analytics.getIssues(period),
				analytics.getTopRepositories(period, 5),
				analytics.getTopIssueTypes(period, 5)
			]);
			stats = statsData;
			trends = trendsData;
			monthlyTrends = monthlyData;
			llmUsage = llmData;
			llmQuota = quotaData;
			issueAnalytics = issuesData;
			topRepositories = reposData;
			topIssueTypes = issueTypesData;
		} catch (e) {
			error = e instanceof Error ? e.message : 'Failed to load analytics';
		} finally {
			loading = false;
		}
	}

	function getTrendInfo(trend: string) {
		switch (trend) {
			case 'improving':
				return { label: 'Improving', color: 'bg-emerald-100 text-emerald-700', icon: '↓' };
			case 'degrading':
				return { label: 'Needs Attention', color: 'bg-red-100 text-red-700', icon: '↑' };
			case 'insufficient_data':
				return { label: 'Not Enough Data', color: 'bg-slate-100 text-slate-600', icon: '—' };
			default:
				return { label: 'Stable', color: 'bg-blue-100 text-blue-700', icon: '→' };
		}
	}

	function formatMonth(monthStr: string): string {
		const [year, month] = monthStr.split('-');
		const date = new Date(parseInt(year), parseInt(month) - 1);
		return date.toLocaleDateString('en-US', { month: 'short', year: '2-digit' });
	}

	function formatNumber(num: number): string {
		if (num >= 1000000) return (num / 1000000).toFixed(1) + 'M';
		if (num >= 1000) return (num / 1000).toFixed(1) + 'K';
		return num.toString();
	}

	function formatCost(cost: number): string {
		return '$' + cost.toFixed(2);
	}

	function getIssueIcon(category: string): string {
		const icons: Record<string, string> = {
			'SECURITY': 'M9 12.75L11.25 15 15 9.75m-3-7.036A11.959 11.959 0 013.598 6 11.99 11.99 0 003 9.749c0 5.592 3.824 10.29 9 11.623 5.176-1.332 9-6.03 9-11.622 0-1.31-.21-2.571-.598-3.751h-.152c-3.196 0-6.1-1.248-8.25-3.285z',
			'CODE_SMELL': 'M12 9v3.75m-9.303 3.376c-.866 1.5.217 3.374 1.948 3.374h14.71c1.73 0 2.813-1.874 1.948-3.374L13.949 3.378c-.866-1.5-3.032-1.5-3.898 0L2.697 16.126zM12 15.75h.007v.008H12v-.008z',
			'PERFORMANCE': 'M3.75 13.5l10.5-11.25L12 10.5h8.25L9.75 21.75 12 13.5H3.75z',
			'BEST_PRACTICE': 'M12 6.042A8.967 8.967 0 006 3.75c-1.052 0-2.062.18-3 .512v14.25A8.987 8.987 0 016 18c2.305 0 4.408.867 6 2.292m0-14.25a8.966 8.966 0 016-2.292c1.052 0 2.062.18 3 .512v14.25A8.987 8.987 0 0018 18a8.967 8.967 0 00-6 2.292m0-14.25v14.25',
			'BUG': 'M12 12.75c1.148 0 2.278.08 3.383.237 1.037.146 1.866.966 1.866 2.013 0 3.728-2.35 6.75-5.25 6.75S6.75 18.728 6.75 15c0-1.046.83-1.867 1.866-2.013A24.204 24.204 0 0112 12.75z',
			'CVE': 'M12 9v3.75m-9.303 3.376c-.866 1.5.217 3.374 1.948 3.374h14.71c1.73 0 2.813-1.874 1.948-3.374L13.949 3.378c-.866-1.5-3.032-1.5-3.898 0L2.697 16.126zM12 15.75h.007v.008H12v-.008z'
		};
		return icons[category] || 'M12 12.75c1.148 0 2.278.08 3.383.237 1.037.146 1.866.966 1.866 2.013 0 3.728-2.35 6.75-5.25 6.75S6.75 18.728 6.75 15c0-1.046.83-1.867 1.866-2.013A24.204 24.204 0 0112 12.75z';
	}
</script>

<svelte:head>
	<title>Analytics - CodeLens</title>
</svelte:head>

<!-- Header -->
<header class="bg-white/80 backdrop-blur-xl border-b border-slate-200/50 px-4 sm:px-6 lg:px-8 py-4 sticky top-0 z-10">
	<div class="flex items-center justify-between">
		<div>
			<h1 class="text-xl font-bold text-slate-900">Analytics</h1>
			<p class="text-slate-500 text-sm">Review metrics and insights</p>
		</div>
		<div class="flex items-center gap-3">
			<div class="relative">
				<select
					bind:value={period}
					onchange={loadData}
					class="appearance-none px-4 py-2.5 pr-10 bg-white border border-slate-200 rounded-xl text-sm focus:ring-2 focus:ring-primary/20 focus:border-primary outline-none cursor-pointer"
				>
					<option value={7}>Last 7 days</option>
					<option value={30}>Last 30 days</option>
					<option value={90}>Last 90 days</option>
				</select>
				<svg class="absolute right-3 top-1/2 -translate-y-1/2 w-4 h-4 text-slate-400 pointer-events-none" fill="none" stroke="currentColor" viewBox="0 0 24 24">
					<path stroke-linecap="round" stroke-linejoin="round" stroke-width="2" d="M19 9l-7 7-7-7" />
				</svg>
			</div>
		</div>
	</div>
</header>

<div class="p-4 sm:p-6 lg:p-8">
	{#if loading}
		<div class="flex items-center justify-center h-64">
			<div class="animate-spin rounded-full h-8 w-8 border-b-2 border-primary"></div>
		</div>
	{:else if error}
		<div class="bg-white rounded-2xl border border-red-200 p-6 text-center text-red-600">
			{error}
		</div>
	{:else}
		<!-- Summary Stats -->
		<div class="grid grid-cols-2 lg:grid-cols-4 gap-3 sm:gap-5 mb-6 lg:mb-8">
			<!-- Total Reviews -->
			<div class="bg-white rounded-2xl p-5 sm:p-6 border border-slate-200/50 hover:shadow-lg hover:-translate-y-0.5 transition-all duration-200">
				<div class="flex items-start justify-between mb-4">
					<div class="w-11 h-11 sm:w-12 sm:h-12 bg-gradient-to-br from-primary to-secondary rounded-xl flex items-center justify-center shadow-lg shadow-primary/25">
						<svg class="w-5 h-5 sm:w-6 sm:h-6 text-white" fill="none" stroke="currentColor" viewBox="0 0 24 24">
							<path stroke-linecap="round" stroke-linejoin="round" stroke-width="2" d="M9 12h6m-6 4h6m2 5H7a2 2 0 01-2-2V5a2 2 0 012-2h5.586a1 1 0 01.707.293l5.414 5.414a1 1 0 01.293.707V19a2 2 0 01-2 2z" />
						</svg>
					</div>
					{#if stats?.reviewsThisWeek}
						<span class="inline-flex items-center gap-1 px-2 py-1 bg-emerald-100 text-emerald-700 rounded-lg text-xs font-medium">
							<svg class="w-3 h-3" fill="none" stroke="currentColor" viewBox="0 0 24 24">
								<path stroke-linecap="round" stroke-linejoin="round" stroke-width="2" d="M5 10l7-7m0 0l7 7m-7-7v18" />
							</svg>
							{stats.reviewsThisWeek} this week
						</span>
					{/if}
				</div>
				<p class="text-2xl sm:text-3xl font-bold text-slate-900 mb-1">{formatNumber(stats?.totalReviews || 0)}</p>
				<p class="text-sm text-slate-500">Total Reviews</p>
			</div>

			<!-- Issues Found -->
			<div class="bg-white rounded-2xl p-5 sm:p-6 border border-slate-200/50 hover:shadow-lg hover:-translate-y-0.5 transition-all duration-200">
				<div class="flex items-start justify-between mb-4">
					<div class="w-11 h-11 sm:w-12 sm:h-12 bg-gradient-to-br from-stone-500 to-stone-600 rounded-xl flex items-center justify-center shadow-lg shadow-stone-500/25">
						<svg class="w-5 h-5 sm:w-6 sm:h-6 text-white" fill="none" stroke="currentColor" viewBox="0 0 24 24">
							<path stroke-linecap="round" stroke-linejoin="round" stroke-width="2" d="M12 9v2m0 4h.01m-6.938 4h13.856c1.54 0 2.502-1.667 1.732-3L13.732 4c-.77-1.333-2.694-1.333-3.464 0L3.34 16c-.77 1.333.192 3 1.732 3z" />
						</svg>
					</div>
				</div>
				<p class="text-2xl sm:text-3xl font-bold text-slate-900 mb-1">{formatNumber(stats?.totalIssues || 0)}</p>
				<p class="text-sm text-slate-500">Issues Found</p>
			</div>

			<!-- CVEs Detected -->
			<div class="bg-white rounded-2xl p-5 sm:p-6 border border-slate-200/50 hover:shadow-lg hover:-translate-y-0.5 transition-all duration-200">
				<div class="flex items-start justify-between mb-4">
					<div class="w-11 h-11 sm:w-12 sm:h-12 bg-gradient-to-br from-red-500 to-red-600 rounded-xl flex items-center justify-center shadow-lg shadow-red-500/25">
						<svg class="w-5 h-5 sm:w-6 sm:h-6 text-white" fill="none" stroke="currentColor" viewBox="0 0 24 24">
							<path stroke-linecap="round" stroke-linejoin="round" stroke-width="2" d="M9 12.75L11.25 15 15 9.75m-3-7.036A11.959 11.959 0 013.598 6 11.99 11.99 0 003 9.749c0 5.592 3.824 10.29 9 11.623 5.176-1.332 9-6.03 9-11.622 0-1.31-.21-2.571-.598-3.751h-.152c-3.196 0-6.1-1.248-8.25-3.285z" />
						</svg>
					</div>
				</div>
				<p class="text-2xl sm:text-3xl font-bold text-red-600 mb-1">{issueAnalytics?.cveCount || 0}</p>
				<p class="text-sm text-slate-500">CVEs Detected</p>
			</div>

			<!-- Daily LLM Quota -->
			<div class="bg-white rounded-2xl p-5 sm:p-6 border border-slate-200/50 hover:shadow-lg hover:-translate-y-0.5 transition-all duration-200 {llmQuota?.exceeded ? 'border-red-300 bg-red-50/30' : llmQuota?.warning ? 'border-amber-300 bg-amber-50/30' : ''}">
				<div class="flex items-start justify-between mb-4">
					<div class="w-11 h-11 sm:w-12 sm:h-12 bg-gradient-to-br {llmQuota?.exceeded ? 'from-red-500 to-red-600' : llmQuota?.warning ? 'from-amber-500 to-amber-600' : 'from-emerald-500 to-emerald-600'} rounded-xl flex items-center justify-center shadow-lg {llmQuota?.exceeded ? 'shadow-red-500/25' : llmQuota?.warning ? 'shadow-amber-500/25' : 'shadow-emerald-500/25'}">
						<svg class="w-5 h-5 sm:w-6 sm:h-6 text-white" fill="none" stroke="currentColor" viewBox="0 0 24 24">
							<path stroke-linecap="round" stroke-linejoin="round" stroke-width="2" d="M12 8c-1.657 0-3 .895-3 2s1.343 2 3 2 3 .895 3 2-1.343 2-3 2m0-8c1.11 0 2.08.402 2.599 1M12 8V7m0 1v8m0 0v1m0-1c-1.11 0-2.08-.402-2.599-1M21 12a9 9 0 11-18 0 9 9 0 0118 0z" />
						</svg>
					</div>
					{#if llmQuota?.exceeded}
						<span class="inline-flex items-center gap-1 px-2 py-1 bg-red-100 text-red-700 rounded-lg text-xs font-medium">
							Limit Reached
						</span>
					{:else if llmQuota?.warning}
						<span class="inline-flex items-center gap-1 px-2 py-1 bg-amber-100 text-amber-700 rounded-lg text-xs font-medium">
							{Math.round(llmQuota.percentUsed)}% used
						</span>
					{:else}
						<span class="text-xs text-slate-400">today</span>
					{/if}
				</div>
				<p class="text-2xl sm:text-3xl font-bold {llmQuota?.exceeded ? 'text-red-600' : 'text-slate-900'} mb-1">
					{formatCost(llmQuota?.usedToday || 0)}
				</p>
				<p class="text-sm text-slate-500 mb-3">of {formatCost(llmQuota?.dailyLimit || 0)} daily limit</p>
				<!-- Progress bar -->
				<div class="w-full bg-slate-200 rounded-full h-2">
					<div
						class="h-2 rounded-full transition-all duration-500 {llmQuota?.exceeded ? 'bg-red-500' : llmQuota?.warning ? 'bg-amber-500' : 'bg-emerald-500'}"
						style="width: {Math.min(llmQuota?.percentUsed || 0, 100)}%"
					></div>
				</div>
			</div>
		</div>

		<!-- Charts Row -->
		<div class="grid grid-cols-1 lg:grid-cols-2 gap-4 sm:gap-6 mb-6 lg:mb-8">
			<!-- Issues by Severity -->
			<div class="bg-white rounded-2xl border border-slate-200/50 p-6 hover:shadow-lg transition-shadow">
				<div class="flex items-center justify-between mb-6">
					<h3 class="text-lg font-semibold text-slate-900">Issues by Severity</h3>
				</div>
				<div class="space-y-5">
					{#each [
						{ label: 'Critical', key: 'critical', gradient: 'from-red-600 to-red-500' },
						{ label: 'High', key: 'high', gradient: 'from-orange-500 to-orange-400' },
						{ label: 'Medium', key: 'medium', gradient: 'from-amber-500 to-amber-400' },
						{ label: 'Low', key: 'low', gradient: 'from-emerald-500 to-emerald-400' }
					] as item}
						{@const count = stats?.issuesBySeverity?.[item.key] || 0}
						{@const total = Object.values(stats?.issuesBySeverity || {}).reduce((a, b) => a + b, 0) || 1}
						{@const percent = Math.round((count / total) * 100)}
						<div>
							<div class="flex justify-between text-sm mb-2">
								<span class="text-slate-700 font-medium">{item.label}</span>
								<span class="font-semibold text-slate-900">{count}</span>
							</div>
							<div class="w-full bg-slate-100 rounded-full h-2.5">
								<div class="bg-gradient-to-r {item.gradient} h-2.5 rounded-full transition-all duration-500" style="width: {percent}%"></div>
							</div>
						</div>
					{/each}
				</div>
			</div>

			<!-- Issues by Source -->
			<div class="bg-white rounded-2xl border border-slate-200/50 p-6 hover:shadow-lg transition-shadow">
				<div class="flex items-center justify-between mb-6">
					<h3 class="text-lg font-semibold text-slate-900">Issues by Source</h3>
				</div>
				<div class="grid grid-cols-2 gap-4">
					<div class="p-5 bg-gradient-to-br from-primary/10 to-secondary/10 rounded-xl text-center border border-primary/20">
						<div class="w-12 h-12 bg-gradient-to-br from-primary to-secondary rounded-xl flex items-center justify-center mx-auto mb-3 shadow-lg shadow-primary/25">
							<svg class="w-6 h-6 text-white" fill="none" stroke="currentColor" viewBox="0 0 24 24">
								<path stroke-linecap="round" stroke-linejoin="round" stroke-width="2" d="M9.75 17L9 20l-1 1h8l-1-1-.75-3M3 13h18M5 17h14a2 2 0 002-2V5a2 2 0 00-2-2H5a2 2 0 00-2 2v10a2 2 0 002 2z" />
							</svg>
						</div>
						<p class="text-3xl font-bold text-slate-900 mb-1">{issueAnalytics?.bySource?.AI || 0}</p>
						<p class="text-sm text-slate-600 font-medium">AI Analysis</p>
					</div>
					<div class="p-5 bg-slate-50 rounded-xl text-center border border-slate-200">
						<div class="w-12 h-12 bg-gradient-to-br from-slate-500 to-slate-600 rounded-xl flex items-center justify-center mx-auto mb-3 shadow-lg shadow-slate-500/25">
							<svg class="w-6 h-6 text-white" fill="none" stroke="currentColor" viewBox="0 0 24 24">
								<path stroke-linecap="round" stroke-linejoin="round" stroke-width="2" d="M10 20l4-16m4 4l4 4-4 4M6 16l-4-4 4-4" />
							</svg>
						</div>
						<p class="text-3xl font-bold text-slate-900 mb-1">{issueAnalytics?.bySource?.STATIC || 0}</p>
						<p class="text-sm text-slate-600 font-medium">Static Analysis</p>
					</div>
				</div>
			</div>
		</div>

		<!-- Reviews Over Time Chart -->
		<div class="bg-white rounded-2xl border border-slate-200/50 p-6 mb-6 lg:mb-8 hover:shadow-lg transition-shadow">
			<div class="flex items-center justify-between mb-6">
				<h3 class="text-lg font-semibold text-slate-900">Reviews Over Time</h3>
				<span class="text-sm text-slate-500">Last {period} days</span>
			</div>
			{#if trends?.dailyReviews && trends.dailyReviews.length > 0}
				{@const maxReviews = Math.max(...trends.dailyReviews.map(d => d[1]), 1)}
				<div class="h-48 flex items-end gap-1">
					{#each trends.dailyReviews as [date, count]}
						{@const height = (count / maxReviews) * 100}
						{@const formattedDate = new Date(date).toLocaleDateString('en-US', { month: 'short', day: 'numeric' })}
						<div class="flex-1 group relative">
							<div
								class="w-full bg-gradient-to-t from-primary to-secondary rounded-t-sm transition-all duration-200 hover:from-primary/80 hover:to-secondary/80 cursor-pointer"
								style="height: {Math.max(height, 2)}%"
							></div>
							<div class="absolute bottom-full left-1/2 -translate-x-1/2 mb-2 px-2 py-1 bg-slate-800 text-white text-xs rounded opacity-0 group-hover:opacity-100 transition-opacity whitespace-nowrap pointer-events-none z-10">
								{formattedDate}: {count} review{count !== 1 ? 's' : ''}
							</div>
						</div>
					{/each}
				</div>
				<div class="flex justify-between mt-3 text-xs text-slate-400">
					<span>{new Date(trends.dailyReviews[0]?.[0]).toLocaleDateString('en-US', { month: 'short', day: 'numeric' })}</span>
					<span>{new Date(trends.dailyReviews[trends.dailyReviews.length - 1]?.[0]).toLocaleDateString('en-US', { month: 'short', day: 'numeric' })}</span>
				</div>
			{:else}
				<div class="h-48 flex items-center justify-center">
					<p class="text-slate-400 text-sm">No review data available</p>
				</div>
			{/if}
		</div>

		<!-- Monthly Trend Analysis -->
		<div class="bg-white rounded-2xl border border-slate-200/50 p-6 mb-6 lg:mb-8 hover:shadow-lg transition-shadow">
			<div class="flex items-center justify-between mb-6">
				<div class="flex items-center gap-3">
					<h3 class="text-lg font-semibold text-slate-900">Code Quality Trend</h3>
					{#if monthlyTrends}
						{@const trendInfo = getTrendInfo(monthlyTrends.trend)}
						<span class="inline-flex items-center gap-1.5 px-3 py-1 {trendInfo.color} rounded-full text-xs font-semibold">
							<span>{trendInfo.icon}</span>
							{trendInfo.label}
						</span>
					{/if}
				</div>
				<span class="text-sm text-slate-500">Last 6 months</span>
			</div>

			{#if monthlyTrends?.monthlyData && monthlyTrends.monthlyData.length > 0}
				<!-- Summary Stats -->
				<div class="grid grid-cols-3 gap-4 mb-6 p-4 bg-slate-50 rounded-xl">
					<div class="text-center">
						<p class="text-2xl font-bold text-slate-900">{monthlyTrends.totalReviews}</p>
						<p class="text-xs text-slate-500">Total Reviews</p>
					</div>
					<div class="text-center border-x border-slate-200">
						<p class="text-2xl font-bold text-slate-900">{monthlyTrends.averageIssuesPerReview}</p>
						<p class="text-xs text-slate-500">Avg Issues/Review</p>
					</div>
					<div class="text-center">
						<p class="text-2xl font-bold {monthlyTrends.totalCriticalIssues > 0 ? 'text-red-600' : 'text-emerald-600'}">{monthlyTrends.totalCriticalIssues}</p>
						<p class="text-xs text-slate-500">Critical Issues</p>
					</div>
				</div>

				<!-- Monthly Chart -->
				{@const maxReviews = Math.max(...monthlyTrends.monthlyData.map(m => m.reviewCount), 1)}
				{@const maxCritical = Math.max(...monthlyTrends.monthlyData.map(m => m.criticalIssues + m.highIssues), 1)}
				<div class="space-y-3">
					{#each monthlyTrends.monthlyData as month}
						{@const reviewPercent = (month.reviewCount / maxReviews) * 100}
						{@const issuePercent = ((month.criticalIssues + month.highIssues) / maxCritical) * 100}
						<div class="group">
							<div class="flex items-center justify-between text-sm mb-1.5">
								<span class="font-medium text-slate-700 w-16">{formatMonth(month.month)}</span>
								<div class="flex items-center gap-4 text-xs text-slate-500">
									<span>{month.reviewCount} reviews</span>
									<span class="text-red-600">{month.criticalIssues} critical</span>
									<span class="text-orange-500">{month.highIssues} high</span>
								</div>
							</div>
							<div class="flex gap-2">
								<!-- Reviews bar -->
								<div class="flex-1 bg-slate-100 rounded-full h-2">
									<div
										class="bg-gradient-to-r from-primary to-secondary h-2 rounded-full transition-all duration-500"
										style="width: {reviewPercent}%"
									></div>
								</div>
								<!-- Issues bar -->
								<div class="w-24 bg-slate-100 rounded-full h-2">
									<div
										class="bg-gradient-to-r from-red-500 to-orange-400 h-2 rounded-full transition-all duration-500"
										style="width: {issuePercent}%"
									></div>
								</div>
							</div>
						</div>
					{/each}
				</div>

				<!-- Legend -->
				<div class="flex items-center justify-center gap-6 mt-4 pt-4 border-t border-slate-100">
					<div class="flex items-center gap-2">
						<div class="w-3 h-3 rounded-full bg-gradient-to-r from-primary to-secondary"></div>
						<span class="text-xs text-slate-500">Reviews</span>
					</div>
					<div class="flex items-center gap-2">
						<div class="w-3 h-3 rounded-full bg-gradient-to-r from-red-500 to-orange-400"></div>
						<span class="text-xs text-slate-500">Critical + High Issues</span>
					</div>
				</div>
			{:else}
				<div class="h-48 flex items-center justify-center">
					<div class="text-center">
						<div class="w-16 h-16 bg-slate-100 rounded-2xl flex items-center justify-center mx-auto mb-4">
							<svg class="w-8 h-8 text-slate-400" fill="none" stroke="currentColor" viewBox="0 0 24 24">
								<path stroke-linecap="round" stroke-linejoin="round" stroke-width="2" d="M9 19v-6a2 2 0 00-2-2H5a2 2 0 00-2 2v6a2 2 0 002 2h2a2 2 0 002-2zm0 0V9a2 2 0 012-2h2a2 2 0 012 2v10m-6 0a2 2 0 002 2h2a2 2 0 002-2m0 0V5a2 2 0 012-2h2a2 2 0 012 2v14a2 2 0 01-2 2h-2a2 2 0 01-2-2z" />
							</svg>
						</div>
						<p class="text-slate-500 font-medium">Not enough data for trends</p>
						<p class="text-slate-400 text-sm mt-1">Complete more reviews to see monthly trends</p>
					</div>
				</div>
			{/if}
		</div>

		<!-- Middle Row: Model Usage and Top Issues -->
		<div class="grid grid-cols-1 md:grid-cols-2 gap-4 sm:gap-6 mb-6 lg:mb-8">
			<!-- LLM/Model Usage -->
			<div class="bg-white rounded-2xl border border-slate-200/50 p-6 hover:shadow-lg transition-shadow">
				<h3 class="text-lg font-semibold text-slate-900 mb-6">Model Usage</h3>
				<div class="space-y-4">
					{#each Object.entries(llmUsage?.costByProvider || {}) as [provider, cost], i}
						{@const colors = ['from-primary to-secondary', 'from-stone-600 to-stone-500', 'from-stone-500 to-stone-400', 'from-stone-400 to-stone-300']}
						<div>
							<div class="flex justify-between text-sm mb-2">
								<div class="flex items-center gap-2">
									<div class="w-3 h-3 rounded-full bg-gradient-to-r {colors[i % colors.length]}"></div>
									<span class="text-slate-700 font-medium capitalize">{provider}</span>
								</div>
								<span class="font-semibold text-slate-900">${Number(cost).toFixed(2)}</span>
							</div>
						</div>
					{:else}
						<p class="text-slate-400 text-sm text-center py-4">No usage data</p>
					{/each}
				</div>
				<div class="mt-6 pt-4 border-t border-slate-100">
					<div class="grid grid-cols-2 gap-4 text-center">
						<div>
							<p class="text-xs text-slate-500 mb-1">Input Tokens</p>
							<p class="text-lg font-bold text-slate-900">{formatNumber(llmUsage?.totalInputTokens || 0)}</p>
						</div>
						<div>
							<p class="text-xs text-slate-500 mb-1">Output Tokens</p>
							<p class="text-lg font-bold text-slate-900">{formatNumber(llmUsage?.totalOutputTokens || 0)}</p>
						</div>
					</div>
				</div>
			</div>

			<!-- Top Issue Types -->
			<div class="bg-white rounded-2xl border border-slate-200/50 p-6 hover:shadow-lg transition-shadow">
				<h3 class="text-lg font-semibold text-slate-900 mb-6">Top Issue Types</h3>
				{#if topIssueTypes.length > 0}
					<div class="space-y-3">
						{#each topIssueTypes as issueType}
							<div class="flex items-center justify-between p-3 bg-slate-50 rounded-xl hover:bg-slate-100 transition-colors">
								<div class="flex items-center gap-3">
									<div class="w-8 h-8 bg-stone-100 rounded-lg flex items-center justify-center">
										<svg class="w-4 h-4 text-stone-600" fill="none" stroke="currentColor" viewBox="0 0 24 24">
											<path stroke-linecap="round" stroke-linejoin="round" stroke-width="2" d={getIssueIcon(issueType.category)} />
										</svg>
									</div>
									<span class="text-sm text-slate-700">{issueType.category.replace(/_/g, ' ')}</span>
								</div>
								<span class="text-sm font-bold text-slate-900">{issueType.count}</span>
							</div>
						{/each}
					</div>
				{:else}
					<p class="text-slate-400 text-sm text-center py-4">No issue type data</p>
				{/if}
			</div>
		</div>

		<!-- Top Repositories Table -->
		<div class="bg-white rounded-2xl border border-slate-200/50 overflow-hidden hover:shadow-lg transition-shadow">
			<div class="px-6 py-5 border-b border-slate-100 flex items-center justify-between">
				<h3 class="text-lg font-semibold text-slate-900">Top Repositories by Reviews</h3>
			</div>
			{#if topRepositories.length > 0}
				<div class="overflow-x-auto">
					<table class="w-full">
						<thead>
							<tr class="bg-slate-50/50">
								<th class="px-6 py-4 text-left text-xs font-semibold text-slate-500 uppercase tracking-wider">Repository</th>
								<th class="px-6 py-4 text-left text-xs font-semibold text-slate-500 uppercase tracking-wider">Reviews</th>
								<th class="px-6 py-4 text-left text-xs font-semibold text-slate-500 uppercase tracking-wider">Issues</th>
								<th class="px-6 py-4 text-left text-xs font-semibold text-slate-500 uppercase tracking-wider">Critical</th>
							</tr>
						</thead>
						<tbody class="divide-y divide-slate-100">
							{#each topRepositories as repo}
								<tr class="hover:bg-slate-50/50 transition-colors">
									<td class="px-6 py-4">
										<div class="flex items-center gap-3">
											<div class="w-9 h-9 bg-stone-100 rounded-lg flex items-center justify-center">
												<svg class="w-4 h-4 text-stone-600" fill="none" stroke="currentColor" viewBox="0 0 24 24">
													<path stroke-linecap="round" stroke-linejoin="round" stroke-width="2" d="M3 7v10a2 2 0 002 2h14a2 2 0 002-2V9a2 2 0 00-2-2h-6l-2-2H5a2 2 0 00-2 2z" />
												</svg>
											</div>
											<span class="font-medium text-slate-900">{repo.repositoryName}</span>
										</div>
									</td>
									<td class="px-6 py-4 text-slate-600 font-medium">{repo.reviewCount}</td>
									<td class="px-6 py-4 text-slate-600">{repo.issueCount}</td>
									<td class="px-6 py-4">
										{#if repo.criticalCount > 0}
											<span class="px-2.5 py-1 bg-red-100 text-red-700 rounded-lg text-xs font-semibold">{repo.criticalCount}</span>
										{:else}
											<span class="px-2.5 py-1 bg-stone-100 text-stone-600 rounded-lg text-xs font-semibold">0</span>
										{/if}
									</td>
								</tr>
							{/each}
						</tbody>
					</table>
				</div>
			{:else}
				<div class="p-8 text-center">
					<div class="w-16 h-16 bg-slate-100 rounded-2xl flex items-center justify-center mx-auto mb-4">
						<svg class="w-8 h-8 text-slate-400" fill="none" stroke="currentColor" viewBox="0 0 24 24">
							<path stroke-linecap="round" stroke-linejoin="round" stroke-width="2" d="M3 7v10a2 2 0 002 2h14a2 2 0 002-2V9a2 2 0 00-2-2h-6l-2-2H5a2 2 0 00-2 2z" />
						</svg>
					</div>
					<p class="text-slate-500 font-medium">No repository data available</p>
					<p class="text-slate-400 text-sm mt-1">Submit a PR review to see repository analytics</p>
				</div>
			{/if}
		</div>
	{/if}
</div>
