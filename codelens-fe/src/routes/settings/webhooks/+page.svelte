<script lang="ts">
	import { onMount } from 'svelte';
	import { webhooks, settings } from '$lib/api/client';
	import type { Webhook, WebhookEventType, CreateWebhookRequest } from '$lib/api/client';

	let loading = $state(true);
	let saving = $state(false);
	let error = $state<string | null>(null);
	let success = $state<string | null>(null);
	let webhookList = $state<Webhook[]>([]);
	let eventTypes = $state<WebhookEventType[]>([]);
	let isAdmin = $state(false);

	// Modal state
	let showModal = $state(false);
	let editingWebhook = $state<Webhook | null>(null);
	let formData = $state<CreateWebhookRequest>({
		name: '',
		url: '',
		secret: '',
		events: [],
		enabled: true
	});

	onMount(async () => {
		await Promise.all([loadWebhooks(), loadEventTypes(), checkAdmin()]);
	});

	async function checkAdmin() {
		try {
			const userSettings = await settings.getUser();
			isAdmin = userSettings.role === 'ADMIN';
		} catch {
			isAdmin = false;
		}
	}

	async function loadWebhooks() {
		loading = true;
		error = null;
		try {
			webhookList = await webhooks.getAll();
		} catch (e) {
			error = e instanceof Error ? e.message : 'Failed to load webhooks';
		} finally {
			loading = false;
		}
	}

	async function loadEventTypes() {
		try {
			eventTypes = await webhooks.getEventTypes();
		} catch (e) {
			console.error('Failed to load event types:', e);
		}
	}

	function openCreateModal() {
		editingWebhook = null;
		formData = {
			name: '',
			url: '',
			secret: '',
			events: [],
			enabled: true
		};
		showModal = true;
	}

	function openEditModal(webhook: Webhook) {
		editingWebhook = webhook;
		formData = {
			name: webhook.name,
			url: webhook.url,
			secret: '',
			events: [...webhook.events],
			enabled: webhook.enabled
		};
		showModal = true;
	}

	function closeModal() {
		showModal = false;
		editingWebhook = null;
	}

	function toggleEvent(eventId: string) {
		if (formData.events.includes(eventId)) {
			formData.events = formData.events.filter((e) => e !== eventId);
		} else {
			formData.events = [...formData.events, eventId];
		}
	}

	async function saveWebhook() {
		if (!formData.name || !formData.url || formData.events.length === 0) {
			error = 'Please fill in all required fields and select at least one event';
			return;
		}

		saving = true;
		error = null;
		success = null;

		try {
			const payload: CreateWebhookRequest = {
				name: formData.name,
				url: formData.url,
				events: formData.events,
				enabled: formData.enabled
			};
			if (formData.secret) {
				payload.secret = formData.secret;
			}

			if (editingWebhook) {
				await webhooks.update(editingWebhook.id, payload);
				success = 'Webhook updated successfully';
			} else {
				await webhooks.create(payload);
				success = 'Webhook created successfully';
			}
			closeModal();
			await loadWebhooks();
			setTimeout(() => (success = null), 3000);
		} catch (e) {
			error = e instanceof Error ? e.message : 'Failed to save webhook';
		} finally {
			saving = false;
		}
	}

	async function deleteWebhook(webhook: Webhook) {
		if (!confirm(`Are you sure you want to delete "${webhook.name}"?`)) return;

		try {
			await webhooks.delete(webhook.id);
			success = 'Webhook deleted successfully';
			await loadWebhooks();
			setTimeout(() => (success = null), 3000);
		} catch (e) {
			error = e instanceof Error ? e.message : 'Failed to delete webhook';
		}
	}

	async function toggleWebhookEnabled(webhook: Webhook) {
		try {
			await webhooks.update(webhook.id, { enabled: !webhook.enabled });
			await loadWebhooks();
		} catch (e) {
			error = e instanceof Error ? e.message : 'Failed to update webhook';
		}
	}

	function formatDate(dateStr: string | null): string {
		if (!dateStr) return 'Never';
		return new Date(dateStr).toLocaleString();
	}
</script>

<div class="space-y-6">
	<div class="flex items-center justify-between">
		<div>
			<h2 class="text-lg font-semibold text-gray-900">Webhooks</h2>
			<p class="text-sm text-gray-500">Configure webhooks to receive notifications in external systems</p>
		</div>
		{#if isAdmin}
			<button
				onclick={openCreateModal}
				class="px-4 py-2 bg-amber-600 text-white rounded-lg hover:bg-amber-700 flex items-center gap-2"
			>
				<svg class="w-5 h-5" fill="none" stroke="currentColor" viewBox="0 0 24 24">
					<path stroke-linecap="round" stroke-linejoin="round" stroke-width="2" d="M12 4v16m8-8H4" />
				</svg>
				Add Webhook
			</button>
		{/if}
	</div>

	{#if !isAdmin}
		<div class="p-4 bg-amber-50 border border-amber-200 rounded-lg text-amber-700">
			<div class="flex items-center gap-2">
				<svg class="w-5 h-5" fill="none" stroke="currentColor" viewBox="0 0 24 24">
					<path stroke-linecap="round" stroke-linejoin="round" stroke-width="2" d="M12 9v2m0 4h.01m-6.938 4h13.856c1.54 0 2.502-1.667 1.732-3L13.732 4c-.77-1.333-2.694-1.333-3.464 0L3.34 16c-.77 1.333.192 3 1.732 3z" />
				</svg>
				Only organization admins can manage webhooks
			</div>
		</div>
	{/if}

	{#if error}
		<div class="p-4 bg-red-50 border border-red-200 rounded-lg text-red-700">
			{error}
		</div>
	{/if}

	{#if success}
		<div class="p-4 bg-green-50 border border-green-200 rounded-lg text-green-700 flex items-center gap-2">
			<svg class="w-5 h-5" fill="none" stroke="currentColor" viewBox="0 0 24 24">
				<path stroke-linecap="round" stroke-linejoin="round" stroke-width="2" d="M5 13l4 4L19 7" />
			</svg>
			{success}
		</div>
	{/if}

	{#if loading}
		<div class="flex items-center justify-center py-12">
			<div class="animate-spin rounded-full h-8 w-8 border-b-2 border-amber-700"></div>
		</div>
	{:else if webhookList.length === 0}
		<div class="bg-white rounded-xl border border-gray-200 p-12 text-center">
			<svg class="w-12 h-12 text-gray-400 mx-auto mb-4" fill="none" stroke="currentColor" viewBox="0 0 24 24">
				<path stroke-linecap="round" stroke-linejoin="round" stroke-width="2" d="M13.828 10.172a4 4 0 00-5.656 0l-4 4a4 4 0 105.656 5.656l1.102-1.101m-.758-4.899a4 4 0 005.656 0l4-4a4 4 0 00-5.656-5.656l-1.1 1.1" />
			</svg>
			<p class="text-gray-500">No webhooks configured</p>
			{#if isAdmin}
				<p class="text-sm text-gray-400 mt-1">Create a webhook to receive notifications in Slack, Discord, or other services</p>
			{/if}
		</div>
	{:else}
		<div class="bg-white rounded-xl border border-gray-200 divide-y divide-gray-200">
			{#each webhookList as webhook}
				<div class="p-4 flex items-center justify-between gap-4">
					<div class="flex-1 min-w-0">
						<div class="flex items-center gap-3">
							<h3 class="font-medium text-gray-900">{webhook.name}</h3>
							{#if webhook.enabled}
								<span class="px-2 py-0.5 bg-green-100 text-green-700 text-xs rounded-full">Active</span>
							{:else}
								<span class="px-2 py-0.5 bg-gray-100 text-gray-600 text-xs rounded-full">Disabled</span>
							{/if}
							{#if webhook.failureCount > 0}
								<span class="px-2 py-0.5 bg-red-100 text-red-700 text-xs rounded-full">{webhook.failureCount} failures</span>
							{/if}
						</div>
						<p class="text-sm text-gray-500 truncate mt-1">{webhook.url}</p>
						<div class="flex items-center gap-4 mt-2 text-xs text-gray-400">
							<span>Events: {webhook.events.join(', ')}</span>
							<span>Last delivery: {formatDate(webhook.lastDeliveryAt)}</span>
						</div>
					</div>
					{#if isAdmin}
						<div class="flex items-center gap-2">
							<button
								onclick={() => toggleWebhookEnabled(webhook)}
								class="p-2 text-gray-400 hover:text-gray-600 hover:bg-gray-100 rounded-lg"
								title={webhook.enabled ? 'Disable' : 'Enable'}
							>
								{#if webhook.enabled}
									<svg class="w-5 h-5" fill="none" stroke="currentColor" viewBox="0 0 24 24">
										<path stroke-linecap="round" stroke-linejoin="round" stroke-width="2" d="M18.364 18.364A9 9 0 005.636 5.636m12.728 12.728A9 9 0 015.636 5.636m12.728 12.728L5.636 5.636" />
									</svg>
								{:else}
									<svg class="w-5 h-5" fill="none" stroke="currentColor" viewBox="0 0 24 24">
										<path stroke-linecap="round" stroke-linejoin="round" stroke-width="2" d="M5 13l4 4L19 7" />
									</svg>
								{/if}
							</button>
							<button
								onclick={() => openEditModal(webhook)}
								class="p-2 text-gray-400 hover:text-gray-600 hover:bg-gray-100 rounded-lg"
								title="Edit"
							>
								<svg class="w-5 h-5" fill="none" stroke="currentColor" viewBox="0 0 24 24">
									<path stroke-linecap="round" stroke-linejoin="round" stroke-width="2" d="M11 5H6a2 2 0 00-2 2v11a2 2 0 002 2h11a2 2 0 002-2v-5m-1.414-9.414a2 2 0 112.828 2.828L11.828 15H9v-2.828l8.586-8.586z" />
								</svg>
							</button>
							<button
								onclick={() => deleteWebhook(webhook)}
								class="p-2 text-gray-400 hover:text-red-600 hover:bg-red-50 rounded-lg"
								title="Delete"
							>
								<svg class="w-5 h-5" fill="none" stroke="currentColor" viewBox="0 0 24 24">
									<path stroke-linecap="round" stroke-linejoin="round" stroke-width="2" d="M19 7l-.867 12.142A2 2 0 0116.138 21H7.862a2 2 0 01-1.995-1.858L5 7m5 4v6m4-6v6m1-10V4a1 1 0 00-1-1h-4a1 1 0 00-1 1v3M4 7h16" />
								</svg>
							</button>
						</div>
					{/if}
				</div>
			{/each}
		</div>
	{/if}
</div>

<!-- Create/Edit Modal -->
{#if showModal}
	<div class="fixed inset-0 bg-black/50 flex items-center justify-center z-50 p-4">
		<div class="bg-white rounded-2xl max-w-lg w-full max-h-[90vh] overflow-y-auto">
			<div class="p-6 border-b border-gray-200">
				<h3 class="text-lg font-semibold text-gray-900">
					{editingWebhook ? 'Edit Webhook' : 'Create Webhook'}
				</h3>
			</div>

			<div class="p-6 space-y-4">
				<div>
					<label class="block text-sm font-medium text-gray-700 mb-1">Name *</label>
					<input
						type="text"
						bind:value={formData.name}
						placeholder="e.g., Slack Notifications"
						class="w-full px-3 py-2 border border-gray-300 rounded-lg focus:ring-2 focus:ring-amber-500 focus:border-amber-500"
					/>
				</div>

				<div>
					<label class="block text-sm font-medium text-gray-700 mb-1">URL *</label>
					<input
						type="url"
						bind:value={formData.url}
						placeholder="https://hooks.slack.com/services/..."
						class="w-full px-3 py-2 border border-gray-300 rounded-lg focus:ring-2 focus:ring-amber-500 focus:border-amber-500"
					/>
				</div>

				<div>
					<label class="block text-sm font-medium text-gray-700 mb-1">
						Secret
						{#if editingWebhook}
							<span class="text-gray-400 font-normal">(leave blank to keep existing)</span>
						{/if}
					</label>
					<input
						type="password"
						bind:value={formData.secret}
						placeholder="Optional signing secret for HMAC verification"
						class="w-full px-3 py-2 border border-gray-300 rounded-lg focus:ring-2 focus:ring-amber-500 focus:border-amber-500"
					/>
					<p class="text-xs text-gray-500 mt-1">Used to sign payloads with X-CodeLens-Signature header</p>
				</div>

				<div>
					<label class="block text-sm font-medium text-gray-700 mb-2">Events *</label>
					<div class="space-y-2">
						{#each eventTypes as eventType}
							<label class="flex items-start gap-3 p-3 border border-gray-200 rounded-lg hover:bg-gray-50 cursor-pointer">
								<input
									type="checkbox"
									checked={formData.events.includes(eventType.id)}
									onchange={() => toggleEvent(eventType.id)}
									class="mt-0.5 h-4 w-4 text-amber-600 border-gray-300 rounded focus:ring-amber-500"
								/>
								<div>
									<span class="text-sm font-medium text-gray-700">{eventType.name}</span>
									<p class="text-xs text-gray-500">{eventType.description}</p>
								</div>
							</label>
						{/each}
					</div>
				</div>

				<label class="flex items-center gap-2">
					<input
						type="checkbox"
						bind:checked={formData.enabled}
						class="h-4 w-4 text-amber-600 border-gray-300 rounded focus:ring-amber-500"
					/>
					<span class="text-sm text-gray-700">Enable this webhook</span>
				</label>
			</div>

			<div class="p-6 border-t border-gray-200 flex justify-end gap-3">
				<button
					onclick={closeModal}
					class="px-4 py-2 text-gray-700 hover:bg-gray-100 rounded-lg"
				>
					Cancel
				</button>
				<button
					onclick={saveWebhook}
					disabled={saving}
					class="px-4 py-2 bg-amber-600 text-white rounded-lg hover:bg-amber-700 disabled:opacity-50 disabled:cursor-not-allowed flex items-center gap-2"
				>
					{#if saving}
						<div class="animate-spin rounded-full h-4 w-4 border-b-2 border-white"></div>
					{/if}
					{editingWebhook ? 'Save Changes' : 'Create Webhook'}
				</button>
			</div>
		</div>
	</div>
{/if}
