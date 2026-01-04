<script lang="ts">
	import { onMount } from 'svelte';
	import { settings } from '$lib/api/client';
	import type { UserSettings } from '$lib/api/client';

	let userSettings: UserSettings | null = $state(null);
	let loading = $state(true);
	let saving = $state(false);
	let error: string | null = $state(null);
	let success: string | null = $state(null);

	// Form state
	let name = $state('');

	onMount(async () => {
		try {
			userSettings = await settings.getUser();
			name = userSettings.name || '';
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
			userSettings = await settings.updateUser({ name });
			success = 'Profile saved successfully';
			setTimeout(() => success = null, 3000);
		} catch (e) {
			error = e instanceof Error ? e.message : 'Failed to save settings';
		} finally {
			saving = false;
		}
	}

	function getRoleBadgeColor(role: string): string {
		switch (role) {
			case 'ADMIN': return 'bg-purple-100 text-purple-800';
			case 'MEMBER': return 'bg-blue-100 text-blue-800';
			case 'VIEWER': return 'bg-gray-100 text-gray-800';
			default: return 'bg-gray-100 text-gray-800';
		}
	}
</script>

<svelte:head>
	<title>Profile Settings - CodeLens</title>
</svelte:head>

{#if loading}
	<div class="flex items-center justify-center h-64">
		<div class="animate-spin rounded-full h-8 w-8 border-b-2 border-primary-700"></div>
	</div>
{:else if error && !userSettings}
	<div class="card p-6 text-center text-red-600">
		{error}
	</div>
{:else if userSettings}
	<!-- Profile Card -->
	<div class="card p-6 mb-6">
		<div class="flex items-start gap-6 mb-6">
			<!-- Avatar -->
			<div class="shrink-0">
				{#if userSettings.avatarUrl}
					<img
						src={userSettings.avatarUrl}
						alt={userSettings.name}
						class="w-20 h-20 rounded-xl"
					/>
				{:else}
					<div class="w-20 h-20 bg-primary-100 rounded-xl flex items-center justify-center text-primary-700 text-2xl font-bold">
						{userSettings.name?.[0]?.toUpperCase() || 'U'}
					</div>
				{/if}
			</div>

			<!-- Info -->
			<div class="flex-1">
				<div class="flex items-center gap-3 mb-1">
					<h2 class="text-xl font-semibold text-gray-900">{userSettings.name}</h2>
					<span class="badge {getRoleBadgeColor(userSettings.role)}">{userSettings.role}</span>
				</div>
				<p class="text-gray-500">{userSettings.email}</p>
				{#if userSettings.organizationName}
					<p class="text-sm text-gray-400 mt-1">
						<span class="inline-flex items-center gap-1">
							<svg class="w-4 h-4" fill="none" stroke="currentColor" viewBox="0 0 24 24">
								<path stroke-linecap="round" stroke-linejoin="round" stroke-width="2" d="M19 21V5a2 2 0 00-2-2H7a2 2 0 00-2 2v16m14 0h2m-2 0h-5m-9 0H3m2 0h5M9 7h1m-1 4h1m4-4h1m-1 4h1m-5 10v-5a1 1 0 011-1h2a1 1 0 011 1v5m-4 0h4" />
							</svg>
							{userSettings.organizationName}
						</span>
					</p>
				{/if}
			</div>
		</div>

		<hr class="my-6 border-gray-100" />

		<!-- Edit Form -->
		<form onsubmit={(e) => { e.preventDefault(); saveSettings(); }}>
			<div class="space-y-4">
				<div>
					<label for="email" class="block text-sm font-medium text-gray-700 mb-1">Email</label>
					<input
						type="email"
						id="email"
						value={userSettings.email}
						disabled
						class="input bg-gray-50 cursor-not-allowed"
					/>
					<p class="text-xs text-gray-400 mt-1">Email is managed by your Google account</p>
				</div>

				<div>
					<label for="name" class="block text-sm font-medium text-gray-700 mb-1">Display Name</label>
					<input
						type="text"
						id="name"
						bind:value={name}
						class="input"
						placeholder="Your name"
					/>
				</div>

				<div>
					<label class="block text-sm font-medium text-gray-700 mb-1">Role</label>
					<input
						type="text"
						value={userSettings.role}
						disabled
						class="input bg-gray-50 cursor-not-allowed"
					/>
					<p class="text-xs text-gray-400 mt-1">Role is managed by your organization admin</p>
				</div>
			</div>

			<!-- Alerts -->
			{#if error}
				<div class="mt-4 p-3 bg-red-50 border border-red-200 rounded-lg text-red-700 text-sm">
					{error}
				</div>
			{/if}

			{#if success}
				<div class="mt-4 p-3 bg-green-50 border border-green-200 rounded-lg text-green-700 text-sm">
					{success}
				</div>
			{/if}

			<!-- Save Button -->
			<div class="mt-6">
				<button
					type="submit"
					disabled={saving}
					class="btn btn-primary"
				>
					{#if saving}
						<span class="inline-flex items-center gap-2">
							<div class="animate-spin rounded-full h-4 w-4 border-b-2 border-white"></div>
							Saving...
						</span>
					{:else}
						Save Changes
					{/if}
				</button>
			</div>
		</form>
	</div>

	<!-- Account Info -->
	<div class="card p-6">
		<h3 class="font-semibold mb-4">Account Information</h3>
		<div class="space-y-3 text-sm">
			<div class="flex justify-between">
				<span class="text-gray-500">User ID</span>
				<span class="font-mono text-gray-700">{userSettings.id}</span>
			</div>
			<div class="flex justify-between">
				<span class="text-gray-500">Authentication</span>
				<span class="text-gray-700">Google OAuth</span>
			</div>
			{#if userSettings.organizationId}
				<div class="flex justify-between">
					<span class="text-gray-500">Organization ID</span>
					<span class="font-mono text-gray-700">{userSettings.organizationId}</span>
				</div>
			{/if}
		</div>
	</div>
{/if}
