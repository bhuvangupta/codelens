<script lang="ts">
	import { onMount } from 'svelte';
	import { settings, llm } from '$lib/api/client';
	import type { UserSettings, LlmProvider } from '$lib/api/client';

	type LlmProviderWithDefault = LlmProvider & { isDefault?: boolean };

	let userSettings: UserSettings | null = $state(null);
	let providers: LlmProviderWithDefault[] = $state([]);
	let loading = $state(true);
	let saving = $state(false);
	let testing: string | null = $state(null);
	let error: string | null = $state(null);
	let success: string | null = $state(null);
	let testResult: { provider: string; success: boolean; message: string } | null = $state(null);

	let selectedProvider = $state('');

	onMount(async () => {
		try {
			const [userData, providersData] = await Promise.all([
				settings.getUser(),
				llm.getProviders()
			]);
			userSettings = userData;
			// Map providers to include isDefault flag
			providers = providersData.map(p => ({
				...p,
				isDefault: p.name === userData.defaultLlmProvider
			}));
			selectedProvider = userData.defaultLlmProvider || '';
		} catch (e) {
			error = e instanceof Error ? e.message : 'Failed to load settings';
		} finally {
			loading = false;
		}
	});

	async function saveSettings() {
		saving = true;
		error = null;
		success = null;

		try {
			userSettings = await settings.updateUser({ defaultLlmProvider: selectedProvider });
			success = 'Default provider saved successfully';
			setTimeout(() => success = null, 3000);
		} catch (e) {
			error = e instanceof Error ? e.message : 'Failed to save settings';
		} finally {
			saving = false;
		}
	}

	async function testProvider(name: string) {
		testing = name;
		testResult = null;

		try {
			const result = await llm.testProvider(name);
			testResult = { provider: name, success: result.success, message: result.message };
		} catch (e) {
			testResult = {
				provider: name,
				success: false,
				message: e instanceof Error ? e.message : 'Test failed'
			};
		} finally {
			testing = null;
		}
	}

	function getProviderIcon(name: string): string {
		switch (name.toLowerCase()) {
			case 'openai': return '🤖';
			case 'claude': return '🧠';
			case 'gemini': return '✨';
			case 'glm': return '🇨🇳';
			case 'ollama': return '🦙';
			default: return '🔮';
		}
	}

	function getProviderDescription(name: string): string {
		switch (name.toLowerCase()) {
			case 'openai': return 'GPT-4 and GPT-3.5 models from OpenAI';
			case 'claude': return 'Claude models from Anthropic';
			case 'gemini': return 'Gemini models from Google';
			case 'glm': return 'GLM-4 models from ZhipuAI';
			case 'ollama': return 'Local models via Ollama';
			default: return 'AI language model';
		}
	}
</script>

<svelte:head>
	<title>LLM Providers - CodeLens</title>
</svelte:head>

{#if loading}
	<div class="flex items-center justify-center h-64">
		<div class="animate-spin rounded-full h-8 w-8 border-b-2 border-primary-700"></div>
	</div>
{:else}
	<!-- Default Provider -->
	<div class="card p-6 mb-6">
		<h2 class="text-lg font-semibold mb-4">Default Provider</h2>
		<p class="text-sm text-gray-600 mb-4">
			Select the default LLM provider for your code reviews. You can override this per-review if needed.
		</p>

		<div class="mb-4">
			<select
				bind:value={selectedProvider}
				class="input"
				disabled={saving}
			>
				<option value="">Select a provider...</option>
				{#each providers.filter(p => p.available) as provider}
					<option value={provider.name}>
						{getProviderIcon(provider.name)} {provider.name.toUpperCase()}
					</option>
				{/each}
			</select>
		</div>

		<!-- Alerts -->
		{#if error}
			<div class="mb-4 p-3 bg-red-50 border border-red-200 rounded-lg text-red-700 text-sm">
				{error}
			</div>
		{/if}

		{#if success}
			<div class="mb-4 p-3 bg-green-50 border border-green-200 rounded-lg text-green-700 text-sm">
				{success}
			</div>
		{/if}

		<button
			onclick={saveSettings}
			disabled={saving || !selectedProvider}
			class="btn btn-primary"
		>
			{#if saving}
				<span class="inline-flex items-center gap-2">
					<div class="animate-spin rounded-full h-4 w-4 border-b-2 border-white"></div>
					Saving...
				</span>
			{:else}
				Save Default Provider
			{/if}
		</button>
	</div>

	<!-- Available Providers -->
	<div class="card p-6">
		<h2 class="text-lg font-semibold mb-4">Available Providers</h2>
		<p class="text-sm text-gray-600 mb-4">
			Test and verify the connection to each configured LLM provider.
		</p>

		<div class="space-y-3">
			{#each providers as provider}
				<div class="flex items-center justify-between p-4 bg-gray-50 rounded-lg border border-gray-100">
					<div class="flex items-center gap-4">
						<span class="text-2xl">{getProviderIcon(provider.name)}</span>
						<div>
							<div class="flex items-center gap-2">
								<p class="font-medium text-gray-900">{provider.name.toUpperCase()}</p>
								{#if provider.isDefault}
									<span class="badge bg-primary-100 text-primary-700 text-xs">Default</span>
								{/if}
							</div>
							<p class="text-sm text-gray-500">{getProviderDescription(provider.name)}</p>
						</div>
					</div>
					<div class="flex items-center gap-3">
						<span class="badge {provider.available ? 'bg-green-100 text-green-800' : 'bg-gray-100 text-gray-600'}">
							{provider.available ? 'Available' : 'Not Configured'}
						</span>
						{#if provider.available}
							<button
								onclick={() => testProvider(provider.name)}
								disabled={testing === provider.name}
								class="btn btn-secondary text-sm py-1.5 px-3"
							>
								{#if testing === provider.name}
									<span class="inline-flex items-center gap-1">
										<div class="animate-spin rounded-full h-3 w-3 border-b-2 border-gray-600"></div>
										Testing...
									</span>
								{:else}
									Test Connection
								{/if}
							</button>
						{/if}
					</div>
				</div>

				<!-- Test Result -->
				{#if testResult && testResult.provider === provider.name}
					<div class="ml-16 p-3 rounded-lg text-sm {testResult.success ? 'bg-green-50 text-green-700 border border-green-200' : 'bg-red-50 text-red-700 border border-red-200'}">
						<div class="flex items-center gap-2">
							{#if testResult.success}
								<svg class="w-4 h-4" fill="none" stroke="currentColor" viewBox="0 0 24 24">
									<path stroke-linecap="round" stroke-linejoin="round" stroke-width="2" d="M5 13l4 4L19 7" />
								</svg>
							{:else}
								<svg class="w-4 h-4" fill="none" stroke="currentColor" viewBox="0 0 24 24">
									<path stroke-linecap="round" stroke-linejoin="round" stroke-width="2" d="M6 18L18 6M6 6l12 12" />
								</svg>
							{/if}
							<span>{testResult.message}</span>
						</div>
					</div>
				{/if}
			{/each}
		</div>
	</div>

	<!-- Info Box -->
	<div class="mt-6 p-4 bg-blue-50 border border-blue-200 rounded-lg">
		<div class="flex gap-3">
			<svg class="w-5 h-5 text-blue-600 shrink-0 mt-0.5" fill="none" stroke="currentColor" viewBox="0 0 24 24">
				<path stroke-linecap="round" stroke-linejoin="round" stroke-width="2" d="M13 16h-1v-4h-1m1-4h.01M21 12a9 9 0 11-18 0 9 9 0 0118 0z" />
			</svg>
			<div class="text-sm text-blue-800">
				<p class="font-medium mb-1">Provider Configuration</p>
				<p>LLM providers are configured at the server level. Contact your administrator to add or configure new providers.</p>
			</div>
		</div>
	</div>
{/if}
