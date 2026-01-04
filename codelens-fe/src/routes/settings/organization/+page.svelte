<script lang="ts">
	import { onMount } from 'svelte';
	import { settings } from '$lib/api/client';
	import type { OrgSettings, UserSettings } from '$lib/api/client';

	let userSettings: UserSettings | null = $state(null);
	let orgSettings: OrgSettings | null = $state(null);
	let loading = $state(true);
	let saving = $state(false);
	let error: string | null = $state(null);
	let success: string | null = $state(null);

	// Form state
	let orgName = $state('');
	let autoReviewEnabled = $state(true);
	let postCommentsEnabled = $state(true);
	let postInlineCommentsEnabled = $state(true);
	let securityScanEnabled = $state(true);
	let staticAnalysisEnabled = $state(true);
	let autoApproveMembers = $state(false);
	let githubToken = $state('');
	let gitlabToken = $state('');
	let gitlabUrl = $state('');

	// Track if tokens have been modified
	let githubTokenModified = $state(false);
	let gitlabTokenModified = $state(false);

	onMount(async () => {
		try {
			userSettings = await settings.getUser();
			if (userSettings.organizationId) {
				orgSettings = await settings.getOrgById(userSettings.organizationId);
				// Populate form
				orgName = orgSettings.name;
				autoReviewEnabled = orgSettings.autoReviewEnabled;
				postCommentsEnabled = orgSettings.postCommentsEnabled;
				postInlineCommentsEnabled = orgSettings.postInlineCommentsEnabled;
				securityScanEnabled = orgSettings.securityScanEnabled;
				staticAnalysisEnabled = orgSettings.staticAnalysisEnabled;
				autoApproveMembers = orgSettings.autoApproveMembers;
				gitlabUrl = orgSettings.gitlabUrl || '';
			}
		} catch (e) {
			error = e instanceof Error ? e.message : 'Failed to load settings';
		} finally {
			loading = false;
		}
	});

	async function saveSettings() {
		if (!orgSettings) return;

		saving = true;
		error = null;
		success = null;

		try {
			const updateData: Record<string, unknown> = {
				name: orgName,
				autoReviewEnabled,
				postCommentsEnabled,
				postInlineCommentsEnabled,
				securityScanEnabled,
				staticAnalysisEnabled,
				autoApproveMembers,
				gitlabUrl: gitlabUrl || null
			};

			// Only include tokens if they were modified
			if (githubTokenModified) {
				updateData.githubToken = githubToken;
			}
			if (gitlabTokenModified) {
				updateData.gitlabToken = gitlabToken;
			}

			orgSettings = await settings.updateOrg(orgSettings.id, updateData);

			// Reset token fields and modification flags
			githubToken = '';
			gitlabToken = '';
			githubTokenModified = false;
			gitlabTokenModified = false;

			success = 'Organization settings saved successfully';
			setTimeout(() => success = null, 3000);
		} catch (e) {
			error = e instanceof Error ? e.message : 'Failed to save settings';
		} finally {
			saving = false;
		}
	}

	function handleGithubTokenInput() {
		githubTokenModified = true;
	}

	function handleGitlabTokenInput() {
		gitlabTokenModified = true;
	}
</script>

<svelte:head>
	<title>Organization Settings - CodeLens</title>
</svelte:head>

{#if loading}
	<div class="flex items-center justify-center h-64">
		<div class="animate-spin rounded-full h-8 w-8 border-b-2 border-primary-700"></div>
	</div>
{:else if !userSettings?.organizationId}
	<div class="card p-6 text-center">
		<svg class="w-16 h-16 mx-auto text-gray-300 mb-4" fill="none" stroke="currentColor" viewBox="0 0 24 24">
			<path stroke-linecap="round" stroke-linejoin="round" stroke-width="1.5" d="M19 21V5a2 2 0 00-2-2H7a2 2 0 00-2 2v16m14 0h2m-2 0h-5m-9 0H3m2 0h5M9 7h1m-1 4h1m4-4h1m-1 4h1m-5 10v-5a1 1 0 011-1h2a1 1 0 011 1v5m-4 0h4" />
		</svg>
		<h3 class="text-lg font-medium text-gray-900 mb-2">No Organization</h3>
		<p class="text-gray-500 mb-4">
			You're not part of an organization yet. Organizations are created automatically when you submit PRs.
		</p>
	</div>
{:else if orgSettings}
	<form onsubmit={(e) => { e.preventDefault(); saveSettings(); }}>
		<!-- General Settings -->
		<div class="card p-6 mb-6">
			<h2 class="text-lg font-semibold mb-4">General</h2>

			<div class="space-y-4">
				<div>
					<label for="orgName" class="block text-sm font-medium text-gray-700 mb-1">
						Organization Name
					</label>
					<input
						type="text"
						id="orgName"
						bind:value={orgName}
						class="input"
						placeholder="My Organization"
					/>
				</div>
			</div>
		</div>

		<!-- Review Settings -->
		<div class="card p-6 mb-6">
			<h2 class="text-lg font-semibold mb-4">Review Settings</h2>
			<p class="text-sm text-gray-600 mb-4">
				Configure default review behavior for all repositories in this organization.
			</p>

			<div class="space-y-4">
				<label class="flex items-center justify-between p-3 bg-gray-50 rounded-lg cursor-pointer">
					<div>
						<p class="font-medium text-gray-900">Auto Review</p>
						<p class="text-sm text-gray-500">Automatically review new PRs when opened</p>
					</div>
					<input
						type="checkbox"
						bind:checked={autoReviewEnabled}
						class="w-5 h-5 text-primary-600 rounded focus:ring-primary-500"
					/>
				</label>

				<label class="flex items-center justify-between p-3 bg-gray-50 rounded-lg cursor-pointer">
					<div>
						<p class="font-medium text-gray-900">Post Summary Comment</p>
						<p class="text-sm text-gray-500">Post a summary table of all issues found to the PR</p>
					</div>
					<input
						type="checkbox"
						bind:checked={postCommentsEnabled}
						class="w-5 h-5 text-primary-600 rounded focus:ring-primary-500"
					/>
				</label>

				<label class="flex items-center justify-between p-3 rounded-lg cursor-pointer {postCommentsEnabled ? 'bg-gray-50' : 'bg-gray-100 opacity-60'}" class:cursor-not-allowed={!postCommentsEnabled}>
					<div class="pl-4 border-l-2 border-gray-300">
						<p class="font-medium text-gray-900">Post Inline Comments</p>
						<p class="text-sm text-gray-500">Post comments directly on specific code lines (for high/critical issues)</p>
					</div>
					<input
						type="checkbox"
						bind:checked={postInlineCommentsEnabled}
						disabled={!postCommentsEnabled}
						class="w-5 h-5 text-primary-600 rounded focus:ring-primary-500 disabled:opacity-50"
					/>
				</label>

				<label class="flex items-center justify-between p-3 bg-gray-50 rounded-lg cursor-pointer">
					<div>
						<p class="font-medium text-gray-900">Security Scan</p>
						<p class="text-sm text-gray-500">Include security vulnerability scanning (CVEs)</p>
					</div>
					<input
						type="checkbox"
						bind:checked={securityScanEnabled}
						class="w-5 h-5 text-primary-600 rounded focus:ring-primary-500"
					/>
				</label>

				<label class="flex items-center justify-between p-3 bg-gray-50 rounded-lg cursor-pointer">
					<div>
						<p class="font-medium text-gray-900">Static Analysis</p>
						<p class="text-sm text-gray-500">Run PMD, Checkstyle, ESLint on code changes</p>
					</div>
					<input
						type="checkbox"
						bind:checked={staticAnalysisEnabled}
						class="w-5 h-5 text-primary-600 rounded focus:ring-primary-500"
					/>
				</label>
			</div>
		</div>

		<!-- Team Settings -->
		<div class="card p-6 mb-6">
			<h2 class="text-lg font-semibold mb-4">Team Settings</h2>
			<p class="text-sm text-gray-600 mb-4">
				Configure how new members join your organization.
			</p>

			<div class="space-y-4">
				<label class="flex items-center justify-between p-3 bg-gray-50 rounded-lg cursor-pointer">
					<div>
						<p class="font-medium text-gray-900">Auto-Approve Members</p>
						<p class="text-sm text-gray-500">
							Automatically approve users with matching email domains to join your organization.
							When disabled, users must be approved by an admin.
						</p>
					</div>
					<input
						type="checkbox"
						bind:checked={autoApproveMembers}
						class="w-5 h-5 text-primary-600 rounded focus:ring-primary-500"
					/>
				</label>
			</div>
		</div>

		<!-- Git Provider Tokens -->
		<div class="card p-6 mb-6">
			<h2 class="text-lg font-semibold mb-4">Git Provider Tokens</h2>
			<p class="text-sm text-gray-600 mb-4">
				Configure access tokens for GitHub and GitLab to enable PR reviews and comment posting.
			</p>

			<div class="space-y-6">
				<!-- GitHub -->
				<div>
					<div class="flex items-center gap-2 mb-2">
						<svg class="w-5 h-5" viewBox="0 0 24 24" fill="currentColor">
							<path d="M12 0c-6.626 0-12 5.373-12 12 0 5.302 3.438 9.8 8.207 11.387.599.111.793-.261.793-.577v-2.234c-3.338.726-4.033-1.416-4.033-1.416-.546-1.387-1.333-1.756-1.333-1.756-1.089-.745.083-.729.083-.729 1.205.084 1.839 1.237 1.839 1.237 1.07 1.834 2.807 1.304 3.492.997.107-.775.418-1.305.762-1.604-2.665-.305-5.467-1.334-5.467-5.931 0-1.311.469-2.381 1.236-3.221-.124-.303-.535-1.524.117-3.176 0 0 1.008-.322 3.301 1.23.957-.266 1.983-.399 3.003-.404 1.02.005 2.047.138 3.006.404 2.291-1.552 3.297-1.23 3.297-1.23.653 1.653.242 2.874.118 3.176.77.84 1.235 1.911 1.235 3.221 0 4.609-2.807 5.624-5.479 5.921.43.372.823 1.102.823 2.222v3.293c0 .319.192.694.801.576 4.765-1.589 8.199-6.086 8.199-11.386 0-6.627-5.373-12-12-12z"/>
						</svg>
						<label for="githubToken" class="text-sm font-medium text-gray-700">GitHub Token</label>
						{#if orgSettings.hasGithubToken}
							<span class="badge bg-green-100 text-green-700 text-xs">Configured</span>
						{/if}
					</div>
					<input
						type="password"
						id="githubToken"
						bind:value={githubToken}
						oninput={handleGithubTokenInput}
						class="input"
						placeholder={orgSettings.hasGithubToken ? '••••••••••••••••' : 'ghp_xxxxxxxxxxxx'}
					/>
					<p class="text-xs text-gray-500 mt-1">
						Personal access token with repo and write:discussion scopes.
						<a href="https://github.com/settings/tokens/new" target="_blank" rel="noopener" class="text-primary-600 hover:underline">
							Create token
						</a>
					</p>
				</div>

				<!-- GitLab -->
				<div>
					<div class="flex items-center gap-2 mb-2">
						<svg class="w-5 h-5" viewBox="0 0 24 24" fill="#FC6D26">
							<path d="M22.65 14.39L12 22.13 1.35 14.39a.84.84 0 0 1-.3-.94l1.22-3.78 2.44-7.51A.42.42 0 0 1 4.82 2a.43.43 0 0 1 .58 0 .42.42 0 0 1 .11.18l2.44 7.49h8.1l2.44-7.51A.42.42 0 0 1 18.6 2a.43.43 0 0 1 .58 0 .42.42 0 0 1 .11.18l2.44 7.51L23 13.45a.84.84 0 0 1-.35.94z"/>
						</svg>
						<label for="gitlabToken" class="text-sm font-medium text-gray-700">GitLab Token</label>
						{#if orgSettings.hasGitlabToken}
							<span class="badge bg-green-100 text-green-700 text-xs">Configured</span>
						{/if}
					</div>
					<input
						type="password"
						id="gitlabToken"
						bind:value={gitlabToken}
						oninput={handleGitlabTokenInput}
						class="input"
						placeholder={orgSettings.hasGitlabToken ? '••••••••••••••••' : 'glpat-xxxxxxxxxxxx'}
					/>
					<p class="text-xs text-gray-500 mt-1">
						Personal access token with api scope.
					</p>
				</div>

				<!-- GitLab URL (for self-hosted) -->
				<div>
					<label for="gitlabUrl" class="block text-sm font-medium text-gray-700 mb-1">
						GitLab URL (optional)
					</label>
					<input
						type="url"
						id="gitlabUrl"
						bind:value={gitlabUrl}
						class="input"
						placeholder="https://gitlab.example.com"
					/>
					<p class="text-xs text-gray-500 mt-1">
						Leave empty for gitlab.com. Set for self-hosted GitLab instances.
					</p>
				</div>
			</div>
		</div>

		<!-- Alerts -->
		{#if error}
			<div class="mb-6 p-3 bg-red-50 border border-red-200 rounded-lg text-red-700 text-sm">
				{error}
			</div>
		{/if}

		{#if success}
			<div class="mb-6 p-3 bg-green-50 border border-green-200 rounded-lg text-green-700 text-sm">
				{success}
			</div>
		{/if}

		<!-- Save Button -->
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
				Save Organization Settings
			{/if}
		</button>
	</form>

	<!-- Warning Box -->
	<div class="mt-6 p-4 bg-amber-50 border border-amber-200 rounded-lg">
		<div class="flex gap-3">
			<svg class="w-5 h-5 text-amber-600 shrink-0 mt-0.5" fill="none" stroke="currentColor" viewBox="0 0 24 24">
				<path stroke-linecap="round" stroke-linejoin="round" stroke-width="2" d="M12 9v2m0 4h.01m-6.938 4h13.856c1.54 0 2.502-1.667 1.732-3L13.732 4c-.77-1.333-2.694-1.333-3.464 0L3.34 16c-.77 1.333.192 3 1.732 3z" />
			</svg>
			<div class="text-sm text-amber-800">
				<p class="font-medium mb-1">Token Security</p>
				<p>Access tokens are stored securely. Never share your tokens or commit them to source control. Use tokens with minimal required permissions.</p>
			</div>
		</div>
	</div>
{/if}
