<script lang="ts">
	import { onMount, onDestroy, tick } from 'svelte';
	import { analytics } from '$lib/api/client';
	import type { DeveloperStats, DeveloperSummaryStats, SizeDistribution, FeedbackStats, WeeklySummary, DailyActivity } from '$lib/api/client';
	import { Chart, LineController, LineElement, PointElement, LinearScale, CategoryScale, Filler, Tooltip, Legend } from 'chart.js';

	// Register Chart.js components
	Chart.register(LineController, LineElement, PointElement, LinearScale, CategoryScale, Filler, Tooltip, Legend);

	let loading = true;
	let error: string | null = null;
	let days = 30;

	// Data
	let leaderboard: DeveloperStats[] = [];
	let summary: DeveloperSummaryStats | null = null;
	let prSizes: SizeDistribution[] = [];
	let feedback: FeedbackStats | null = null;
	let weeklySummary: WeeklySummary | null = null;
	let loadingWeeklySummary = false;
	let dailyActivity: DailyActivity[] = [];
	let activityError: string | null = null;

	// Chart
	let activityChartCanvas: HTMLCanvasElement;
	let activityChart: Chart | null = null;

	async function loadData() {
		loading = true;
		error = null;
		try {
			const [leaderboardData, summaryData, prSizeData, feedbackData] = await Promise.all([
				analytics.getDeveloperLeaderboard(days, 10),
				analytics.getDeveloperSummary(days),
				analytics.getPrSizeDistribution(days),
				analytics.getFeedbackStats(days)
			]);
			leaderboard = leaderboardData;
			summary = summaryData;
			prSizes = prSizeData;
			feedback = feedbackData;
		} catch (e) {
			error = e instanceof Error ? e.message : 'Failed to load data';
		} finally {
			loading = false;
		}
	}

	async function loadDailyActivity() {
		activityError = null;
		try {
			// Get current user's activity
			const stats = await analytics.getMyDeveloperStats(days);
			if (stats?.userId) {
				dailyActivity = await analytics.getDeveloperActivity(stats.userId, days);
				// Wait for DOM to update before rendering chart
				await tick();
				renderActivityChart();
			}
		} catch (e: unknown) {
			console.error('Failed to load daily activity:', e);
			if (e instanceof Error && e.message === 'Unauthorized') {
				activityError = 'Please log in to view your activity';
			} else {
				activityError = 'Failed to load activity data';
			}
		}
	}

	function renderActivityChart() {
		if (!activityChartCanvas || dailyActivity.length === 0) return;

		// Destroy existing chart
		if (activityChart) {
			activityChart.destroy();
		}

		const labels = dailyActivity.map(d => {
			const date = new Date(d.date);
			return date.toLocaleDateString('en-US', { month: 'short', day: 'numeric' });
		});

		activityChart = new Chart(activityChartCanvas, {
			type: 'line',
			data: {
				labels,
				datasets: [
					{
						label: 'Reviews',
						data: dailyActivity.map(d => d.reviewCount),
						borderColor: 'rgb(99, 102, 241)',
						backgroundColor: 'rgba(99, 102, 241, 0.1)',
						fill: true,
						tension: 0.3,
						pointRadius: 3,
						pointHoverRadius: 5
					},
					{
						label: 'Lines (K)',
						data: dailyActivity.map(d => Math.round(d.linesReviewed / 1000 * 10) / 10),
						borderColor: 'rgb(16, 185, 129)',
						backgroundColor: 'rgba(16, 185, 129, 0.1)',
						fill: true,
						tension: 0.3,
						pointRadius: 3,
						pointHoverRadius: 5,
						yAxisID: 'y1'
					}
				]
			},
			options: {
				responsive: true,
				maintainAspectRatio: false,
				interaction: {
					mode: 'index',
					intersect: false
				},
				plugins: {
					legend: {
						position: 'top',
						labels: {
							usePointStyle: true,
							boxWidth: 6
						}
					},
					tooltip: {
						backgroundColor: 'rgba(0, 0, 0, 0.8)',
						padding: 12,
						titleFont: { size: 13 },
						bodyFont: { size: 12 }
					}
				},
				scales: {
					x: {
						grid: {
							display: false
						},
						ticks: {
							maxRotation: 0,
							autoSkip: true,
							maxTicksLimit: 10
						}
					},
					y: {
						position: 'left',
						beginAtZero: true,
						title: {
							display: true,
							text: 'Reviews'
						},
						grid: {
							color: 'rgba(0, 0, 0, 0.05)'
						}
					},
					y1: {
						position: 'right',
						beginAtZero: true,
						title: {
							display: true,
							text: 'Lines (K)'
						},
						grid: {
							display: false
						}
					}
				}
			}
		});
	}

	onDestroy(() => {
		if (activityChart) {
			activityChart.destroy();
		}
	});

	async function loadWeeklySummary() {
		loadingWeeklySummary = true;
		try {
			weeklySummary = await analytics.getMyWeeklySummary(7);
		} catch (e) {
			console.error('Failed to load weekly summary:', e);
		} finally {
			loadingWeeklySummary = false;
		}
	}

	onMount(() => {
		loadData();
		loadWeeklySummary();
		loadDailyActivity();
	});

	function formatNumber(num: number): string {
		if (num >= 1000000) return (num / 1000000).toFixed(1) + 'M';
		if (num >= 1000) return (num / 1000).toFixed(1) + 'K';
		return num.toString();
	}

	function formatTime(seconds: number): string {
		if (seconds < 60) return Math.round(seconds) + 's';
		if (seconds < 3600) return Math.round(seconds / 60) + 'm';
		return (seconds / 3600).toFixed(1) + 'h';
	}

	function handleDaysChange() {
		loadData();
		loadDailyActivity();
	}
</script>

<svelte:head>
	<title>Developer Activity - CodeLens</title>
</svelte:head>

<div class="p-6 max-w-7xl mx-auto">
	<!-- Header -->
	<div class="flex items-center justify-between mb-6">
		<div>
			<h1 class="text-2xl font-bold text-gray-900">Developer Activity</h1>
			<p class="text-gray-600">Track review activity and team performance</p>
		</div>
		<div class="flex items-center gap-3">
			<label class="text-sm text-gray-600">Time period:</label>
			<select
				bind:value={days}
				on:change={handleDaysChange}
				class="px-3 py-2 border border-gray-300 rounded-lg text-sm focus:ring-2 focus:ring-indigo-500"
			>
				<option value={7}>Last 7 days</option>
				<option value={30}>Last 30 days</option>
				<option value={90}>Last 90 days</option>
				<option value={365}>Last year</option>
			</select>
		</div>
	</div>

	{#if loading}
		<div class="flex items-center justify-center py-20">
			<div class="animate-spin rounded-full h-8 w-8 border-b-2 border-indigo-600"></div>
		</div>
	{:else if error}
		<div class="bg-red-50 border border-red-200 rounded-lg p-4 text-red-700">
			{error}
		</div>
	{:else}
		<!-- Summary Cards -->
		<div class="grid grid-cols-1 md:grid-cols-2 lg:grid-cols-5 gap-4 mb-8">
			<div class="bg-white rounded-xl border border-gray-200 p-5">
				<div class="flex items-center gap-3">
					<div class="w-10 h-10 bg-indigo-100 rounded-lg flex items-center justify-center">
						<svg class="w-5 h-5 text-indigo-600" fill="none" stroke="currentColor" viewBox="0 0 24 24">
							<path stroke-linecap="round" stroke-linejoin="round" stroke-width="2" d="M17 20h5v-2a3 3 0 00-5.356-1.857M17 20H7m10 0v-2c0-.656-.126-1.283-.356-1.857M7 20H2v-2a3 3 0 015.356-1.857M7 20v-2c0-.656.126-1.283.356-1.857m0 0a5.002 5.002 0 019.288 0M15 7a3 3 0 11-6 0 3 3 0 016 0zm6 3a2 2 0 11-4 0 2 2 0 014 0zM7 10a2 2 0 11-4 0 2 2 0 014 0z" />
						</svg>
					</div>
					<div>
						<p class="text-2xl font-bold text-gray-900">{summary?.totalDevelopers || 0}</p>
						<p class="text-sm text-gray-500">Active Reviewers</p>
					</div>
				</div>
			</div>

			<div class="bg-white rounded-xl border border-gray-200 p-5">
				<div class="flex items-center gap-3">
					<div class="w-10 h-10 bg-green-100 rounded-lg flex items-center justify-center">
						<svg class="w-5 h-5 text-green-600" fill="none" stroke="currentColor" viewBox="0 0 24 24">
							<path stroke-linecap="round" stroke-linejoin="round" stroke-width="2" d="M9 12l2 2 4-4m6 2a9 9 0 11-18 0 9 9 0 0118 0z" />
						</svg>
					</div>
					<div>
						<p class="text-2xl font-bold text-gray-900">{formatNumber(summary?.totalReviews || 0)}</p>
						<p class="text-sm text-gray-500">Reviews Completed</p>
					</div>
				</div>
			</div>

			<div class="bg-white rounded-xl border border-gray-200 p-5">
				<div class="flex items-center gap-3">
					<div class="w-10 h-10 bg-blue-100 rounded-lg flex items-center justify-center">
						<svg class="w-5 h-5 text-blue-600" fill="none" stroke="currentColor" viewBox="0 0 24 24">
							<path stroke-linecap="round" stroke-linejoin="round" stroke-width="2" d="M10 20l4-16m4 4l4 4-4 4M6 16l-4-4 4-4" />
						</svg>
					</div>
					<div>
						<p class="text-2xl font-bold text-gray-900">{formatNumber(summary?.totalLinesReviewed || 0)}</p>
						<p class="text-sm text-gray-500">Lines Reviewed</p>
					</div>
				</div>
			</div>

			<div class="bg-white rounded-xl border border-gray-200 p-5">
				<div class="flex items-center gap-3">
					<div class="w-10 h-10 bg-amber-100 rounded-lg flex items-center justify-center">
						<svg class="w-5 h-5 text-amber-600" fill="none" stroke="currentColor" viewBox="0 0 24 24">
							<path stroke-linecap="round" stroke-linejoin="round" stroke-width="2" d="M12 9v2m0 4h.01m-6.938 4h13.856c1.54 0 2.502-1.667 1.732-3L13.732 4c-.77-1.333-2.694-1.333-3.464 0L3.34 16c-.77 1.333.192 3 1.732 3z" />
						</svg>
					</div>
					<div>
						<p class="text-2xl font-bold text-gray-900">{formatNumber(summary?.totalIssuesFound || 0)}</p>
						<p class="text-sm text-gray-500">Issues Found</p>
					</div>
				</div>
			</div>

			<div class="bg-white rounded-xl border border-gray-200 p-5">
				<div class="flex items-center gap-3">
					<div class="w-10 h-10 bg-purple-100 rounded-lg flex items-center justify-center">
						<svg class="w-5 h-5 text-purple-600" fill="none" stroke="currentColor" viewBox="0 0 24 24">
							<path stroke-linecap="round" stroke-linejoin="round" stroke-width="2" d="M12 8v4l3 3m6-3a9 9 0 11-18 0 9 9 0 0118 0z" />
						</svg>
					</div>
					<div>
						<p class="text-2xl font-bold text-gray-900">{formatTime(summary?.avgCycleTimeSeconds || 0)}</p>
						<p class="text-sm text-gray-500">Avg Review Time</p>
					</div>
				</div>
			</div>
		</div>

		<!-- Weekly AI Summary -->
		{#if loadingWeeklySummary}
			<div class="bg-gradient-to-r from-indigo-50 to-purple-50 rounded-xl border border-indigo-100 p-6 mb-8">
				<div class="flex items-center gap-3">
					<div class="animate-spin rounded-full h-5 w-5 border-b-2 border-indigo-600"></div>
					<span class="text-gray-600">Generating your weekly summary...</span>
				</div>
			</div>
		{:else if weeklySummary && weeklySummary.summary}
			<div class="bg-gradient-to-r from-indigo-50 to-purple-50 rounded-xl border border-indigo-100 p-6 mb-8">
				<div class="flex items-start gap-4">
					<div class="w-10 h-10 bg-indigo-100 rounded-lg flex items-center justify-center flex-shrink-0">
						<svg class="w-5 h-5 text-indigo-600" fill="none" stroke="currentColor" viewBox="0 0 24 24">
							<path stroke-linecap="round" stroke-linejoin="round" stroke-width="2" d="M9.663 17h4.673M12 3v1m6.364 1.636l-.707.707M21 12h-1M4 12H3m3.343-5.657l-.707-.707m2.828 9.9a5 5 0 117.072 0l-.548.547A3.374 3.374 0 0014 18.469V19a2 2 0 11-4 0v-.531c0-.895-.356-1.754-.988-2.386l-.548-.547z" />
						</svg>
					</div>
					<div class="flex-1">
						<div class="flex items-center gap-2 mb-2">
							<h2 class="text-lg font-semibold text-gray-900">Your Weekly Summary</h2>
							<span class="text-xs bg-indigo-100 text-indigo-700 px-2 py-0.5 rounded-full">{weeklySummary.period}</span>
						</div>
						<p class="text-gray-700 mb-4">{weeklySummary.summary}</p>
						{#if weeklySummary.highlights && weeklySummary.highlights.length > 0}
							<div class="flex flex-wrap gap-2">
								{#each weeklySummary.highlights as highlight}
									<span class="text-xs bg-white text-gray-600 px-3 py-1.5 rounded-full border border-gray-200">
										{highlight}
									</span>
								{/each}
							</div>
						{/if}
						{#if weeklySummary.issueCategories && weeklySummary.issueCategories.length > 0}
							<div class="mt-4 pt-4 border-t border-indigo-100">
								<p class="text-xs text-gray-500 mb-2">Issue Categories Found:</p>
								<div class="flex flex-wrap gap-2">
									{#each weeklySummary.issueCategories.slice(0, 5) as cat}
										<span class="text-xs bg-amber-50 text-amber-700 px-2 py-1 rounded">
											{cat.category}: {cat.count}
										</span>
									{/each}
								</div>
							</div>
						{/if}
					</div>
				</div>
			</div>
		{/if}

		<!-- Daily Activity Chart -->
		<div class="bg-white rounded-xl border border-gray-200 p-6 mb-8">
			<div class="flex items-center justify-between mb-4">
				<div>
					<h2 class="text-lg font-semibold text-gray-900">Your Daily Activity</h2>
					<p class="text-sm text-gray-500">Reviews and lines reviewed over time</p>
				</div>
				<div class="flex items-center gap-2">
					<span class="flex items-center gap-1.5 text-xs text-gray-500">
						<span class="w-3 h-3 rounded-full bg-indigo-500"></span>
						Reviews
					</span>
					<span class="flex items-center gap-1.5 text-xs text-gray-500">
						<span class="w-3 h-3 rounded-full bg-emerald-500"></span>
						Lines
					</span>
				</div>
			</div>
			{#if activityError}
				<div class="h-64 flex items-center justify-center text-gray-500">
					<div class="text-center">
						<svg class="w-12 h-12 mx-auto mb-2 text-amber-400" fill="none" stroke="currentColor" viewBox="0 0 24 24">
							<path stroke-linecap="round" stroke-linejoin="round" stroke-width="2" d="M12 9v2m0 4h.01m-6.938 4h13.856c1.54 0 2.502-1.667 1.732-3L13.732 4c-.77-1.333-2.694-1.333-3.464 0L3.34 16c-.77 1.333.192 3 1.732 3z" />
						</svg>
						<p>{activityError}</p>
					</div>
				</div>
			{:else if dailyActivity.length === 0}
				<div class="h-64 flex items-center justify-center text-gray-500">
					<div class="text-center">
						<svg class="w-12 h-12 mx-auto mb-2 text-gray-300" fill="none" stroke="currentColor" viewBox="0 0 24 24">
							<path stroke-linecap="round" stroke-linejoin="round" stroke-width="2" d="M9 19v-6a2 2 0 00-2-2H5a2 2 0 00-2 2v6a2 2 0 002 2h2a2 2 0 002-2zm0 0V9a2 2 0 012-2h2a2 2 0 012 2v10m-6 0a2 2 0 002 2h2a2 2 0 002-2m0 0V5a2 2 0 012-2h2a2 2 0 012 2v14a2 2 0 01-2 2h-2a2 2 0 01-2-2z" />
						</svg>
						<p>No activity data for this period</p>
					</div>
				</div>
			{:else}
				<div class="h-64">
					<canvas bind:this={activityChartCanvas}></canvas>
				</div>
			{/if}
		</div>

		<div class="grid grid-cols-1 lg:grid-cols-3 gap-6">
			<!-- Leaderboard -->
			<div class="lg:col-span-2 bg-white rounded-xl border border-gray-200 p-6">
				<h2 class="text-lg font-semibold text-gray-900 mb-4">Developer Leaderboard</h2>
				{#if leaderboard.length === 0}
					<p class="text-gray-500 text-center py-8">No review data for this period</p>
				{:else}
					<div class="overflow-x-auto">
						<table class="w-full">
							<thead>
								<tr class="text-left text-sm text-gray-500 border-b border-gray-200">
									<th class="pb-3 font-medium">#</th>
									<th class="pb-3 font-medium">Developer</th>
									<th class="pb-3 font-medium text-right">Reviews</th>
									<th class="pb-3 font-medium text-right">Lines</th>
									<th class="pb-3 font-medium text-right">Issues</th>
									<th class="pb-3 font-medium text-right">Avg Time</th>
								</tr>
							</thead>
							<tbody>
								{#each leaderboard as dev, i}
									<tr class="border-b border-gray-100 last:border-0">
										<td class="py-3">
											{#if i === 0}
												<span class="text-xl">&#x1F947;</span>
											{:else if i === 1}
												<span class="text-xl">&#x1F948;</span>
											{:else if i === 2}
												<span class="text-xl">&#x1F949;</span>
											{:else}
												<span class="text-gray-400 font-medium">{i + 1}</span>
											{/if}
										</td>
										<td class="py-3">
											<div class="flex items-center gap-3">
												{#if dev.avatarUrl}
													<img src={dev.avatarUrl} alt="" class="w-8 h-8 rounded-full" />
												{:else}
													<div class="w-8 h-8 rounded-full bg-indigo-100 flex items-center justify-center text-indigo-600 font-medium text-sm">
														{dev.userName?.charAt(0)?.toUpperCase() || '?'}
													</div>
												{/if}
												<div>
													<p class="font-medium text-gray-900">{dev.userName || 'Unknown'}</p>
													{#if dev.userEmail}
														<p class="text-xs text-gray-500">{dev.userEmail}</p>
													{/if}
												</div>
											</div>
										</td>
										<td class="py-3 text-right font-medium text-gray-900">{dev.reviewCount}</td>
										<td class="py-3 text-right text-gray-600">{formatNumber(dev.linesReviewed)}</td>
										<td class="py-3 text-right">
											<span class="text-gray-600">{dev.issuesFound}</span>
											{#if dev.criticalIssues > 0}
												<span class="ml-1 px-1.5 py-0.5 text-xs bg-red-100 text-red-700 rounded">{dev.criticalIssues} crit</span>
											{/if}
										</td>
										<td class="py-3 text-right text-gray-600">{formatTime(dev.avgCycleTimeSeconds)}</td>
									</tr>
								{/each}
							</tbody>
						</table>
					</div>
				{/if}
			</div>

			<!-- Side Stats -->
			<div class="space-y-6">
				<!-- PR Size Distribution -->
				<div class="bg-white rounded-xl border border-gray-200 p-6">
					<h2 class="text-lg font-semibold text-gray-900 mb-4">PR Size Distribution</h2>
					{#if prSizes.length === 0}
						<p class="text-gray-500 text-center py-4">No data</p>
					{:else}
						<div class="space-y-3">
							{#each prSizes as size}
								{@const total = prSizes.reduce((sum, s) => sum + s.count, 0)}
								{@const percent = total > 0 ? (size.count / total) * 100 : 0}
								<div>
									<div class="flex justify-between text-sm mb-1">
										<span class="text-gray-600">{size.category}</span>
										<span class="font-medium text-gray-900">{size.count}</span>
									</div>
									<div class="w-full bg-gray-100 rounded-full h-2">
										<div
											class="h-2 rounded-full {size.category.startsWith('XS') ? 'bg-green-500' : size.category.startsWith('S') ? 'bg-blue-500' : size.category.startsWith('M') ? 'bg-yellow-500' : size.category.startsWith('L') ? 'bg-orange-500' : 'bg-red-500'}"
											style="width: {percent}%"
										></div>
									</div>
								</div>
							{/each}
						</div>
						<p class="mt-4 text-xs text-gray-500">Smaller PRs are easier to review and have fewer bugs</p>
					{/if}
				</div>

				<!-- Feedback Stats -->
				<div class="bg-white rounded-xl border border-gray-200 p-6">
					<h2 class="text-lg font-semibold text-gray-900 mb-4">Review Quality</h2>
					{#if feedback && feedback.totalWithFeedback > 0}
						<div class="space-y-4">
							<div>
								<div class="flex justify-between text-sm mb-1">
									<span class="text-gray-600">Helpful Rate</span>
									<span class="font-medium text-green-600">{feedback.helpfulRate.toFixed(1)}%</span>
								</div>
								<div class="w-full bg-gray-100 rounded-full h-2">
									<div class="bg-green-500 h-2 rounded-full" style="width: {feedback.helpfulRate}%"></div>
								</div>
							</div>
							<div>
								<div class="flex justify-between text-sm mb-1">
									<span class="text-gray-600">False Positive Rate</span>
									<span class="font-medium text-red-600">{feedback.falsePositiveRate.toFixed(1)}%</span>
								</div>
								<div class="w-full bg-gray-100 rounded-full h-2">
									<div class="bg-red-500 h-2 rounded-full" style="width: {feedback.falsePositiveRate}%"></div>
								</div>
							</div>
							<p class="text-xs text-gray-500">Based on {feedback.totalWithFeedback} feedback submissions</p>
						</div>
					{:else}
						<p class="text-gray-500 text-center py-4">No feedback data yet</p>
					{/if}
				</div>
			</div>
		</div>
	{/if}
</div>
