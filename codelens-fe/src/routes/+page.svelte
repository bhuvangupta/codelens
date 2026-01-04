<script lang="ts">
	import { goto } from '$app/navigation';
	import { page } from '$app/stores';
	import { onMount } from 'svelte';

	let { data } = $props();
	let error: string | null = $state(null);
	let authMessage: string | null = $state(null);
	let redirectPath: string | null = $state(null);

	// Login config passed from server (env/dynamic/public doesn't work on client)
	const googleEnabled = data.loginConfig?.googleEnabled ?? true;
	const githubEnabled = data.loginConfig?.githubEnabled ?? false;

	// Check if already authenticated
	onMount(() => {
		if (data.user) {
			// If there's a redirect path, go there instead of dashboard
			const redirect = $page.url.searchParams.get('redirect');
			goto(redirect || '/dashboard');
			return;
		}

		// Check for auth required message
		const authRequired = $page.url.searchParams.get('auth');
		if (authRequired === 'required') {
			authMessage = 'Please sign in to access this page';
			redirectPath = $page.url.searchParams.get('redirect');
		}

		// Check for error in URL
		const urlError = $page.url.searchParams.get('error');
		if (urlError) {
			if (urlError === 'no_token' || urlError === 'auth_failed') {
				error = 'Authentication failed. Please try again.';
			} else {
				error = decodeURIComponent(urlError);
			}
		}
	});

	// Build login URL with redirect if needed
	function getLoginUrl(provider: string): string {
		let url = `/auth/login?provider=${provider}`;
		if (redirectPath) {
			url += `&redirect=${encodeURIComponent(redirectPath)}`;
		}
		return url;
	}
</script>

<div class="min-h-screen bg-stone-900 overflow-hidden">
	<!-- Background Elements -->
	<div class="absolute inset-0 overflow-hidden">
		<div class="absolute top-1/4 -left-20 w-96 h-96 bg-amber-900/20 rounded-full blur-3xl"></div>
		<div class="absolute bottom-1/4 -right-20 w-96 h-96 bg-amber-800/20 rounded-full blur-3xl"></div>
		<div class="absolute top-1/2 left-1/2 -translate-x-1/2 -translate-y-1/2 w-[600px] h-[600px] bg-amber-700/10 rounded-full blur-3xl"></div>
	</div>

	<div class="relative min-h-screen flex">
		<!-- Left Side - Branding (hidden on mobile) -->
		<div class="hidden lg:flex lg:w-1/2 flex-col justify-center p-12">
			<div>
				<div class="flex items-center gap-3 mb-12">
					<img src="/codelens.png" alt="CodeLens" class="w-12 h-12">
					<span class="text-2xl font-bold text-white">CodeLens</span>
				</div>

				<div class="max-w-md mb-10">
					<h1 class="text-5xl font-bold text-white mb-6 leading-tight">
						AI-powered code reviews in
						<span class="text-transparent bg-clip-text bg-gradient-to-r from-amber-500 to-orange-600">minutes</span>
					</h1>
					<p class="text-xl text-slate-400 leading-relaxed">
						Get instant, intelligent feedback on your pull requests. Find bugs, security issues, and improvements before they reach production.
					</p>
				</div>

				<!-- Features -->
				<div class="grid grid-cols-3 gap-4">
				<div class="bg-white/5 backdrop-blur-sm rounded-2xl p-5 border border-white/10">
					<div class="w-10 h-10 bg-amber-900/30 rounded-xl flex items-center justify-center mb-3">
						<svg class="w-5 h-5 text-amber-500" fill="none" stroke="currentColor" viewBox="0 0 24 24">
							<path stroke-linecap="round" stroke-linejoin="round" stroke-width="2" d="M9.75 17L9 20l-1 1h8l-1-1-.75-3M3 13h18M5 17h14a2 2 0 002-2V5a2 2 0 00-2-2H5a2 2 0 00-2 2v10a2 2 0 002 2z" />
						</svg>
					</div>
					<h3 class="font-medium text-white mb-1">Multi-Model AI</h3>
					<p class="text-sm text-slate-500">GLM, Claude, Gemini & Ollama</p>
				</div>
				<div class="bg-white/5 backdrop-blur-sm rounded-2xl p-5 border border-white/10">
					<div class="w-10 h-10 bg-stone-500/20 rounded-xl flex items-center justify-center mb-3">
						<svg class="w-5 h-5 text-stone-300" fill="none" stroke="currentColor" viewBox="0 0 24 24">
							<path stroke-linecap="round" stroke-linejoin="round" stroke-width="2" d="M9 12.75L11.25 15 15 9.75m-3-7.036A11.959 11.959 0 013.598 6 11.99 11.99 0 003 9.749c0 5.592 3.824 10.29 9 11.623 5.176-1.332 9-6.03 9-11.622 0-1.31-.21-2.571-.598-3.751h-.152c-3.196 0-6.1-1.248-8.25-3.285z" />
						</svg>
					</div>
					<h3 class="font-medium text-white mb-1">Security Scan</h3>
					<p class="text-sm text-slate-500">CVE & vulnerability detection</p>
				</div>
				<div class="bg-white/5 backdrop-blur-sm rounded-2xl p-5 border border-white/10">
					<div class="w-10 h-10 bg-stone-500/20 rounded-xl flex items-center justify-center mb-3">
						<svg class="w-5 h-5 text-stone-300" fill="none" stroke="currentColor" viewBox="0 0 24 24">
							<path stroke-linecap="round" stroke-linejoin="round" stroke-width="2" d="M13 10V3L4 14h7v7l9-11h-7z" />
						</svg>
					</div>
					<h3 class="font-medium text-white mb-1">Lightning Fast</h3>
					<p class="text-sm text-slate-500">Reviews in 2-5 minutes</p>
				</div>
				</div>
			</div>
		</div>

		<!-- Right Side - Login Form -->
		<div class="w-full lg:w-1/2 flex items-center justify-center p-4 sm:p-8">
			<div class="w-full max-w-md">
				<!-- Mobile Logo -->
				<div class="lg:hidden flex items-center justify-center gap-3 mb-8">
					<img src="/codelens.png" alt="CodeLens" class="w-12 h-12">
					<span class="text-2xl font-bold text-white">CodeLens</span>
				</div>

				<!-- Mobile Tagline -->
				<div class="lg:hidden text-center mb-8">
					<h1 class="text-2xl font-bold text-white mb-2">
						AI-powered code reviews
					</h1>
					<p class="text-slate-400">
						Find bugs and security issues before they reach production.
					</p>
				</div>

				<!-- Login Card -->
				<div class="bg-stone-800/50 backdrop-blur-xl rounded-3xl p-6 sm:p-8 border border-white/10 shadow-2xl shadow-amber-900/20">
					<div class="text-center mb-8">
						<h2 class="text-2xl font-bold text-white mb-2">Welcome</h2>
						<p class="text-slate-400">Sign in to continue to CodeLens</p>
					</div>

					{#if authMessage}
						<div class="mb-4 p-3 bg-amber-900/30 border border-amber-700/50 rounded-lg text-amber-300 text-sm flex items-center gap-2">
							<svg class="w-5 h-5 flex-shrink-0" fill="none" stroke="currentColor" viewBox="0 0 24 24">
								<path stroke-linecap="round" stroke-linejoin="round" stroke-width="2" d="M12 9v2m0 4h.01m-6.938 4h13.856c1.54 0 2.502-1.667 1.732-3L13.732 4c-.77-1.333-2.694-1.333-3.464 0L3.34 16c-.77 1.333.192 3 1.732 3z" />
							</svg>
							{authMessage}
						</div>
					{/if}

					{#if error}
						<div class="mb-4 p-3 bg-red-900/30 border border-red-700/50 rounded-lg text-red-300 text-sm">
							{error}
						</div>
					{/if}

					<div class="space-y-3">
						{#if googleEnabled}
							<!-- Google Login -->
							<a
								href={getLoginUrl('google')}
								rel="external"
								class="flex items-center justify-center gap-3 w-full px-4 py-4 bg-white rounded-xl hover:bg-gray-50 transition-all group"
							>
								<svg class="w-5 h-5" viewBox="0 0 24 24">
									<path
										fill="#4285F4"
										d="M22.56 12.25c0-.78-.07-1.53-.2-2.25H12v4.26h5.92c-.26 1.37-1.04 2.53-2.21 3.31v2.77h3.57c2.08-1.92 3.28-4.74 3.28-8.09z"
									/>
									<path
										fill="#34A853"
										d="M12 23c2.97 0 5.46-.98 7.28-2.66l-3.57-2.77c-.98.66-2.23 1.06-3.71 1.06-2.86 0-5.29-1.93-6.16-4.53H2.18v2.84C3.99 20.53 7.7 23 12 23z"
									/>
									<path
										fill="#FBBC05"
										d="M5.84 14.09c-.22-.66-.35-1.36-.35-2.09s.13-1.43.35-2.09V7.07H2.18C1.43 8.55 1 10.22 1 12s.43 3.45 1.18 4.93l2.85-2.22.81-.62z"
									/>
									<path
										fill="#EA4335"
										d="M12 5.38c1.62 0 3.06.56 4.21 1.64l3.15-3.15C17.45 2.09 14.97 1 12 1 7.7 1 3.99 3.47 2.18 7.07l3.66 2.84c.87-2.6 3.3-4.53 6.16-4.53z"
									/>
								</svg>
								<span class="font-medium text-gray-700">Continue with Google</span>
								<svg class="w-4 h-4 text-gray-400 group-hover:translate-x-1 transition-transform" fill="none" stroke="currentColor" viewBox="0 0 24 24">
									<path stroke-linecap="round" stroke-linejoin="round" stroke-width="2" d="M9 5l7 7-7 7" />
								</svg>
							</a>
						{/if}

						{#if githubEnabled}
							<!-- GitHub Login -->
							<a
								href={getLoginUrl('github')}
								rel="external"
								class="flex items-center justify-center gap-3 w-full px-4 py-4 bg-slate-800 border border-slate-700 rounded-xl hover:bg-slate-700 transition-all group"
							>
								<svg class="w-5 h-5 text-white" viewBox="0 0 24 24" fill="currentColor">
									<path d="M12 0c-6.626 0-12 5.373-12 12 0 5.302 3.438 9.8 8.207 11.387.599.111.793-.261.793-.577v-2.234c-3.338.726-4.033-1.416-4.033-1.416-.546-1.387-1.333-1.756-1.333-1.756-1.089-.745.083-.729.083-.729 1.205.084 1.839 1.237 1.839 1.237 1.07 1.834 2.807 1.304 3.492.997.107-.775.418-1.305.762-1.604-2.665-.305-5.467-1.334-5.467-5.931 0-1.311.469-2.381 1.236-3.221-.124-.303-.535-1.524.117-3.176 0 0 1.008-.322 3.301 1.23.957-.266 1.983-.399 3.003-.404 1.02.005 2.047.138 3.006.404 2.291-1.552 3.297-1.23 3.297-1.23.653 1.653.242 2.874.118 3.176.77.84 1.235 1.911 1.235 3.221 0 4.609-2.807 5.624-5.479 5.921.43.372.823 1.102.823 2.222v3.293c0 .319.192.694.801.576 4.765-1.589 8.199-6.086 8.199-11.386 0-6.627-5.373-12-12-12z"/>
								</svg>
								<span class="font-medium text-white">Continue with GitHub</span>
								<svg class="w-4 h-4 text-slate-500 group-hover:translate-x-1 transition-transform" fill="none" stroke="currentColor" viewBox="0 0 24 24">
									<path stroke-linecap="round" stroke-linejoin="round" stroke-width="2" d="M9 5l7 7-7 7" />
								</svg>
							</a>
						{/if}
					</div>

					<p class="text-center text-sm text-slate-500 mt-6">
						By signing in, you agree to our
						<a href="/terms" class="text-amber-500 hover:text-amber-400 transition-colors">Terms</a>
						and
						<a href="/privacy" class="text-amber-500 hover:text-amber-400 transition-colors">Privacy Policy</a>.
					</p>
				</div>

				<!-- Mobile Features -->
				<div class="lg:hidden grid grid-cols-3 gap-3 mt-6">
					<div class="bg-white/5 backdrop-blur-sm rounded-xl p-3 border border-white/10 text-center">
						<svg class="w-5 h-5 text-amber-500 mx-auto mb-1" fill="none" stroke="currentColor" viewBox="0 0 24 24">
							<path stroke-linecap="round" stroke-linejoin="round" stroke-width="2" d="M9.75 17L9 20l-1 1h8l-1-1-.75-3M3 13h18M5 17h14a2 2 0 002-2V5a2 2 0 00-2-2H5a2 2 0 00-2 2v10a2 2 0 002 2z" />
						</svg>
						<p class="text-xs text-slate-400">Multi-Model AI</p>
					</div>
					<div class="bg-white/5 backdrop-blur-sm rounded-xl p-3 border border-white/10 text-center">
						<svg class="w-5 h-5 text-stone-300 mx-auto mb-1" fill="none" stroke="currentColor" viewBox="0 0 24 24">
							<path stroke-linecap="round" stroke-linejoin="round" stroke-width="2" d="M9 12.75L11.25 15 15 9.75m-3-7.036A11.959 11.959 0 013.598 6 11.99 11.99 0 003 9.749c0 5.592 3.824 10.29 9 11.623 5.176-1.332 9-6.03 9-11.622 0-1.31-.21-2.571-.598-3.751h-.152c-3.196 0-6.1-1.248-8.25-3.285z" />
						</svg>
						<p class="text-xs text-slate-400">Security Scan</p>
					</div>
					<div class="bg-white/5 backdrop-blur-sm rounded-xl p-3 border border-white/10 text-center">
						<svg class="w-5 h-5 text-stone-300 mx-auto mb-1" fill="none" stroke="currentColor" viewBox="0 0 24 24">
							<path stroke-linecap="round" stroke-linejoin="round" stroke-width="2" d="M13 10V3L4 14h7v7l9-11h-7z" />
						</svg>
						<p class="text-xs text-slate-400">Lightning Fast</p>
					</div>
				</div>
			</div>
		</div>
	</div>

	<!-- Footer -->
	<footer class="absolute bottom-0 left-0 right-0 py-4 text-center text-sm text-slate-500">
		<p>&copy; {new Date().getFullYear()} CodeLens. All rights reserved.</p>
	</footer>
</div>
