<script lang="ts">
	import { goto } from '$app/navigation';
	import { page } from '$app/stores';
	import { onMount } from 'svelte';

	onMount(() => {
		// Redirect to home with error if present
		const error = $page.url.searchParams.get('error');
		if (error) {
			goto('/?error=auth_failed');
		} else {
			goto('/');
		}
	});
</script>

<div class="min-h-screen flex items-center justify-center">
	<p class="text-slate-500">Redirecting...</p>
</div>
