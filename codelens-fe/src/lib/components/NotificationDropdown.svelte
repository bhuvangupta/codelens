<script lang="ts">
	import { onMount, onDestroy } from 'svelte';
	import { notifications } from '$lib/api/client';
	import type { NotificationItem } from '$lib/api/client';
	import { goto } from '$app/navigation';

	let isOpen = false;
	let notificationItems: NotificationItem[] = [];
	let unreadCount = 0;
	let loading = false;
	let pollInterval: ReturnType<typeof setInterval> | null = null;

	onMount(async () => {
		await fetchUnreadCount();
		// Poll for new notifications every 30 seconds
		pollInterval = setInterval(fetchUnreadCount, 30000);
	});

	onDestroy(() => {
		if (pollInterval) clearInterval(pollInterval);
	});

	async function fetchUnreadCount() {
		try {
			const result = await notifications.getUnreadCount();
			unreadCount = result.count;
		} catch (e) {
			console.error('Failed to fetch notification count:', e);
		}
	}

	async function loadNotifications() {
		if (loading) return;
		loading = true;
		try {
			notificationItems = await notifications.getAll(0, 10);
		} catch (e) {
			console.error('Failed to load notifications:', e);
		} finally {
			loading = false;
		}
	}

	async function toggleDropdown() {
		isOpen = !isOpen;
		if (isOpen) {
			await loadNotifications();
		}
	}

	async function handleMarkAsRead(notification: NotificationItem) {
		if (notification.isRead) return;
		try {
			await notifications.markAsRead(notification.id);
			notification.isRead = true;
			unreadCount = Math.max(0, unreadCount - 1);
			notificationItems = [...notificationItems]; // Trigger reactivity
		} catch (e) {
			console.error('Failed to mark notification as read:', e);
		}
	}

	async function handleMarkAllAsRead() {
		try {
			await notifications.markAllAsRead();
			notificationItems = notificationItems.map(n => ({ ...n, isRead: true }));
			unreadCount = 0;
		} catch (e) {
			console.error('Failed to mark all as read:', e);
		}
	}

	function handleClickNotification(notification: NotificationItem) {
		handleMarkAsRead(notification);
		if (notification.referenceType === 'review' && notification.referenceId) {
			goto(`/reviews/${notification.referenceId}`);
			isOpen = false;
		}
	}

	function handleClickOutside(event: MouseEvent) {
		const target = event.target as HTMLElement;
		if (!target.closest('.notification-dropdown')) {
			isOpen = false;
		}
	}

	function formatTimeAgo(dateStr: string): string {
		const date = new Date(dateStr);
		const now = new Date();
		const diffMs = now.getTime() - date.getTime();
		const diffMins = Math.floor(diffMs / 60000);
		const diffHours = Math.floor(diffMs / 3600000);
		const diffDays = Math.floor(diffMs / 86400000);

		if (diffMins < 1) return 'Just now';
		if (diffMins < 60) return `${diffMins}m ago`;
		if (diffHours < 24) return `${diffHours}h ago`;
		if (diffDays < 7) return `${diffDays}d ago`;
		return date.toLocaleDateString();
	}

	function getNotificationIcon(type: string): { icon: string; bg: string } {
		switch (type) {
			case 'REVIEW_COMPLETED':
				return { icon: 'fa-check-circle', bg: 'bg-green-100 text-green-600' };
			case 'REVIEW_FAILED':
				return { icon: 'fa-times-circle', bg: 'bg-red-100 text-red-600' };
			case 'CRITICAL_ISSUES_FOUND':
				return { icon: 'fa-exclamation-triangle', bg: 'bg-amber-100 text-amber-600' };
			case 'MEMBERSHIP_APPROVED':
				return { icon: 'fa-user-check', bg: 'bg-blue-100 text-blue-600' };
			case 'MEMBERSHIP_REJECTED':
				return { icon: 'fa-user-times', bg: 'bg-gray-100 text-gray-600' };
			default:
				return { icon: 'fa-bell', bg: 'bg-slate-100 text-slate-600' };
		}
	}
</script>

<svelte:window on:click={handleClickOutside} />

<div class="notification-dropdown relative">
	<button
		on:click={toggleDropdown}
		class="relative p-2 text-slate-400 hover:text-slate-600 hover:bg-slate-100/50 rounded-xl transition-all"
		aria-label="Notifications"
	>
		<i class="fas fa-bell text-lg"></i>
		{#if unreadCount > 0}
			<span class="absolute -top-0.5 -right-0.5 bg-red-500 text-white text-xs font-bold rounded-full w-5 h-5 flex items-center justify-center">
				{unreadCount > 9 ? '9+' : unreadCount}
			</span>
		{/if}
	</button>

	{#if isOpen}
		<div class="absolute right-0 mt-2 w-96 bg-white rounded-2xl shadow-xl border border-slate-200 z-[100] overflow-hidden">
			<div class="px-4 py-3 border-b border-slate-100 flex items-center justify-between">
				<h3 class="font-semibold text-slate-900">Notifications</h3>
				{#if unreadCount > 0}
					<button
						on:click={handleMarkAllAsRead}
						class="text-sm text-amber-700 hover:text-amber-800"
					>
						Mark all as read
					</button>
				{/if}
			</div>

			<div class="max-h-96 overflow-y-auto">
				{#if loading}
					<div class="p-8 text-center">
						<div class="animate-spin rounded-full h-6 w-6 border-b-2 border-amber-700 mx-auto"></div>
					</div>
				{:else if notificationItems.length === 0}
					<div class="p-8 text-center text-slate-500">
						<i class="fas fa-bell-slash text-3xl text-slate-300 mb-2"></i>
						<p>No notifications yet</p>
					</div>
				{:else}
					{#each notificationItems as notification}
						<button
							on:click={() => handleClickNotification(notification)}
							class="w-full text-left px-4 py-3 hover:bg-slate-50 transition-colors border-b border-slate-50 last:border-0 {notification.isRead ? 'opacity-60' : ''}"
						>
							<div class="flex gap-3">
								<div class="w-9 h-9 rounded-lg {getNotificationIcon(notification.type).bg} flex items-center justify-center flex-shrink-0">
									<i class="fas {getNotificationIcon(notification.type).icon}"></i>
								</div>
								<div class="flex-1 min-w-0">
									<div class="flex items-center gap-2">
										<span class="font-medium text-slate-900 text-sm truncate">{notification.title}</span>
										{#if !notification.isRead}
											<span class="w-2 h-2 bg-amber-500 rounded-full flex-shrink-0"></span>
										{/if}
									</div>
									<p class="text-sm text-slate-600 line-clamp-2">{notification.message}</p>
									<p class="text-xs text-slate-400 mt-1">{formatTimeAgo(notification.createdAt)}</p>
								</div>
							</div>
						</button>
					{/each}
				{/if}
			</div>

			<div class="px-4 py-3 border-t border-slate-100 bg-slate-50">
				<a
					href="/settings/notifications"
					on:click={() => isOpen = false}
					class="text-sm text-amber-700 hover:text-amber-800 flex items-center gap-2"
				>
					<i class="fas fa-cog"></i>
					Notification settings
				</a>
			</div>
		</div>
	{/if}
</div>
