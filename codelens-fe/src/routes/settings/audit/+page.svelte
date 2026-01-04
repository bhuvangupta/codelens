<script lang="ts">
	import { onMount } from 'svelte';
	import { audit } from '$lib/api/client';
	import type { AuditLogEntry } from '$lib/api/client';

	let logs: AuditLogEntry[] = [];
	let loading = true;
	let error: string | null = null;
	let page = 0;
	let hasMore = true;
	let loadingMore = false;

	// Filters
	let actionFilter = '';
	let userFilter = '';
	let dateRange = '7'; // days

	const actionTypes = [
		{ value: '', label: 'All Actions' },
		{ value: 'REVIEW_SUBMITTED', label: 'Review Submitted' },
		{ value: 'REVIEW_COMPLETED', label: 'Review Completed' },
		{ value: 'SETTINGS_CHANGED', label: 'Settings Changed' },
		{ value: 'MEMBER_INVITED', label: 'Member Invited' },
		{ value: 'MEMBER_REMOVED', label: 'Member Removed' },
		{ value: 'ROLE_CHANGED', label: 'Role Changed' },
		{ value: 'RULE_CREATED', label: 'Rule Created' },
		{ value: 'RULE_DELETED', label: 'Rule Deleted' },
		{ value: 'REPO_CONNECTED', label: 'Repository Connected' },
		{ value: 'REPO_DISCONNECTED', label: 'Repository Disconnected' }
	];

	onMount(async () => {
		await loadLogs();
	});

	async function loadLogs(reset = true) {
		try {
			if (reset) {
				loading = true;
				page = 0;
				logs = [];
			} else {
				loadingMore = true;
			}

			const result = await audit.getLogs({
				page,
				limit: 50,
				action: actionFilter || undefined,
				user: userFilter || undefined,
				days: parseInt(dateRange)
			});

			if (reset) {
				logs = result.logs;
			} else {
				logs = [...logs, ...result.logs];
			}
			hasMore = result.hasMore;
		} catch (e) {
			error = e instanceof Error ? e.message : 'Failed to load audit logs';
		} finally {
			loading = false;
			loadingMore = false;
		}
	}

	async function loadMore() {
		page++;
		await loadLogs(false);
	}

	function applyFilters() {
		loadLogs(true);
	}

	function formatDate(dateStr: string): string {
		const date = new Date(dateStr);
		return date.toLocaleDateString('en-US', {
			month: 'short',
			day: 'numeric',
			year: 'numeric',
			hour: '2-digit',
			minute: '2-digit'
		});
	}

	function getActionIcon(action: string): string {
		switch (action) {
			case 'REVIEW_SUBMITTED':
			case 'REVIEW_COMPLETED':
				return 'M9 5H7a2 2 0 00-2 2v12a2 2 0 002 2h10a2 2 0 002-2V7a2 2 0 00-2-2h-2M9 5a2 2 0 002 2h2a2 2 0 002-2M9 5a2 2 0 012-2h2a2 2 0 012 2m-6 9l2 2 4-4';
			case 'SETTINGS_CHANGED':
				return 'M10.325 4.317c.426-1.756 2.924-1.756 3.35 0a1.724 1.724 0 002.573 1.066c1.543-.94 3.31.826 2.37 2.37a1.724 1.724 0 001.065 2.572c1.756.426 1.756 2.924 0 3.35a1.724 1.724 0 00-1.066 2.573c.94 1.543-.826 3.31-2.37 2.37a1.724 1.724 0 00-2.572 1.065c-.426 1.756-2.924 1.756-3.35 0a1.724 1.724 0 00-2.573-1.066c-1.543.94-3.31-.826-2.37-2.37a1.724 1.724 0 00-1.065-2.572c-1.756-.426-1.756-2.924 0-3.35a1.724 1.724 0 001.066-2.573c-.94-1.543.826-3.31 2.37-2.37.996.608 2.296.07 2.572-1.065z';
			case 'MEMBER_INVITED':
			case 'MEMBER_REMOVED':
			case 'ROLE_CHANGED':
				return 'M12 4.354a4 4 0 110 5.292M15 21H3v-1a6 6 0 0112 0v1zm0 0h6v-1a6 6 0 00-9-5.197M13 7a4 4 0 11-8 0 4 4 0 018 0z';
			case 'RULE_CREATED':
			case 'RULE_DELETED':
				return 'M3 4a1 1 0 011-1h16a1 1 0 011 1v2.586a1 1 0 01-.293.707l-6.414 6.414a1 1 0 00-.293.707V17l-4 4v-6.586a1 1 0 00-.293-.707L3.293 7.293A1 1 0 013 6.586V4z';
			case 'REPO_CONNECTED':
			case 'REPO_DISCONNECTED':
				return 'M3 7v10a2 2 0 002 2h14a2 2 0 002-2V9a2 2 0 00-2-2h-6l-2-2H5a2 2 0 00-2 2z';
			default:
				return 'M13 16h-1v-4h-1m1-4h.01M21 12a9 9 0 11-18 0 9 9 0 0118 0z';
		}
	}

	function getActionColor(action: string): string {
		if (action.includes('REMOVED') || action.includes('DELETED')) {
			return 'text-red-600 bg-red-100';
		}
		if (action.includes('CREATED') || action.includes('INVITED') || action.includes('CONNECTED')) {
			return 'text-green-600 bg-green-100';
		}
		if (action.includes('CHANGED')) {
			return 'text-blue-600 bg-blue-100';
		}
		return 'text-gray-600 bg-gray-100';
	}

	function formatAction(action: string): string {
		return action.replace(/_/g, ' ').toLowerCase().replace(/\b\w/g, l => l.toUpperCase());
	}
</script>

<svelte:head>
	<title>Audit Log - Settings - CodeLens</title>
</svelte:head>

<div class="space-y-6">
	<!-- Header & Filters -->
	<div class="card p-4">
		<div class="flex flex-col sm:flex-row gap-4">
			<div class="flex-1">
				<label class="block text-sm font-medium text-gray-700 mb-1">Action Type</label>
				<select
					bind:value={actionFilter}
					on:change={applyFilters}
					class="w-full px-4 py-2 border border-gray-300 rounded-lg focus:ring-2 focus:ring-primary-500"
				>
					{#each actionTypes as type}
						<option value={type.value}>{type.label}</option>
					{/each}
				</select>
			</div>

			<div class="flex-1">
				<label class="block text-sm font-medium text-gray-700 mb-1">User</label>
				<input
					type="text"
					bind:value={userFilter}
					on:input={applyFilters}
					placeholder="Filter by user name or email"
					class="w-full px-4 py-2 border border-gray-300 rounded-lg focus:ring-2 focus:ring-primary-500"
				/>
			</div>

			<div class="sm:w-40">
				<label class="block text-sm font-medium text-gray-700 mb-1">Time Range</label>
				<select
					bind:value={dateRange}
					on:change={applyFilters}
					class="w-full px-4 py-2 border border-gray-300 rounded-lg focus:ring-2 focus:ring-primary-500"
				>
					<option value="1">Last 24 hours</option>
					<option value="7">Last 7 days</option>
					<option value="30">Last 30 days</option>
					<option value="90">Last 90 days</option>
				</select>
			</div>
		</div>
	</div>

	{#if error}
		<div class="p-4 bg-red-50 text-red-700 rounded-lg">
			{error}
			<button on:click={() => { error = null; loadLogs(); }} class="ml-2 underline">Retry</button>
		</div>
	{/if}

	<!-- Logs -->
	<div class="card">
		{#if loading}
			<div class="flex items-center justify-center py-12">
				<div class="animate-spin rounded-full h-8 w-8 border-b-2 border-primary-700"></div>
			</div>
		{:else if logs.length === 0}
			<div class="text-center py-12 text-gray-500">
				<svg class="w-12 h-12 mx-auto mb-4 text-gray-300" fill="none" stroke="currentColor" viewBox="0 0 24 24">
					<path stroke-linecap="round" stroke-linejoin="round" stroke-width="2" d="M9 5H7a2 2 0 00-2 2v12a2 2 0 002 2h10a2 2 0 002-2V7a2 2 0 00-2-2h-2M9 5a2 2 0 002 2h2a2 2 0 002-2M9 5a2 2 0 012-2h2a2 2 0 012 2" />
				</svg>
				<p>No audit logs found for the selected filters.</p>
			</div>
		{:else}
			<div class="divide-y divide-gray-100">
				{#each logs as log}
					<div class="p-4 hover:bg-gray-50">
						<div class="flex items-start gap-4">
							<div class="p-2 rounded-lg {getActionColor(log.action)}">
								<svg class="w-5 h-5" fill="none" stroke="currentColor" viewBox="0 0 24 24">
									<path stroke-linecap="round" stroke-linejoin="round" stroke-width="2" d={getActionIcon(log.action)} />
								</svg>
							</div>

							<div class="flex-1 min-w-0">
								<div class="flex items-center gap-2 mb-1">
									<span class="font-medium text-gray-900">{formatAction(log.action)}</span>
									<span class="text-gray-400">•</span>
									<span class="text-sm text-gray-500">{formatDate(log.timestamp)}</span>
								</div>

								<p class="text-sm text-gray-600">{log.description}</p>

								<div class="flex items-center gap-3 mt-2">
									{#if log.userAvatar}
										<img src={log.userAvatar} alt={log.userName} class="w-5 h-5 rounded-full" />
									{:else}
										<div class="w-5 h-5 rounded-full bg-gray-200 flex items-center justify-center text-xs text-gray-600">
											{log.userName.charAt(0).toUpperCase()}
										</div>
									{/if}
									<span class="text-sm text-gray-500">{log.userName}</span>

									{#if log.ipAddress}
										<span class="text-xs text-gray-400">IP: {log.ipAddress}</span>
									{/if}
								</div>

								{#if log.metadata && Object.keys(log.metadata).length > 0}
									<div class="mt-2 p-2 bg-gray-50 rounded text-xs font-mono text-gray-600">
										{JSON.stringify(log.metadata, null, 2)}
									</div>
								{/if}
							</div>
						</div>
					</div>
				{/each}
			</div>

			{#if hasMore}
				<div class="p-4 border-t border-gray-100">
					<button
						on:click={loadMore}
						disabled={loadingMore}
						class="w-full py-2 text-primary-700 hover:bg-primary-50 rounded-lg font-medium disabled:opacity-50"
					>
						{#if loadingMore}
							Loading more...
						{:else}
							Load More
						{/if}
					</button>
				</div>
			{/if}
		{/if}
	</div>

	<!-- Info -->
	<div class="text-sm text-gray-500">
		<p>Audit logs are retained for 90 days. For compliance exports, contact support.</p>
	</div>
</div>
