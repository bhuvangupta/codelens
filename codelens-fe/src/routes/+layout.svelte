<script lang="ts">
	import '../app.css';
	import { page } from '$app/stores';
	import { goto } from '$app/navigation';
	import { onMount, onDestroy } from 'svelte';
	import { analytics, settings, user as userApi } from '$lib/api/client';
	import type { SidebarStats } from '$lib/api/client';
	import NotificationDropdown from '$lib/components/NotificationDropdown.svelte';
	import { startProactiveRefresh, stopProactiveRefresh } from '$lib/auth/tokenRefresh';

	let { data, children } = $props();
	let sidebarOpen = $state(false);
	let sidebarStats: SidebarStats | null = $state(null);
	let userSynced = $state(false);
	let isAdmin = $state(false);

	const allNavItems = [
		{ href: '/dashboard', label: 'Dashboard', icon: 'home', adminOnly: false },
		{ href: '/reviews', label: 'Reviews', icon: 'code-review', adminOnly: false },
		{ href: '/reviews/new', label: 'Submit PR', icon: 'paper-plane', adminOnly: false },
		{ href: '/analytics', label: 'Analytics', icon: 'chart', adminOnly: false },
		{ href: '/analytics/developers', label: 'Developers', icon: 'users', adminOnly: false },
		{ href: '/settings', label: 'Settings', icon: 'cog', adminOnly: true }
	];

	// Filter nav items based on role - Settings only for admins
	let navItems = $derived(
		isAdmin ? allNavItems : allNavItems.filter(item => !item.adminOnly)
	);

	// Sync user to backend when authenticated
	$effect(() => {
		if (data.user && !userSynced) {
			userApi.sync().then(() => {
				userSynced = true;
			}).catch((e) => {
				console.error('Failed to sync user:', e);
			});
		} else if (!data.user) {
			userSynced = false;
		}
	});

	onMount(async () => {
		if (data.user) {
			// Start proactive token refresh for authenticated users
			startProactiveRefresh();

			try {
				const [statsData, userSettings] = await Promise.all([
					analytics.getSidebarStats(),
					settings.getUser()
				]);
				sidebarStats = statsData;
				isAdmin = userSettings.role === 'ADMIN';
			} catch (e) {
				// Silently fail - sidebar stats are optional
			}
		}
	});

	onDestroy(() => {
		stopProactiveRefresh();
	});

	function isActive(href: string): boolean {
		return $page.url.pathname === href || $page.url.pathname.startsWith(href + '/');
	}

	function toggleSidebar() {
		sidebarOpen = !sidebarOpen;
	}

	function closeSidebar() {
		sidebarOpen = false;
	}

	async function handleLogout() {
		stopProactiveRefresh();
		await fetch('/auth/logout', { method: 'POST' });
		goto('/login');
	}
</script>

<div class="min-h-screen bg-gradient-to-br from-slate-50 via-white to-amber-50/30">
	{#if data.user}
		<!-- Mobile Header -->
		<header class="lg:hidden fixed top-0 left-0 right-0 z-50 bg-white border-b border-slate-200 px-4 py-3 flex items-center justify-between">
			<div class="flex items-center gap-3">
				<button onclick={toggleSidebar} class="p-2 hover:bg-slate-100 rounded-lg">
					<svg class="w-5 h-5 text-slate-600" fill="none" stroke="currentColor" viewBox="0 0 24 24">
						<path stroke-linecap="round" stroke-linejoin="round" stroke-width="2" d="M4 6h16M4 12h16M4 18h16" />
					</svg>
				</button>
				<a href="/dashboard" class="flex items-center gap-2">
					<img src="/codelens.png" alt="CodeLens" class="w-8 h-8">
					<span class="text-lg font-semibold text-slate-900">CodeLens</span>
				</a>
			</div>
			<div class="flex items-center gap-2">
				<NotificationDropdown />
				<a href="/settings" title="Profile Settings">
					{#if data.user.picture}
						<img src={data.user.picture} alt={data.user.name} class="w-8 h-8 rounded-lg">
					{:else}
						<div class="w-8 h-8 bg-indigo-500 rounded-lg flex items-center justify-center text-white font-medium text-sm">
							{data.user.name?.[0] || data.user.email?.[0] || 'U'}
						</div>
					{/if}
				</a>
			</div>
		</header>

		<!-- Mobile Sidebar Overlay -->
		<div
			class="sidebar-overlay lg:hidden fixed inset-0 bg-black/50 z-40"
			class:active={sidebarOpen}
			onclick={closeSidebar}
			onkeydown={(e) => e.key === 'Escape' && closeSidebar()}
			role="button"
			tabindex="-1"
		></div>

		<div class="flex h-screen pt-14 lg:pt-0">
			<!-- Sidebar -->
			<aside
				class="mobile-sidebar lg:translate-x-0 fixed lg:static inset-y-0 left-0 z-50 w-72 bg-white border-r border-slate-200 flex flex-col pt-14 lg:pt-0"
				class:active={sidebarOpen}
			>
				<!-- Logo -->
				<div class="p-6 hidden lg:block">
					<a href="/dashboard" class="flex items-center gap-3">
						<img src="/codelens.png" alt="CodeLens" class="w-10 h-10">
						<span class="text-xl font-semibold text-slate-900">CodeLens</span>
					</a>
				</div>

				<!-- Quick Action -->
				<div class="px-4 mb-6">
					<a href="/reviews/new" class="flex items-center justify-center gap-2 w-full py-3 bg-gradient-to-r from-primary to-secondary rounded-xl text-white font-medium hover:opacity-90 transition-opacity shadow-lg shadow-primary/25">
						<svg class="w-5 h-5" fill="none" stroke="currentColor" viewBox="0 0 24 24">
							<path stroke-linecap="round" stroke-linejoin="round" stroke-width="2" d="M12 4v16m8-8H4" />
						</svg>
						New Review
					</a>
				</div>

				<!-- Navigation -->
				<nav class="flex-1 px-4">
					<div class="text-xs font-medium text-slate-400 uppercase tracking-wider mb-3 px-3">Menu</div>
					<ul class="space-y-1">
						{#each navItems as item}
							<li>
								<a
									href={item.href}
									class="sidebar-link"
									class:active={isActive(item.href)}
									onclick={closeSidebar}
								>
									<span class="w-5">
										{#if item.icon === 'home'}
											<svg class="w-5 h-5" fill="none" stroke="currentColor" viewBox="0 0 24 24">
												<path stroke-linecap="round" stroke-linejoin="round" stroke-width="2" d="M3 12l2-2m0 0l7-7 7 7M5 10v10a1 1 0 001 1h3m10-11l2 2m-2-2v10a1 1 0 01-1 1h-3m-6 0a1 1 0 001-1v-4a1 1 0 011-1h2a1 1 0 011 1v4a1 1 0 001 1m-6 0h6" />
											</svg>
										{:else if item.icon === 'code-review'}
											<svg class="w-5 h-5" fill="none" stroke="currentColor" viewBox="0 0 24 24">
												<path stroke-linecap="round" stroke-linejoin="round" stroke-width="2" d="M9 12h6m-6 4h6m2 5H7a2 2 0 01-2-2V5a2 2 0 012-2h5.586a1 1 0 01.707.293l5.414 5.414a1 1 0 01.293.707V19a2 2 0 01-2 2z" />
											</svg>
										{:else if item.icon === 'paper-plane'}
											<svg class="w-5 h-5" fill="none" stroke="currentColor" viewBox="0 0 24 24">
												<path stroke-linecap="round" stroke-linejoin="round" stroke-width="2" d="M12 19l9 2-9-18-9 18 9-2zm0 0v-8" />
											</svg>
										{:else if item.icon === 'chart'}
											<svg class="w-5 h-5" fill="none" stroke="currentColor" viewBox="0 0 24 24">
												<path stroke-linecap="round" stroke-linejoin="round" stroke-width="2" d="M9 19v-6a2 2 0 00-2-2H5a2 2 0 00-2 2v6a2 2 0 002 2h2a2 2 0 002-2zm0 0V9a2 2 0 012-2h2a2 2 0 012 2v10m-6 0a2 2 0 002 2h2a2 2 0 002-2m0 0V5a2 2 0 012-2h2a2 2 0 012 2v14a2 2 0 01-2 2h-2a2 2 0 01-2-2z" />
											</svg>
										{:else if item.icon === 'users'}
											<svg class="w-5 h-5" fill="none" stroke="currentColor" viewBox="0 0 24 24">
												<path stroke-linecap="round" stroke-linejoin="round" stroke-width="2" d="M17 20h5v-2a3 3 0 00-5.356-1.857M17 20H7m10 0v-2c0-.656-.126-1.283-.356-1.857M7 20H2v-2a3 3 0 015.356-1.857M7 20v-2c0-.656.126-1.283.356-1.857m0 0a5.002 5.002 0 019.288 0M15 7a3 3 0 11-6 0 3 3 0 016 0zm6 3a2 2 0 11-4 0 2 2 0 014 0zM7 10a2 2 0 11-4 0 2 2 0 014 0z" />
											</svg>
										{:else if item.icon === 'cog'}
											<svg class="w-5 h-5" fill="none" stroke="currentColor" viewBox="0 0 24 24">
												<path stroke-linecap="round" stroke-linejoin="round" stroke-width="2" d="M10.325 4.317c.426-1.756 2.924-1.756 3.35 0a1.724 1.724 0 002.573 1.066c1.543-.94 3.31.826 2.37 2.37a1.724 1.724 0 001.065 2.572c1.756.426 1.756 2.924 0 3.35a1.724 1.724 0 00-1.066 2.573c.94 1.543-.826 3.31-2.37 2.37a1.724 1.724 0 00-2.572 1.065c-.426 1.756-2.924 1.756-3.35 0a1.724 1.724 0 00-2.573-1.066c-1.543.94-3.31-.826-2.37-2.37a1.724 1.724 0 00-1.065-2.572c-1.756-.426-1.756-2.924 0-3.35a1.724 1.724 0 001.066-2.573c-.94-1.543.826-3.31 2.37-2.37.996.608 2.296.07 2.572-1.065z" />
												<path stroke-linecap="round" stroke-linejoin="round" stroke-width="2" d="M15 12a3 3 0 11-6 0 3 3 0 016 0z" />
											</svg>
										{/if}
									</span>
									{item.label}
									{#if item.icon === 'code-review' && sidebarStats?.pendingCount}
										<span class="ml-auto bg-primary/10 text-primary text-xs px-2.5 py-1 rounded-full font-medium">{sidebarStats.pendingCount}</span>
									{/if}
								</a>
							</li>
						{/each}
					</ul>

					<!-- Quick Stats -->
					{#if sidebarStats}
						<div class="text-xs font-medium text-slate-400 uppercase tracking-wider mb-3 px-3 mt-8">Quick Stats</div>
						<div class="bg-slate-50 rounded-xl p-4 space-y-3 border border-slate-100">
							<div class="flex items-center justify-between">
								<span class="text-sm text-slate-500">Today</span>
								<span class="text-sm font-semibold text-slate-900">{sidebarStats.reviewsToday} reviews</span>
							</div>
							<div class="w-full bg-slate-200 rounded-full h-1.5">
								<div class="bg-gradient-to-r from-primary to-secondary h-1.5 rounded-full" style="width: {sidebarStats.progressPercent}%"></div>
							</div>
							<p class="text-xs text-slate-400">{sidebarStats.progressPercent}% of daily goal</p>
						</div>
					{/if}
				</nav>

				<!-- User Profile -->
				<div class="p-4 m-4 bg-slate-50 rounded-xl border border-slate-100">
					<div class="flex items-center gap-3">
						<div class="relative">
							{#if data.user.picture}
								<img src={data.user.picture} alt={data.user.name} class="w-10 h-10 rounded-xl">
							{:else}
								<div class="w-10 h-10 bg-indigo-500 rounded-xl flex items-center justify-center text-white font-medium">
									{data.user.name?.[0] || data.user.email?.[0] || 'U'}
								</div>
							{/if}
							<span class="absolute -bottom-0.5 -right-0.5 w-3 h-3 bg-emerald-500 rounded-full border-2 border-white"></span>
						</div>
						<div class="flex-1 min-w-0">
							<p class="text-sm font-medium text-slate-900 truncate">{data.user.name || 'User'}</p>
							<p class="text-xs text-slate-500 truncate">{data.user.email}</p>
						</div>
						<button
							onclick={handleLogout}
							class="text-slate-400 hover:text-slate-600 p-2 hover:bg-slate-100 rounded-lg transition-colors"
							title="Logout"
						>
							<svg class="w-5 h-5" fill="none" stroke="currentColor" viewBox="0 0 24 24">
								<path stroke-linecap="round" stroke-linejoin="round" stroke-width="2" d="M17 16l4-4m0 0l-4-4m4 4H7m6 4v1a3 3 0 01-3 3H6a3 3 0 01-3-3V7a3 3 0 013-3h4a3 3 0 013 3v1" />
							</svg>
						</button>
					</div>
				</div>

				<!-- Footer Links -->
				<div class="px-4 pb-4 text-center text-xs text-slate-400">
					<p class="mb-1">&copy; {new Date().getFullYear()} CodeLens</p>
					<a href="/privacy" class="hover:text-slate-600">Privacy</a>
					<span class="mx-1">·</span>
					<a href="/terms" class="hover:text-slate-600">Terms</a>
				</div>
			</aside>

			<!-- Main Content -->
			<main class="flex-1 overflow-auto">
				<!-- Desktop Header with Notifications -->
				<div class="hidden lg:flex items-center justify-end gap-3 px-6 py-3 border-b border-slate-100 bg-white/50 backdrop-blur-sm sticky top-0 z-50">
					<NotificationDropdown />
					<a href="/settings" class="flex items-center gap-3 pl-3 border-l border-slate-200 hover:opacity-80 transition-opacity" title="Profile Settings">
						{#if data.user.picture}
							<img src={data.user.picture} alt={data.user.name} class="w-8 h-8 rounded-lg">
						{:else}
							<div class="w-8 h-8 bg-indigo-500 rounded-lg flex items-center justify-center text-white font-medium text-sm">
								{data.user.name?.[0] || data.user.email?.[0] || 'U'}
							</div>
						{/if}
						<span class="text-sm font-medium text-slate-700">{data.user.name || 'User'}</span>
					</a>
				</div>
				{@render children()}
			</main>
		</div>
	{:else}
		<!-- No sidebar for unauthenticated users -->
		<main class="min-h-screen">
			{@render children()}
		</main>
	{/if}
</div>
