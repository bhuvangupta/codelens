<script lang="ts">
	import { onMount } from 'svelte';
	import { learning, settings } from '$lib/api/client';
	import type { LearningStats, SuppressedRule, PromptHint, RepositorySettings } from '$lib/api/client';

	let repositories: RepositorySettings[] = $state([]);
	let selectedRepoId = $state('');
	let stats: LearningStats | null = $state(null);
	let suppressedRules: SuppressedRule[] = $state([]);
	let hints: PromptHint[] = $state([]);
	let loading = $state(true);
	let loadingData = $state(false);
	let error: string | null = $state(null);
	let success: string | null = $state(null);

	// Add hint form
	let newHintText = $state('');
	let addingHint = $state(false);

	// Reset confirmation
	let showResetConfirm = $state(false);
	let resetting = $state(false);

	onMount(async () => {
		try {
			repositories = await settings.getRepositories();
			if (repositories.length > 0) {
				selectedRepoId = repositories[0].id;
				await loadLearningData();
			}
		} catch (e) {
			error = e instanceof Error ? e.message : 'Failed to load repositories';
		} finally {
			loading = false;
		}
	});

	async function loadLearningData() {
		if (!selectedRepoId) return;
		loadingData = true;
		error = null;
		try {
			const [statsData, rulesData, hintsData] = await Promise.all([
				learning.getStats(selectedRepoId),
				learning.getSuppressedRules(selectedRepoId),
				learning.getHints(selectedRepoId)
			]);
			stats = statsData;
			suppressedRules = rulesData;
			hints = hintsData;
		} catch (e) {
			error = e instanceof Error ? e.message : 'Failed to load learning data';
		} finally {
			loadingData = false;
		}
	}

	async function handleRepoChange() {
		await loadLearningData();
	}

	async function addHint() {
		if (!newHintText.trim() || !selectedRepoId) return;
		addingHint = true;
		error = null;
		try {
			await learning.addHint(selectedRepoId, newHintText.trim());
			newHintText = '';
			success = 'Hint added successfully';
			setTimeout(() => success = null, 3000);
			await loadLearningData();
		} catch (e) {
			error = e instanceof Error ? e.message : 'Failed to add hint';
		} finally {
			addingHint = false;
		}
	}

	async function toggleHint(hintId: string, active: boolean) {
		if (!selectedRepoId) return;
		try {
			await learning.toggleHint(selectedRepoId, hintId, active);
			await loadLearningData();
		} catch (e) {
			error = e instanceof Error ? e.message : 'Failed to update hint';
		}
	}

	async function deleteHint(hintId: string) {
		if (!selectedRepoId || !confirm('Are you sure you want to delete this hint?')) return;
		try {
			await learning.deleteHint(selectedRepoId, hintId);
			success = 'Hint deleted';
			setTimeout(() => success = null, 3000);
			await loadLearningData();
		} catch (e) {
			error = e instanceof Error ? e.message : 'Failed to delete hint';
		}
	}

	async function unsuppressRule(ruleKey: string) {
		if (!selectedRepoId) return;
		try {
			await learning.unsuppressRule(selectedRepoId, ruleKey);
			success = 'Rule unsuppressed';
			setTimeout(() => success = null, 3000);
			await loadLearningData();
		} catch (e) {
			error = e instanceof Error ? e.message : 'Failed to unsuppress rule';
		}
	}

	async function resetLearning() {
		if (!selectedRepoId) return;
		resetting = true;
		try {
			await learning.resetLearning(selectedRepoId);
			showResetConfirm = false;
			success = 'Learning data has been reset';
			setTimeout(() => success = null, 3000);
			await loadLearningData();
		} catch (e) {
			error = e instanceof Error ? e.message : 'Failed to reset learning data';
		} finally {
			resetting = false;
		}
	}

	function getSourceBadge(source: string): { class: string; label: string } {
		switch (source) {
			case 'USER_ADDED': return { class: 'bg-blue-100 text-blue-800', label: 'User Added' };
			case 'AUTO_LEARNED': return { class: 'bg-purple-100 text-purple-800', label: 'Auto Learned' };
			default: return { class: 'bg-gray-100 text-gray-800', label: source };
		}
	}
</script>

<svelte:head>
	<title>Learning - Settings - CodeLens</title>
</svelte:head>

<div class="space-y-8">
	<!-- Header -->
	<div>
		<h2 class="text-lg font-semibold">Repository Learning</h2>
		<p class="text-sm text-gray-600">View and manage what CodeLens has learned from your team's feedback. Rules are automatically suppressed or downgraded based on false positive reports.</p>
	</div>

	{#if success}
		<div class="p-4 bg-green-50 text-green-700 rounded-lg">
			{success}
		</div>
	{/if}

	{#if error}
		<div class="p-4 bg-red-50 text-red-700 rounded-lg">
			{error}
			<button onclick={() => error = null} class="ml-2 underline">Dismiss</button>
		</div>
	{/if}

	{#if loading}
		<div class="flex items-center justify-center py-12">
			<div class="animate-spin rounded-full h-8 w-8 border-b-2 border-primary-700"></div>
		</div>
	{:else if repositories.length === 0}
		<div class="card p-6 text-center text-gray-500">
			<p>No repositories found. Add repositories in the Repositories settings page.</p>
		</div>
	{:else}
		<!-- Repository Selector -->
		<div class="card p-4">
			<label for="repo-select" class="block text-sm font-medium text-gray-700 mb-2">Select Repository</label>
			<select
				id="repo-select"
				bind:value={selectedRepoId}
				onchange={handleRepoChange}
				class="w-full max-w-md px-4 py-2 border border-gray-300 rounded-lg focus:ring-2 focus:ring-primary-500 focus:border-primary-500"
			>
				{#each repositories as repo}
					<option value={repo.id}>{repo.fullName}</option>
				{/each}
			</select>
		</div>

		{#if loadingData}
			<div class="flex items-center justify-center py-12">
				<div class="animate-spin rounded-full h-8 w-8 border-b-2 border-primary-700"></div>
			</div>
		{:else if stats}
			<!-- Stats Cards -->
			<div class="grid grid-cols-2 md:grid-cols-4 gap-4">
				<div class="card p-5">
					<p class="text-sm text-gray-500 mb-1">Total Feedback</p>
					<p class="text-2xl font-bold text-gray-900">{stats.totalFeedbackCount}</p>
				</div>
				<div class="card p-5">
					<p class="text-sm text-gray-500 mb-1">Rules Suppressed</p>
					<p class="text-2xl font-bold text-gray-900">{stats.suppressedRulesCount}</p>
				</div>
				<div class="card p-5">
					<p class="text-sm text-gray-500 mb-1">Severity Overrides</p>
					<p class="text-2xl font-bold text-gray-900">{stats.severityOverridesCount}</p>
				</div>
				<div class="card p-5">
					<p class="text-sm text-gray-500 mb-1">Active Hints</p>
					<p class="text-2xl font-bold text-gray-900">{stats.activeHintsCount}</p>
				</div>
			</div>

			<!-- Suppressed Rules Section -->
			<div class="card">
				<div class="px-6 py-4 border-b border-gray-100">
					<h3 class="text-md font-semibold">Suppressed Rules</h3>
					<p class="text-sm text-gray-500">Rules that have been automatically suppressed due to repeated false positive reports.</p>
				</div>
				{#if suppressedRules.length === 0}
					<div class="text-center py-8 text-gray-500">
						<p>No suppressed rules for this repository.</p>
					</div>
				{:else}
					<div class="overflow-x-auto">
						<table class="w-full text-sm">
							<thead>
								<tr class="border-b border-gray-100">
									<th class="text-left px-6 py-3 text-gray-500 font-medium">Rule</th>
									<th class="text-left px-6 py-3 text-gray-500 font-medium">Analyzer</th>
									<th class="text-left px-6 py-3 text-gray-500 font-medium">Category</th>
									<th class="text-left px-6 py-3 text-gray-500 font-medium">FP Count</th>
									<th class="text-right px-6 py-3 text-gray-500 font-medium">Action</th>
								</tr>
							</thead>
							<tbody class="divide-y divide-gray-50">
								{#each suppressedRules as rule}
									<tr class="hover:bg-gray-50">
										<td class="px-6 py-3 font-mono text-gray-900">{rule.ruleId}</td>
										<td class="px-6 py-3 text-gray-700">{rule.analyzer}</td>
										<td class="px-6 py-3">
											{#if rule.category}
												<span class="px-2 py-1 bg-gray-100 text-gray-600 rounded text-xs">{rule.category}</span>
											{:else}
												<span class="text-gray-400">-</span>
											{/if}
										</td>
										<td class="px-6 py-3 text-gray-700">{rule.falsePositiveCount}</td>
										<td class="px-6 py-3 text-right">
											<button
												onclick={() => unsuppressRule(rule.ruleId + ':' + rule.analyzer)}
												class="px-3 py-1.5 text-xs bg-amber-50 text-amber-700 hover:bg-amber-100 rounded-lg transition-colors"
											>
												Unsuppress
											</button>
										</td>
									</tr>
								{/each}
							</tbody>
						</table>
					</div>
				{/if}
			</div>

			<!-- Prompt Hints Section -->
			<div class="card">
				<div class="px-6 py-4 border-b border-gray-100">
					<h3 class="text-md font-semibold">Prompt Hints</h3>
					<p class="text-sm text-gray-500">Custom instructions that are injected into the AI review prompt for this repository.</p>
				</div>
				{#if hints.length === 0}
					<div class="text-center py-8 text-gray-500">
						<p>No prompt hints for this repository.</p>
						<p class="text-sm mt-1">Add hints to guide the AI reviewer's behavior.</p>
					</div>
				{:else}
					<div class="divide-y divide-gray-50">
						{#each hints as hint}
							<div class="px-6 py-4 flex items-start justify-between gap-4 hover:bg-gray-50">
								<div class="flex-1 min-w-0">
									<p class="text-sm text-gray-900">{hint.hint}</p>
									<div class="flex items-center gap-2 mt-2">
										<span class="px-2 py-0.5 rounded text-xs font-medium {getSourceBadge(hint.source).class}">
											{getSourceBadge(hint.source).label}
										</span>
										{#if !hint.active}
											<span class="px-2 py-0.5 bg-gray-100 text-gray-500 rounded text-xs">Inactive</span>
										{/if}
									</div>
								</div>
								<div class="flex items-center gap-3 flex-shrink-0">
									<label class="relative inline-flex items-center cursor-pointer">
										<input
											type="checkbox"
											checked={hint.active}
											onchange={() => toggleHint(hint.id, !hint.active)}
											class="sr-only peer"
										/>
										<div class="w-11 h-6 bg-gray-200 peer-focus:outline-none peer-focus:ring-4 peer-focus:ring-primary-300 rounded-full peer peer-checked:after:translate-x-full peer-checked:after:border-white after:content-[''] after:absolute after:top-[2px] after:left-[2px] after:bg-white after:border-gray-300 after:border after:rounded-full after:h-5 after:w-5 after:transition-all peer-checked:bg-primary-700"></div>
									</label>
									<button
										onclick={() => deleteHint(hint.id)}
										class="p-2 text-gray-400 hover:text-red-600 transition-colors"
										title="Delete hint"
									>
										<svg class="w-5 h-5" fill="none" stroke="currentColor" viewBox="0 0 24 24">
											<path stroke-linecap="round" stroke-linejoin="round" stroke-width="2" d="M19 7l-.867 12.142A2 2 0 0116.138 21H7.862a2 2 0 01-1.995-1.858L5 7m5 4v6m4-6v6m1-10V4a1 1 0 00-1-1h-4a1 1 0 00-1 1v3M4 7h16" />
										</svg>
									</button>
								</div>
							</div>
						{/each}
					</div>
				{/if}

				<!-- Add Hint Form -->
				<div class="px-6 py-4 border-t border-gray-100 bg-gray-50">
					<form onsubmit={(e) => { e.preventDefault(); addHint(); }} class="flex gap-3">
						<input
							type="text"
							bind:value={newHintText}
							placeholder="e.g., This repo uses Lombok, ignore missing getters/setters"
							class="flex-1 px-4 py-2 border border-gray-300 rounded-lg focus:ring-2 focus:ring-primary-500 focus:border-primary-500 text-sm"
						/>
						<button
							type="submit"
							disabled={addingHint || !newHintText.trim()}
							class="px-4 py-2 bg-primary-700 text-white rounded-lg hover:bg-primary-800 disabled:opacity-50 text-sm whitespace-nowrap"
						>
							{#if addingHint}
								Adding...
							{:else}
								Add Hint
							{/if}
						</button>
					</form>
				</div>
			</div>

			<!-- Reset Section -->
			<div class="card p-6 bg-red-50 border-red-200">
				<div class="flex items-center justify-between">
					<div>
						<h3 class="text-sm font-medium text-red-900 mb-1">Reset Learning Data</h3>
						<p class="text-sm text-red-700">This will clear all suppressed rules, severity overrides, and auto-learned hints for this repository. User-added hints will be preserved.</p>
					</div>
					<button
						onclick={() => showResetConfirm = true}
						class="px-4 py-2 bg-red-600 text-white rounded-lg hover:bg-red-700 text-sm whitespace-nowrap ml-4"
					>
						Reset Learning
					</button>
				</div>
			</div>
		{/if}
	{/if}
</div>

<!-- Reset Confirmation Modal -->
{#if showResetConfirm}
	<div class="fixed inset-0 bg-black/50 flex items-center justify-center z-50" onclick={() => showResetConfirm = false} onkeydown={(e) => e.key === 'Escape' && (showResetConfirm = false)} role="dialog" aria-modal="true">
		<!-- svelte-ignore a11y_click_events_have_key_events -->
		<!-- svelte-ignore a11y_no_static_element_interactions -->
		<div class="bg-white rounded-2xl shadow-xl max-w-md w-full mx-4 p-6" onclick={(e) => e.stopPropagation()}>
			<div class="flex items-center gap-4 mb-4">
				<div class="w-12 h-12 bg-red-100 rounded-xl flex items-center justify-center">
					<svg class="w-6 h-6 text-red-600" fill="none" stroke="currentColor" viewBox="0 0 24 24">
						<path stroke-linecap="round" stroke-linejoin="round" stroke-width="2" d="M12 9v2m0 4h.01m-6.938 4h13.856c1.54 0 2.502-1.667 1.732-3L13.732 4c-.77-1.333-2.694-1.333-3.464 0L3.34 16c-.77 1.333.192 3 1.732 3z" />
					</svg>
				</div>
				<div>
					<h3 class="text-lg font-semibold text-gray-900">Reset Learning Data?</h3>
					<p class="text-gray-500 text-sm">This action cannot be undone.</p>
				</div>
			</div>
			<p class="text-gray-600 mb-6">
				All suppressed rules and severity overrides will be cleared. Auto-learned hints will be removed. The AI reviewer will start fresh for this repository.
			</p>
			<div class="flex justify-end gap-3">
				<button
					onclick={() => showResetConfirm = false}
					class="px-4 py-2 text-gray-600 hover:text-gray-900 border border-gray-200 hover:border-gray-300 rounded-lg transition-all"
				>
					Cancel
				</button>
				<button
					onclick={resetLearning}
					disabled={resetting}
					class="px-4 py-2 bg-red-600 hover:bg-red-700 text-white rounded-lg transition-all disabled:opacity-50"
				>
					{#if resetting}
						Resetting...
					{:else}
						Reset Learning
					{/if}
				</button>
			</div>
		</div>
	</div>
{/if}
