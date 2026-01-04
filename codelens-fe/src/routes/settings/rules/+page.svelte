<script lang="ts">
	import { onMount } from 'svelte';
	import { rules } from '$lib/api/client';
	import type { ReviewRule } from '$lib/api/client';

	let rulesList: ReviewRule[] = [];
	let loading = true;
	let error: string | null = null;
	let saving = false;
	let saveSuccess = false;

	// New rule form
	let showNewRule = false;
	let newRule = {
		name: '',
		description: '',
		severity: 'MEDIUM' as 'CRITICAL' | 'HIGH' | 'MEDIUM' | 'LOW',
		category: 'CUSTOM',
		pattern: '',
		suggestion: '',
		enabled: true,
		languages: [] as string[]
	};

	const languages = ['Java', 'JavaScript', 'TypeScript', 'Python', 'Go', 'Rust'];
	const severities = ['CRITICAL', 'HIGH', 'MEDIUM', 'LOW'];
	const categories = ['SECURITY', 'BUG', 'PERFORMANCE', 'STYLE', 'CUSTOM'];

	onMount(async () => {
		await loadRules();
	});

	async function loadRules() {
		try {
			loading = true;
			rulesList = await rules.getAll();
		} catch (e) {
			error = e instanceof Error ? e.message : 'Failed to load rules';
		} finally {
			loading = false;
		}
	}

	async function toggleRule(ruleId: string, enabled: boolean) {
		try {
			await rules.update(ruleId, { enabled });
			await loadRules();
		} catch (e) {
			error = e instanceof Error ? e.message : 'Failed to update rule';
		}
	}

	async function createRule() {
		if (!newRule.name.trim() || !newRule.pattern.trim()) return;

		try {
			saving = true;
			await rules.create(newRule);
			saveSuccess = true;
			showNewRule = false;
			newRule = {
				name: '',
				description: '',
				severity: 'MEDIUM',
				category: 'CUSTOM',
				pattern: '',
				suggestion: '',
				enabled: true,
				languages: []
			};
			await loadRules();
			setTimeout(() => saveSuccess = false, 3000);
		} catch (e) {
			error = e instanceof Error ? e.message : 'Failed to create rule';
		} finally {
			saving = false;
		}
	}

	async function deleteRule(ruleId: string, ruleName: string) {
		if (!confirm(`Are you sure you want to delete the rule "${ruleName}"?`)) return;

		try {
			await rules.delete(ruleId);
			await loadRules();
		} catch (e) {
			error = e instanceof Error ? e.message : 'Failed to delete rule';
		}
	}

	function getSeverityColor(severity: string): string {
		switch (severity) {
			case 'CRITICAL': return 'bg-red-100 text-red-800';
			case 'HIGH': return 'bg-orange-100 text-orange-800';
			case 'MEDIUM': return 'bg-yellow-100 text-yellow-800';
			case 'LOW': return 'bg-green-100 text-green-800';
			default: return 'bg-gray-100 text-gray-800';
		}
	}

	function toggleLanguage(lang: string) {
		if (newRule.languages.includes(lang)) {
			newRule.languages = newRule.languages.filter(l => l !== lang);
		} else {
			newRule.languages = [...newRule.languages, lang];
		}
	}
</script>

<svelte:head>
	<title>Rules - Settings - CodeLens</title>
</svelte:head>

<div class="space-y-8">
	<!-- Header -->
	<div class="flex items-center justify-between">
		<div>
			<h2 class="text-lg font-semibold">Custom Review Rules</h2>
			<p class="text-sm text-gray-600">Define custom patterns to flag during code reviews.</p>
		</div>
		<button
			on:click={() => showNewRule = !showNewRule}
			class="px-4 py-2 bg-primary-700 text-white rounded-lg hover:bg-primary-800 flex items-center gap-2"
		>
			<svg class="w-4 h-4" fill="none" stroke="currentColor" viewBox="0 0 24 24">
				<path stroke-linecap="round" stroke-linejoin="round" stroke-width="2" d="M12 6v6m0 0v6m0-6h6m-6 0H6" />
			</svg>
			New Rule
		</button>
	</div>

	{#if saveSuccess}
		<div class="p-4 bg-green-50 text-green-700 rounded-lg">
			Rule created successfully!
		</div>
	{/if}

	{#if error}
		<div class="p-4 bg-red-50 text-red-700 rounded-lg">
			{error}
			<button on:click={() => error = null} class="ml-2 underline">Dismiss</button>
		</div>
	{/if}

	<!-- New Rule Form -->
	{#if showNewRule}
		<div class="card p-6">
			<h3 class="text-lg font-semibold mb-4">Create Custom Rule</h3>

			<form on:submit|preventDefault={createRule} class="space-y-4">
				<div class="grid grid-cols-1 md:grid-cols-2 gap-4">
					<div>
						<label class="block text-sm font-medium text-gray-700 mb-1">Rule Name *</label>
						<input
							type="text"
							bind:value={newRule.name}
							placeholder="e.g., No console.log"
							class="w-full px-4 py-2 border border-gray-300 rounded-lg focus:ring-2 focus:ring-primary-500"
							required
						/>
					</div>

					<div>
						<label class="block text-sm font-medium text-gray-700 mb-1">Category</label>
						<select
							bind:value={newRule.category}
							class="w-full px-4 py-2 border border-gray-300 rounded-lg focus:ring-2 focus:ring-primary-500"
						>
							{#each categories as cat}
								<option value={cat}>{cat}</option>
							{/each}
						</select>
					</div>
				</div>

				<div>
					<label class="block text-sm font-medium text-gray-700 mb-1">Description</label>
					<input
						type="text"
						bind:value={newRule.description}
						placeholder="Brief description of what this rule checks for"
						class="w-full px-4 py-2 border border-gray-300 rounded-lg focus:ring-2 focus:ring-primary-500"
					/>
				</div>

				<div>
					<label class="block text-sm font-medium text-gray-700 mb-1">Pattern (Regex) *</label>
					<input
						type="text"
						bind:value={newRule.pattern}
						placeholder="e.g., console\.(log|debug|info)\("
						class="w-full px-4 py-2 border border-gray-300 rounded-lg focus:ring-2 focus:ring-primary-500 font-mono text-sm"
						required
					/>
					<p class="text-xs text-gray-500 mt-1">Regular expression to match in the code</p>
				</div>

				<div>
					<label class="block text-sm font-medium text-gray-700 mb-1">Suggestion</label>
					<input
						type="text"
						bind:value={newRule.suggestion}
						placeholder="e.g., Remove console statements before committing"
						class="w-full px-4 py-2 border border-gray-300 rounded-lg focus:ring-2 focus:ring-primary-500"
					/>
				</div>

				<div class="grid grid-cols-1 md:grid-cols-2 gap-4">
					<div>
						<label class="block text-sm font-medium text-gray-700 mb-1">Severity</label>
						<select
							bind:value={newRule.severity}
							class="w-full px-4 py-2 border border-gray-300 rounded-lg focus:ring-2 focus:ring-primary-500"
						>
							{#each severities as sev}
								<option value={sev}>{sev}</option>
							{/each}
						</select>
					</div>

					<div>
						<label class="block text-sm font-medium text-gray-700 mb-2">Languages</label>
						<div class="flex flex-wrap gap-2">
							{#each languages as lang}
								<button
									type="button"
									on:click={() => toggleLanguage(lang)}
									class="px-3 py-1 rounded-full text-sm border transition-colors"
									class:bg-primary-100={newRule.languages.includes(lang)}
									class:text-primary-700={newRule.languages.includes(lang)}
									class:border-primary-300={newRule.languages.includes(lang)}
									class:bg-white={!newRule.languages.includes(lang)}
									class:text-gray-600={!newRule.languages.includes(lang)}
									class:border-gray-300={!newRule.languages.includes(lang)}
								>
									{lang}
								</button>
							{/each}
						</div>
						<p class="text-xs text-gray-500 mt-1">Leave empty to apply to all languages</p>
					</div>
				</div>

				<div class="flex items-center justify-end gap-4 pt-4 border-t">
					<button
						type="button"
						on:click={() => showNewRule = false}
						class="px-4 py-2 text-gray-700 hover:bg-gray-100 rounded-lg"
					>
						Cancel
					</button>
					<button
						type="submit"
						disabled={saving || !newRule.name.trim() || !newRule.pattern.trim()}
						class="px-6 py-2 bg-primary-700 text-white rounded-lg hover:bg-primary-800 disabled:opacity-50"
					>
						{#if saving}
							Creating...
						{:else}
							Create Rule
						{/if}
					</button>
				</div>
			</form>
		</div>
	{/if}

	<!-- Rules List -->
	<div class="card">
		{#if loading}
			<div class="flex items-center justify-center py-12">
				<div class="animate-spin rounded-full h-8 w-8 border-b-2 border-primary-700"></div>
			</div>
		{:else if rulesList.length === 0}
			<div class="text-center py-12 text-gray-500">
				<svg class="w-12 h-12 mx-auto mb-4 text-gray-300" fill="none" stroke="currentColor" viewBox="0 0 24 24">
					<path stroke-linecap="round" stroke-linejoin="round" stroke-width="2" d="M3 4a1 1 0 011-1h16a1 1 0 011 1v2.586a1 1 0 01-.293.707l-6.414 6.414a1 1 0 00-.293.707V17l-4 4v-6.586a1 1 0 00-.293-.707L3.293 7.293A1 1 0 013 6.586V4z" />
				</svg>
				<p>No custom rules defined yet.</p>
				<p class="text-sm mt-1">Create your first rule to enforce custom patterns in code reviews.</p>
			</div>
		{:else}
			<div class="divide-y divide-gray-100">
				{#each rulesList as rule}
					<div class="p-4 hover:bg-gray-50">
						<div class="flex items-start justify-between gap-4">
							<div class="flex-1">
								<div class="flex items-center gap-3 mb-1">
									<h4 class="font-medium text-gray-900">{rule.name}</h4>
									<span class="badge {getSeverityColor(rule.severity)}">{rule.severity}</span>
									<span class="badge bg-gray-100 text-gray-600">{rule.category}</span>
									{#if !rule.enabled}
										<span class="badge bg-gray-100 text-gray-500">Disabled</span>
									{/if}
								</div>
								{#if rule.description}
									<p class="text-sm text-gray-600 mb-2">{rule.description}</p>
								{/if}
								<div class="flex items-center gap-4 text-xs text-gray-500">
									<span class="font-mono bg-gray-100 px-2 py-1 rounded">{rule.pattern}</span>
									{#if rule.languages && rule.languages.length > 0}
										<span>{rule.languages.join(', ')}</span>
									{:else}
										<span>All languages</span>
									{/if}
								</div>
							</div>

							<div class="flex items-center gap-3">
								<label class="relative inline-flex items-center cursor-pointer">
									<input
										type="checkbox"
										checked={rule.enabled}
										on:change={() => toggleRule(rule.id, !rule.enabled)}
										class="sr-only peer"
									/>
									<div class="w-11 h-6 bg-gray-200 peer-focus:outline-none peer-focus:ring-4 peer-focus:ring-primary-300 rounded-full peer peer-checked:after:translate-x-full peer-checked:after:border-white after:content-[''] after:absolute after:top-[2px] after:left-[2px] after:bg-white after:border-gray-300 after:border after:rounded-full after:h-5 after:w-5 after:transition-all peer-checked:bg-primary-700"></div>
								</label>

								{#if rule.isCustom}
									<button
										on:click={() => deleteRule(rule.id, rule.name)}
										class="p-2 text-gray-400 hover:text-red-600 transition-colors"
										title="Delete rule"
									>
										<svg class="w-5 h-5" fill="none" stroke="currentColor" viewBox="0 0 24 24">
											<path stroke-linecap="round" stroke-linejoin="round" stroke-width="2" d="M19 7l-.867 12.142A2 2 0 0116.138 21H7.862a2 2 0 01-1.995-1.858L5 7m5 4v6m4-6v6m1-10V4a1 1 0 00-1-1h-4a1 1 0 00-1 1v3M4 7h16" />
										</svg>
									</button>
								{/if}
							</div>
						</div>
					</div>
				{/each}
			</div>
		{/if}
	</div>

	<!-- Help Section -->
	<div class="card p-6 bg-blue-50 border-blue-200">
		<h3 class="text-sm font-medium text-blue-900 mb-2">Tips for Writing Rules</h3>
		<ul class="text-sm text-blue-700 space-y-1">
			<li>• Use regex patterns to match code that should be flagged</li>
			<li>• Be specific to avoid false positives</li>
			<li>• Test your pattern on sample code before saving</li>
			<li>• Add a clear suggestion to help developers fix the issue</li>
		</ul>
	</div>
</div>
