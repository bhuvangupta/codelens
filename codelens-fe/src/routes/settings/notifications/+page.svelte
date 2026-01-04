<script lang="ts">
	import { onMount } from 'svelte';
	import { notifications } from '$lib/api/client';
	import type { NotificationPreferences } from '$lib/api/client';

	let loading = $state(true);
	let saving = $state(false);
	let error = $state<string | null>(null);
	let success = $state<string | null>(null);
	let preferences = $state<NotificationPreferences>({
		emailEnabled: true,
		inAppEnabled: true,
		reviewCompleted: true,
		reviewFailed: true,
		criticalIssues: true
	});

	onMount(async () => {
		await loadPreferences();
	});

	async function loadPreferences() {
		loading = true;
		error = null;
		try {
			preferences = await notifications.getPreferences();
		} catch (e) {
			error = e instanceof Error ? e.message : 'Failed to load preferences';
		} finally {
			loading = false;
		}
	}

	async function savePreferences() {
		saving = true;
		error = null;
		success = null;
		try {
			preferences = await notifications.updatePreferences(preferences);
			success = 'Preferences saved successfully';
			setTimeout(() => (success = null), 3000);
		} catch (e) {
			error = e instanceof Error ? e.message : 'Failed to save preferences';
		} finally {
			saving = false;
		}
	}
</script>

<div class="space-y-6">
	<div>
		<h2 class="text-lg font-semibold text-gray-900">Notification Preferences</h2>
		<p class="text-sm text-gray-500">Configure how you want to receive notifications</p>
	</div>

	{#if loading}
		<div class="flex items-center justify-center py-12">
			<div class="animate-spin rounded-full h-8 w-8 border-b-2 border-amber-700"></div>
		</div>
	{:else}
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

		<div class="bg-white rounded-xl border border-gray-200 divide-y divide-gray-200">
			<!-- Delivery Methods -->
			<div class="p-6">
				<h3 class="text-sm font-medium text-gray-900 mb-4">Delivery Methods</h3>
				<div class="space-y-4">
					<label class="flex items-center justify-between">
						<div>
							<span class="text-sm font-medium text-gray-700">In-App Notifications</span>
							<p class="text-sm text-gray-500">Show notifications in the bell icon dropdown</p>
						</div>
						<button
							type="button"
							onclick={() => (preferences.inAppEnabled = !preferences.inAppEnabled)}
							class="relative inline-flex h-6 w-11 flex-shrink-0 cursor-pointer rounded-full border-2 border-transparent transition-colors duration-200 ease-in-out focus:outline-none focus:ring-2 focus:ring-amber-500 focus:ring-offset-2 {preferences.inAppEnabled ? 'bg-amber-600' : 'bg-gray-200'}"
							role="switch"
							aria-checked={preferences.inAppEnabled}
						>
							<span
								class="pointer-events-none inline-block h-5 w-5 transform rounded-full bg-white shadow ring-0 transition duration-200 ease-in-out {preferences.inAppEnabled ? 'translate-x-5' : 'translate-x-0'}"
							></span>
						</button>
					</label>

					<label class="flex items-center justify-between">
						<div>
							<span class="text-sm font-medium text-gray-700">Email Notifications</span>
							<p class="text-sm text-gray-500">Receive notifications via email</p>
						</div>
						<button
							type="button"
							onclick={() => (preferences.emailEnabled = !preferences.emailEnabled)}
							class="relative inline-flex h-6 w-11 flex-shrink-0 cursor-pointer rounded-full border-2 border-transparent transition-colors duration-200 ease-in-out focus:outline-none focus:ring-2 focus:ring-amber-500 focus:ring-offset-2 {preferences.emailEnabled ? 'bg-amber-600' : 'bg-gray-200'}"
							role="switch"
							aria-checked={preferences.emailEnabled}
						>
							<span
								class="pointer-events-none inline-block h-5 w-5 transform rounded-full bg-white shadow ring-0 transition duration-200 ease-in-out {preferences.emailEnabled ? 'translate-x-5' : 'translate-x-0'}"
							></span>
						</button>
					</label>
				</div>
			</div>

			<!-- Notification Types -->
			<div class="p-6">
				<h3 class="text-sm font-medium text-gray-900 mb-4">Notification Types</h3>
				<div class="space-y-4">
					<label class="flex items-center justify-between">
						<div>
							<span class="text-sm font-medium text-gray-700">Review Completed</span>
							<p class="text-sm text-gray-500">When a code review finishes successfully</p>
						</div>
						<button
							type="button"
							onclick={() => (preferences.reviewCompleted = !preferences.reviewCompleted)}
							class="relative inline-flex h-6 w-11 flex-shrink-0 cursor-pointer rounded-full border-2 border-transparent transition-colors duration-200 ease-in-out focus:outline-none focus:ring-2 focus:ring-amber-500 focus:ring-offset-2 {preferences.reviewCompleted ? 'bg-amber-600' : 'bg-gray-200'}"
							role="switch"
							aria-checked={preferences.reviewCompleted}
						>
							<span
								class="pointer-events-none inline-block h-5 w-5 transform rounded-full bg-white shadow ring-0 transition duration-200 ease-in-out {preferences.reviewCompleted ? 'translate-x-5' : 'translate-x-0'}"
							></span>
						</button>
					</label>

					<label class="flex items-center justify-between">
						<div>
							<span class="text-sm font-medium text-gray-700">Review Failed</span>
							<p class="text-sm text-gray-500">When a code review fails or encounters an error</p>
						</div>
						<button
							type="button"
							onclick={() => (preferences.reviewFailed = !preferences.reviewFailed)}
							class="relative inline-flex h-6 w-11 flex-shrink-0 cursor-pointer rounded-full border-2 border-transparent transition-colors duration-200 ease-in-out focus:outline-none focus:ring-2 focus:ring-amber-500 focus:ring-offset-2 {preferences.reviewFailed ? 'bg-amber-600' : 'bg-gray-200'}"
							role="switch"
							aria-checked={preferences.reviewFailed}
						>
							<span
								class="pointer-events-none inline-block h-5 w-5 transform rounded-full bg-white shadow ring-0 transition duration-200 ease-in-out {preferences.reviewFailed ? 'translate-x-5' : 'translate-x-0'}"
							></span>
						</button>
					</label>

					<label class="flex items-center justify-between">
						<div>
							<span class="text-sm font-medium text-gray-700">Critical Issues Found</span>
							<p class="text-sm text-gray-500">When critical security issues are detected in a review</p>
						</div>
						<button
							type="button"
							onclick={() => (preferences.criticalIssues = !preferences.criticalIssues)}
							class="relative inline-flex h-6 w-11 flex-shrink-0 cursor-pointer rounded-full border-2 border-transparent transition-colors duration-200 ease-in-out focus:outline-none focus:ring-2 focus:ring-amber-500 focus:ring-offset-2 {preferences.criticalIssues ? 'bg-amber-600' : 'bg-gray-200'}"
							role="switch"
							aria-checked={preferences.criticalIssues}
						>
							<span
								class="pointer-events-none inline-block h-5 w-5 transform rounded-full bg-white shadow ring-0 transition duration-200 ease-in-out {preferences.criticalIssues ? 'translate-x-5' : 'translate-x-0'}"
							></span>
						</button>
					</label>
				</div>
			</div>
		</div>

		<div class="flex justify-end">
			<button
				onclick={savePreferences}
				disabled={saving}
				class="px-4 py-2 bg-amber-600 text-white rounded-lg hover:bg-amber-700 disabled:opacity-50 disabled:cursor-not-allowed flex items-center gap-2"
			>
				{#if saving}
					<div class="animate-spin rounded-full h-4 w-4 border-b-2 border-white"></div>
				{/if}
				Save Preferences
			</button>
		</div>
	{/if}
</div>
