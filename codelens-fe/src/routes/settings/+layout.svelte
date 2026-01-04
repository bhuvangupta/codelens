<script lang="ts">
	import { page } from '$app/stores';
	import { goto } from '$app/navigation';
	import { onMount } from 'svelte';
	import { settings } from '$lib/api/client';

	let { children } = $props();
	let isAdmin = $state(false);
	let loading = $state(true);
	let accessDenied = $state(false);

	const allTabs = [
		{ href: '/settings', label: 'Profile', icon: 'user', adminOnly: false },
		{ href: '/settings/notifications', label: 'Notifications', icon: 'bell', adminOnly: true },
		{ href: '/settings/llm', label: 'LLM Providers', icon: 'cpu', adminOnly: true },
		{ href: '/settings/repositories', label: 'Repositories', icon: 'git', adminOnly: true },
		{ href: '/settings/organization', label: 'Organization', icon: 'building', adminOnly: true },
		{ href: '/settings/team', label: 'Team', icon: 'users', adminOnly: true },
		{ href: '/settings/rules', label: 'Rules', icon: 'filter', adminOnly: true },
		{ href: '/settings/webhooks', label: 'Webhooks', icon: 'link', adminOnly: true },
		{ href: '/settings/audit', label: 'Audit Log', icon: 'list', adminOnly: true }
	];

	const adminOnlyPaths = allTabs.filter(t => t.adminOnly).map(t => t.href);

	// Filter tabs based on role
	let tabs = $derived(
		isAdmin ? allTabs : allTabs.filter(tab => !tab.adminOnly)
	);

	onMount(async () => {
		try {
			const userSettings = await settings.getUser();
			isAdmin = userSettings.role === 'ADMIN';

			// Check if non-admin is trying to access admin-only page
			if (!isAdmin) {
				const currentPath = $page.url.pathname;
				const isAdminPage = adminOnlyPaths.some(path =>
					currentPath === path || currentPath.startsWith(path + '/')
				);
				if (isAdminPage) {
					accessDenied = true;
					setTimeout(() => goto('/settings'), 2000);
				}
			}
		} catch (e) {
			console.error('Failed to load user settings:', e);
		} finally {
			loading = false;
		}
	});

	function isActive(href: string): boolean {
		if (href === '/settings') {
			return $page.url.pathname === '/settings';
		}
		return $page.url.pathname.startsWith(href);
	}
</script>

<div class="p-8">
	<div class="mb-8">
		<h1 class="text-2xl font-bold text-gray-900">Settings</h1>
		<p class="text-gray-600">Manage your account and preferences</p>
	</div>

	<!-- Tabs -->
	<div class="border-b border-gray-200 mb-6">
		{#if loading}
			<div class="flex gap-4 py-3">
				<div class="h-4 w-16 bg-gray-200 rounded animate-pulse"></div>
				<div class="h-4 w-20 bg-gray-200 rounded animate-pulse"></div>
			</div>
		{:else}
		<nav class="flex gap-4 overflow-x-auto">
			{#each tabs as tab}
				<a
					href={tab.href}
					class="pb-3 px-1 border-b-2 text-sm font-medium transition-colors flex items-center gap-2 whitespace-nowrap"
					class:border-primary-700={isActive(tab.href)}
					class:text-primary-700={isActive(tab.href)}
					class:border-transparent={!isActive(tab.href)}
					class:text-gray-500={!isActive(tab.href)}
					class:hover:text-gray-700={!isActive(tab.href)}
				>
					{#if tab.icon === 'user'}
						<svg class="w-4 h-4" fill="none" stroke="currentColor" viewBox="0 0 24 24">
							<path stroke-linecap="round" stroke-linejoin="round" stroke-width="2" d="M16 7a4 4 0 11-8 0 4 4 0 018 0zM12 14a7 7 0 00-7 7h14a7 7 0 00-7-7z" />
						</svg>
					{:else if tab.icon === 'cpu'}
						<svg class="w-4 h-4" fill="none" stroke="currentColor" viewBox="0 0 24 24">
							<path stroke-linecap="round" stroke-linejoin="round" stroke-width="2" d="M9 3v2m6-2v2M9 19v2m6-2v2M5 9H3m2 6H3m18-6h-2m2 6h-2M7 19h10a2 2 0 002-2V7a2 2 0 00-2-2H7a2 2 0 00-2 2v10a2 2 0 002 2zM9 9h6v6H9V9z" />
						</svg>
					{:else if tab.icon === 'git'}
						<svg class="w-4 h-4" fill="none" stroke="currentColor" viewBox="0 0 24 24">
							<path stroke-linecap="round" stroke-linejoin="round" stroke-width="2" d="M3 7v10a2 2 0 002 2h14a2 2 0 002-2V9a2 2 0 00-2-2h-6l-2-2H5a2 2 0 00-2 2z" />
						</svg>
					{:else if tab.icon === 'building'}
						<svg class="w-4 h-4" fill="none" stroke="currentColor" viewBox="0 0 24 24">
							<path stroke-linecap="round" stroke-linejoin="round" stroke-width="2" d="M19 21V5a2 2 0 00-2-2H7a2 2 0 00-2 2v16m14 0h2m-2 0h-5m-9 0H3m2 0h5M9 7h1m-1 4h1m4-4h1m-1 4h1m-5 10v-5a1 1 0 011-1h2a1 1 0 011 1v5m-4 0h4" />
						</svg>
					{:else if tab.icon === 'users'}
						<svg class="w-4 h-4" fill="none" stroke="currentColor" viewBox="0 0 24 24">
							<path stroke-linecap="round" stroke-linejoin="round" stroke-width="2" d="M12 4.354a4 4 0 110 5.292M15 21H3v-1a6 6 0 0112 0v1zm0 0h6v-1a6 6 0 00-9-5.197M13 7a4 4 0 11-8 0 4 4 0 018 0z" />
						</svg>
					{:else if tab.icon === 'filter'}
						<svg class="w-4 h-4" fill="none" stroke="currentColor" viewBox="0 0 24 24">
							<path stroke-linecap="round" stroke-linejoin="round" stroke-width="2" d="M3 4a1 1 0 011-1h16a1 1 0 011 1v2.586a1 1 0 01-.293.707l-6.414 6.414a1 1 0 00-.293.707V17l-4 4v-6.586a1 1 0 00-.293-.707L3.293 7.293A1 1 0 013 6.586V4z" />
						</svg>
					{:else if tab.icon === 'bell'}
						<svg class="w-4 h-4" fill="none" stroke="currentColor" viewBox="0 0 24 24">
							<path stroke-linecap="round" stroke-linejoin="round" stroke-width="2" d="M15 17h5l-1.405-1.405A2.032 2.032 0 0118 14.158V11a6.002 6.002 0 00-4-5.659V5a2 2 0 10-4 0v.341C7.67 6.165 6 8.388 6 11v3.159c0 .538-.214 1.055-.595 1.436L4 17h5m6 0v1a3 3 0 11-6 0v-1m6 0H9" />
						</svg>
					{:else if tab.icon === 'link'}
						<svg class="w-4 h-4" fill="none" stroke="currentColor" viewBox="0 0 24 24">
							<path stroke-linecap="round" stroke-linejoin="round" stroke-width="2" d="M13.828 10.172a4 4 0 00-5.656 0l-4 4a4 4 0 105.656 5.656l1.102-1.101m-.758-4.899a4 4 0 005.656 0l4-4a4 4 0 00-5.656-5.656l-1.1 1.1" />
						</svg>
					{:else if tab.icon === 'list'}
						<svg class="w-4 h-4" fill="none" stroke="currentColor" viewBox="0 0 24 24">
							<path stroke-linecap="round" stroke-linejoin="round" stroke-width="2" d="M9 5H7a2 2 0 00-2 2v12a2 2 0 002 2h10a2 2 0 002-2V7a2 2 0 00-2-2h-2M9 5a2 2 0 002 2h2a2 2 0 002-2M9 5a2 2 0 012-2h2a2 2 0 012 2m-3 7h3m-3 4h3m-6-4h.01M9 16h.01" />
						</svg>
					{/if}
					{tab.label}
				</a>
			{/each}
		</nav>
		{/if}
	</div>

	<!-- Content -->
	<div class="max-w-4xl">
		{#if loading}
			<div class="flex items-center justify-center h-64">
				<div class="animate-spin rounded-full h-8 w-8 border-b-2 border-primary-700"></div>
			</div>
		{:else if accessDenied}
			<div class="bg-red-50 border border-red-200 rounded-xl p-6 text-center">
				<svg class="w-12 h-12 text-red-400 mx-auto mb-4" fill="none" stroke="currentColor" viewBox="0 0 24 24">
					<path stroke-linecap="round" stroke-linejoin="round" stroke-width="2" d="M12 9v2m0 4h.01m-6.938 4h13.856c1.54 0 2.502-1.667 1.732-3L13.732 4c-.77-1.333-2.694-1.333-3.464 0L3.34 16c-.77 1.333.192 3 1.732 3z" />
				</svg>
				<h3 class="text-lg font-semibold text-red-800 mb-2">Access Denied</h3>
				<p class="text-red-600">This page is only accessible to administrators.</p>
				<p class="text-red-500 text-sm mt-2">Redirecting to settings...</p>
			</div>
		{:else}
			{@render children()}
		{/if}
	</div>
</div>
