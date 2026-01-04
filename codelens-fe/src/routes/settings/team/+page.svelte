<script lang="ts">
	import { onMount } from 'svelte';
	import { team, settings, membershipRequests } from '$lib/api/client';
	import type { OrgMember, UserSettings, MembershipRequest } from '$lib/api/client';

	let userSettings: UserSettings | null = $state(null);
	let members: OrgMember[] = $state([]);
	let pendingRequests: MembershipRequest[] = $state([]);
	let loading = $state(true);
	let error: string | null = $state(null);
	let processingRequest: string | null = $state(null);

	// Add member form
	let showAddForm = $state(false);
	let addEmail = $state('');
	let addName = $state('');
	let addRole: 'ADMIN' | 'MEMBER' | 'VIEWER' = $state('MEMBER');
	let addGithubUsername = $state('');
	let addGitlabUsername = $state('');
	let adding = $state(false);
	let addError: string | null = $state(null);
	let addSuccess: string | null = $state(null);

	// Edit member
	let editingMember: OrgMember | null = $state(null);
	let editRole: 'ADMIN' | 'MEMBER' | 'VIEWER' = $state('MEMBER');
	let editGithubUsername = $state('');
	let editGitlabUsername = $state('');
	let saving = $state(false);

	$effect(() => {
		if (userSettings?.role !== 'ADMIN') {
			showAddForm = false;
		}
	});

	onMount(async () => {
		await loadData();
	});

	async function loadData() {
		try {
			loading = true;
			error = null;
			userSettings = await settings.getUser();
			if (userSettings.organizationId) {
				members = await team.getMembers(userSettings.organizationId);
				// Load pending requests for admins
				if (userSettings.role === 'ADMIN') {
					try {
						pendingRequests = await membershipRequests.getPending(userSettings.organizationId);
					} catch {
						pendingRequests = [];
					}
				}
			}
		} catch (e) {
			error = e instanceof Error ? e.message : 'Failed to load team members';
		} finally {
			loading = false;
		}
	}

	async function approveRequest(requestId: string) {
		if (!userSettings?.organizationId) return;
		try {
			processingRequest = requestId;
			await membershipRequests.approve(userSettings.organizationId, requestId);
			await loadData();
		} catch (e) {
			error = e instanceof Error ? e.message : 'Failed to approve request';
		} finally {
			processingRequest = null;
		}
	}

	async function rejectRequest(requestId: string, userName: string) {
		if (!confirm(`Are you sure you want to reject the membership request from ${userName}?`)) return;
		if (!userSettings?.organizationId) return;
		try {
			processingRequest = requestId;
			await membershipRequests.reject(userSettings.organizationId, requestId);
			await loadData();
		} catch (e) {
			error = e instanceof Error ? e.message : 'Failed to reject request';
		} finally {
			processingRequest = null;
		}
	}

	async function addMember() {
		if (!addEmail.trim() || !userSettings?.organizationId) return;

		try {
			adding = true;
			addError = null;
			addSuccess = null;
			await team.addMember(userSettings.organizationId, {
				email: addEmail,
				name: addName || undefined,
				role: addRole,
				githubUsername: addGithubUsername || undefined,
				gitlabUsername: addGitlabUsername || undefined
			});
			addSuccess = `Added ${addEmail} to the team`;
			resetAddForm();
			await loadData();
		} catch (e) {
			addError = e instanceof Error ? e.message : 'Failed to add member';
		} finally {
			adding = false;
		}
	}

	function resetAddForm() {
		addEmail = '';
		addName = '';
		addRole = 'MEMBER';
		addGithubUsername = '';
		addGitlabUsername = '';
		showAddForm = false;
	}

	function startEdit(member: OrgMember) {
		editingMember = member;
		editRole = member.role;
		editGithubUsername = member.githubUsername || '';
		editGitlabUsername = member.gitlabUsername || '';
	}

	function cancelEdit() {
		editingMember = null;
	}

	async function saveMember() {
		if (!editingMember || !userSettings?.organizationId) return;

		try {
			saving = true;
			await team.updateMember(userSettings.organizationId, editingMember.id, {
				role: editRole,
				githubUsername: editGithubUsername || undefined,
				gitlabUsername: editGitlabUsername || undefined
			});
			editingMember = null;
			await loadData();
		} catch (e) {
			error = e instanceof Error ? e.message : 'Failed to update member';
		} finally {
			saving = false;
		}
	}

	async function removeMember(memberId: string, memberName: string) {
		if (!confirm(`Are you sure you want to remove ${memberName} from the team?`)) return;
		if (!userSettings?.organizationId) return;

		try {
			await team.removeMember(userSettings.organizationId, memberId);
			await loadData();
		} catch (e) {
			error = e instanceof Error ? e.message : 'Failed to remove member';
		}
	}

	function getRoleBadgeClass(role: string): string {
		switch (role) {
			case 'ADMIN': return 'bg-purple-100 text-purple-800';
			case 'MEMBER': return 'bg-blue-100 text-blue-800';
			case 'VIEWER': return 'bg-gray-100 text-gray-800';
			default: return 'bg-gray-100 text-gray-800';
		}
	}

	function isCurrentUser(member: OrgMember): boolean {
		return userSettings?.email === member.email;
	}

	function canManageMembers(): boolean {
		return userSettings?.role === 'ADMIN';
	}
</script>

<svelte:head>
	<title>Team - Settings - CodeLens</title>
</svelte:head>

{#if loading}
	<div class="flex items-center justify-center h-64">
		<div class="animate-spin rounded-full h-8 w-8 border-b-2 border-primary-700"></div>
	</div>
{:else if !userSettings?.organizationId}
	<div class="card p-6 text-center">
		<svg class="w-16 h-16 mx-auto text-gray-300 mb-4" fill="none" stroke="currentColor" viewBox="0 0 24 24">
			<path stroke-linecap="round" stroke-linejoin="round" stroke-width="1.5" d="M17 20h5v-2a3 3 0 00-5.356-1.857M17 20H7m10 0v-2c0-.656-.126-1.283-.356-1.857M7 20H2v-2a3 3 0 015.356-1.857M7 20v-2c0-.656.126-1.283.356-1.857m0 0a5.002 5.002 0 019.288 0M15 7a3 3 0 11-6 0 3 3 0 016 0zm6 3a2 2 0 11-4 0 2 2 0 014 0zM7 10a2 2 0 11-4 0 2 2 0 014 0z" />
		</svg>
		<h3 class="text-lg font-medium text-gray-900 mb-2">No Organization</h3>
		<p class="text-gray-500 mb-4">
			You're not part of an organization yet. Organizations are created automatically when you submit PRs from your company's repositories.
		</p>
	</div>
{:else}
	<div class="space-y-6">
		<!-- Header with Add Button -->
		{#if canManageMembers()}
			<div class="flex justify-between items-center">
				<div>
					<h2 class="text-lg font-semibold">Team Members</h2>
					<p class="text-sm text-gray-500">Manage who has access to your organization</p>
				</div>
				<button
					onclick={() => showAddForm = !showAddForm}
					class="btn btn-primary flex items-center gap-2"
				>
					<svg class="w-4 h-4" fill="none" stroke="currentColor" viewBox="0 0 24 24">
						<path stroke-linecap="round" stroke-linejoin="round" stroke-width="2" d="M12 6v6m0 0v6m0-6h6m-6 0H6" />
					</svg>
					Add Member
				</button>
			</div>
		{:else}
			<div>
				<h2 class="text-lg font-semibold">Team Members</h2>
				<p class="text-sm text-gray-500">View your organization's team members</p>
			</div>
		{/if}

		<!-- Add Member Form -->
		{#if showAddForm && canManageMembers()}
			<div class="card p-6">
				<h3 class="text-md font-semibold mb-4">Add New Member</h3>
				<form onsubmit={(e) => { e.preventDefault(); addMember(); }} class="space-y-4">
					<div class="grid grid-cols-1 md:grid-cols-2 gap-4">
						<div>
							<label for="addEmail" class="block text-sm font-medium text-gray-700 mb-1">
								Email <span class="text-red-500">*</span>
							</label>
							<input
								type="email"
								id="addEmail"
								bind:value={addEmail}
								placeholder="colleague@company.com"
								class="input"
								required
							/>
						</div>
						<div>
							<label for="addName" class="block text-sm font-medium text-gray-700 mb-1">
								Name
							</label>
							<input
								type="text"
								id="addName"
								bind:value={addName}
								placeholder="John Doe"
								class="input"
							/>
						</div>
						<div>
							<label for="addRole" class="block text-sm font-medium text-gray-700 mb-1">
								Role
							</label>
							<select id="addRole" bind:value={addRole} class="input">
								<option value="VIEWER">Viewer</option>
								<option value="MEMBER">Member</option>
								<option value="ADMIN">Admin</option>
							</select>
						</div>
						<div>
							<label for="addGithub" class="block text-sm font-medium text-gray-700 mb-1">
								<span class="flex items-center gap-1">
									<svg class="w-4 h-4" viewBox="0 0 24 24" fill="currentColor">
										<path d="M12 0c-6.626 0-12 5.373-12 12 0 5.302 3.438 9.8 8.207 11.387.599.111.793-.261.793-.577v-2.234c-3.338.726-4.033-1.416-4.033-1.416-.546-1.387-1.333-1.756-1.333-1.756-1.089-.745.083-.729.083-.729 1.205.084 1.839 1.237 1.839 1.237 1.07 1.834 2.807 1.304 3.492.997.107-.775.418-1.305.762-1.604-2.665-.305-5.467-1.334-5.467-5.931 0-1.311.469-2.381 1.236-3.221-.124-.303-.535-1.524.117-3.176 0 0 1.008-.322 3.301 1.23.957-.266 1.983-.399 3.003-.404 1.02.005 2.047.138 3.006.404 2.291-1.552 3.297-1.23 3.297-1.23.653 1.653.242 2.874.118 3.176.77.84 1.235 1.911 1.235 3.221 0 4.609-2.807 5.624-5.479 5.921.43.372.823 1.102.823 2.222v3.293c0 .319.192.694.801.576 4.765-1.589 8.199-6.086 8.199-11.386 0-6.627-5.373-12-12-12z"/>
									</svg>
									GitHub Username
								</span>
							</label>
							<input
								type="text"
								id="addGithub"
								bind:value={addGithubUsername}
								placeholder="octocat"
								class="input"
							/>
						</div>
						<div>
							<label for="addGitlab" class="block text-sm font-medium text-gray-700 mb-1">
								<span class="flex items-center gap-1">
									<svg class="w-4 h-4" viewBox="0 0 24 24" fill="#FC6D26">
										<path d="M22.65 14.39L12 22.13 1.35 14.39a.84.84 0 0 1-.3-.94l1.22-3.78 2.44-7.51A.42.42 0 0 1 4.82 2a.43.43 0 0 1 .58 0 .42.42 0 0 1 .11.18l2.44 7.49h8.1l2.44-7.51A.42.42 0 0 1 18.6 2a.43.43 0 0 1 .58 0 .42.42 0 0 1 .11.18l2.44 7.51L23 13.45a.84.84 0 0 1-.35.94z"/>
									</svg>
									GitLab Username
								</span>
							</label>
							<input
								type="text"
								id="addGitlab"
								bind:value={addGitlabUsername}
								placeholder="tanuki"
								class="input"
							/>
						</div>
					</div>

					{#if addError}
						<div class="p-3 bg-red-50 text-red-700 rounded-lg text-sm">
							{addError}
						</div>
					{/if}

					{#if addSuccess}
						<div class="p-3 bg-green-50 text-green-700 rounded-lg text-sm">
							{addSuccess}
						</div>
					{/if}

					<div class="flex gap-3">
						<button
							type="submit"
							disabled={adding || !addEmail.trim()}
							class="btn btn-primary"
						>
							{#if adding}
								<span class="inline-flex items-center gap-2">
									<div class="animate-spin rounded-full h-4 w-4 border-b-2 border-white"></div>
									Adding...
								</span>
							{:else}
								Add Member
							{/if}
						</button>
						<button
							type="button"
							onclick={resetAddForm}
							class="btn btn-secondary"
						>
							Cancel
						</button>
					</div>
				</form>

				<div class="mt-4 p-4 bg-gray-50 rounded-lg">
					<h4 class="text-sm font-medium text-gray-900 mb-2">Role Permissions</h4>
					<ul class="text-sm text-gray-600 space-y-1">
						<li><strong>Admin:</strong> Full access, manage team, change settings</li>
						<li><strong>Member:</strong> Submit and view reviews, see analytics</li>
						<li><strong>Viewer:</strong> View reviews and analytics only</li>
					</ul>
				</div>
			</div>
		{/if}

		<!-- Error -->
		{#if error}
			<div class="p-4 bg-red-50 text-red-700 rounded-lg">
				{error}
				<button onclick={() => error = null} class="ml-2 text-red-500 hover:text-red-700">&times;</button>
			</div>
		{/if}

		<!-- Pending Membership Requests -->
		{#if canManageMembers() && pendingRequests.length > 0}
			<div class="card border-2 border-amber-200 bg-amber-50">
				<div class="p-4 border-b border-amber-200">
					<div class="flex items-center gap-2">
						<svg class="w-5 h-5 text-amber-600" fill="none" stroke="currentColor" viewBox="0 0 24 24">
							<path stroke-linecap="round" stroke-linejoin="round" stroke-width="2" d="M12 8v4l3 3m6-3a9 9 0 11-18 0 9 9 0 0118 0z" />
						</svg>
						<h3 class="font-semibold text-amber-800">
							Pending Membership Requests
							<span class="ml-2 px-2 py-0.5 bg-amber-200 text-amber-800 rounded-full text-sm">{pendingRequests.length}</span>
						</h3>
					</div>
					<p class="text-sm text-amber-700 mt-1">
						Users with matching email domains waiting for approval to join your organization
					</p>
				</div>
				<div class="divide-y divide-amber-200">
					{#each pendingRequests as request}
						<div class="p-4 flex items-center justify-between">
							<div class="flex items-center gap-4">
								{#if request.userAvatarUrl}
									<img src={request.userAvatarUrl} alt={request.userName} class="w-10 h-10 rounded-full" />
								{:else}
									<div class="w-10 h-10 rounded-full bg-amber-200 flex items-center justify-center text-amber-700 font-medium">
										{request.userName.charAt(0).toUpperCase()}
									</div>
								{/if}
								<div>
									<p class="font-medium text-gray-900">{request.userName}</p>
									<p class="text-sm text-gray-500">{request.userEmail}</p>
									<p class="text-xs text-gray-400 mt-1">
										Requested {new Date(request.requestedAt).toLocaleDateString()} at {new Date(request.requestedAt).toLocaleTimeString()}
									</p>
								</div>
							</div>
							<div class="flex items-center gap-2">
								<button
									onclick={() => approveRequest(request.id)}
									disabled={processingRequest === request.id}
									class="btn btn-sm bg-green-600 text-white hover:bg-green-700 flex items-center gap-1"
								>
									{#if processingRequest === request.id}
										<div class="animate-spin rounded-full h-3 w-3 border-b-2 border-white"></div>
									{:else}
										<svg class="w-4 h-4" fill="none" stroke="currentColor" viewBox="0 0 24 24">
											<path stroke-linecap="round" stroke-linejoin="round" stroke-width="2" d="M5 13l4 4L19 7" />
										</svg>
									{/if}
									Approve
								</button>
								<button
									onclick={() => rejectRequest(request.id, request.userName)}
									disabled={processingRequest === request.id}
									class="btn btn-sm bg-red-600 text-white hover:bg-red-700 flex items-center gap-1"
								>
									<svg class="w-4 h-4" fill="none" stroke="currentColor" viewBox="0 0 24 24">
										<path stroke-linecap="round" stroke-linejoin="round" stroke-width="2" d="M6 18L18 6M6 6l12 12" />
									</svg>
									Reject
								</button>
							</div>
						</div>
					{/each}
				</div>
			</div>
		{/if}

		<!-- Members List -->
		<div class="card">
			{#if members.length === 0}
				<div class="p-8 text-center text-gray-500">
					<svg class="w-12 h-12 mx-auto mb-4 text-gray-300" fill="none" stroke="currentColor" viewBox="0 0 24 24">
						<path stroke-linecap="round" stroke-linejoin="round" stroke-width="2" d="M12 4.354a4 4 0 110 5.292M15 21H3v-1a6 6 0 0112 0v1zm0 0h6v-1a6 6 0 00-9-5.197M13 7a4 4 0 11-8 0 4 4 0 018 0z" />
					</svg>
					<p>No team members yet.</p>
					{#if canManageMembers()}
						<p class="text-sm mt-1">Click "Add Member" to invite your first team member.</p>
					{/if}
				</div>
			{:else}
				<div class="divide-y divide-gray-100">
					{#each members as member}
						<div class="p-4 hover:bg-gray-50">
							{#if editingMember?.id === member.id}
								<!-- Edit Mode -->
								<div class="space-y-4">
									<div class="flex items-center gap-4">
										{#if member.avatarUrl}
											<img src={member.avatarUrl} alt={member.name} class="w-10 h-10 rounded-full" />
										{:else}
											<div class="w-10 h-10 rounded-full bg-primary-100 flex items-center justify-center text-primary-700 font-medium">
												{member.name.charAt(0).toUpperCase()}
											</div>
										{/if}
										<div>
											<p class="font-medium text-gray-900">{member.name}</p>
											<p class="text-sm text-gray-500">{member.email}</p>
										</div>
									</div>
									<div class="grid grid-cols-1 md:grid-cols-3 gap-4 ml-14">
										<div>
											<label class="block text-sm font-medium text-gray-700 mb-1">Role</label>
											<select bind:value={editRole} class="input">
												<option value="VIEWER">Viewer</option>
												<option value="MEMBER">Member</option>
												<option value="ADMIN">Admin</option>
											</select>
										</div>
										<div>
											<label class="block text-sm font-medium text-gray-700 mb-1">GitHub</label>
											<input
												type="text"
												bind:value={editGithubUsername}
												placeholder="octocat"
												class="input"
											/>
										</div>
										<div>
											<label class="block text-sm font-medium text-gray-700 mb-1">GitLab</label>
											<input
												type="text"
												bind:value={editGitlabUsername}
												placeholder="tanuki"
												class="input"
											/>
										</div>
									</div>
									<div class="flex gap-2 ml-14">
										<button
											onclick={saveMember}
											disabled={saving}
											class="btn btn-primary btn-sm"
										>
											{saving ? 'Saving...' : 'Save'}
										</button>
										<button
											onclick={cancelEdit}
											class="btn btn-secondary btn-sm"
										>
											Cancel
										</button>
									</div>
								</div>
							{:else}
								<!-- View Mode -->
								<div class="flex items-center justify-between">
									<div class="flex items-center gap-4">
										{#if member.avatarUrl}
											<img src={member.avatarUrl} alt={member.name} class="w-10 h-10 rounded-full" />
										{:else}
											<div class="w-10 h-10 rounded-full bg-primary-100 flex items-center justify-center text-primary-700 font-medium">
												{member.name.charAt(0).toUpperCase()}
											</div>
										{/if}
										<div>
											<div class="flex items-center gap-2">
												<p class="font-medium text-gray-900">{member.name}</p>
												{#if isCurrentUser(member)}
													<span class="text-xs text-gray-400">(you)</span>
												{/if}
											</div>
											<p class="text-sm text-gray-500">{member.email}</p>
											<div class="flex items-center gap-3 mt-1 text-xs text-gray-400">
												{#if member.githubUsername}
													<span class="flex items-center gap-1">
														<svg class="w-3 h-3" viewBox="0 0 24 24" fill="currentColor">
															<path d="M12 0c-6.626 0-12 5.373-12 12 0 5.302 3.438 9.8 8.207 11.387.599.111.793-.261.793-.577v-2.234c-3.338.726-4.033-1.416-4.033-1.416-.546-1.387-1.333-1.756-1.333-1.756-1.089-.745.083-.729.083-.729 1.205.084 1.839 1.237 1.839 1.237 1.07 1.834 2.807 1.304 3.492.997.107-.775.418-1.305.762-1.604-2.665-.305-5.467-1.334-5.467-5.931 0-1.311.469-2.381 1.236-3.221-.124-.303-.535-1.524.117-3.176 0 0 1.008-.322 3.301 1.23.957-.266 1.983-.399 3.003-.404 1.02.005 2.047.138 3.006.404 2.291-1.552 3.297-1.23 3.297-1.23.653 1.653.242 2.874.118 3.176.77.84 1.235 1.911 1.235 3.221 0 4.609-2.807 5.624-5.479 5.921.43.372.823 1.102.823 2.222v3.293c0 .319.192.694.801.576 4.765-1.589 8.199-6.086 8.199-11.386 0-6.627-5.373-12-12-12z"/>
														</svg>
														{member.githubUsername}
													</span>
												{/if}
												{#if member.gitlabUsername}
													<span class="flex items-center gap-1">
														<svg class="w-3 h-3" viewBox="0 0 24 24" fill="#FC6D26">
															<path d="M22.65 14.39L12 22.13 1.35 14.39a.84.84 0 0 1-.3-.94l1.22-3.78 2.44-7.51A.42.42 0 0 1 4.82 2a.43.43 0 0 1 .58 0 .42.42 0 0 1 .11.18l2.44 7.49h8.1l2.44-7.51A.42.42 0 0 1 18.6 2a.43.43 0 0 1 .58 0 .42.42 0 0 1 .11.18l2.44 7.51L23 13.45a.84.84 0 0 1-.35.94z"/>
														</svg>
														{member.gitlabUsername}
													</span>
												{/if}
												{#if member.lastLoginAt}
													<span>Last login: {new Date(member.lastLoginAt).toLocaleDateString()}</span>
												{:else}
													<span>Never logged in</span>
												{/if}
											</div>
										</div>
									</div>

									<div class="flex items-center gap-3">
										<span class="badge {getRoleBadgeClass(member.role)}">{member.role}</span>

										{#if canManageMembers() && !isCurrentUser(member)}
											<button
												onclick={() => startEdit(member)}
												class="p-2 text-gray-400 hover:text-primary-600 transition-colors"
												title="Edit member"
											>
												<svg class="w-4 h-4" fill="none" stroke="currentColor" viewBox="0 0 24 24">
													<path stroke-linecap="round" stroke-linejoin="round" stroke-width="2" d="M11 5H6a2 2 0 00-2 2v11a2 2 0 002 2h11a2 2 0 002-2v-5m-1.414-9.414a2 2 0 112.828 2.828L11.828 15H9v-2.828l8.586-8.586z" />
												</svg>
											</button>
											<button
												onclick={() => removeMember(member.id, member.name)}
												class="p-2 text-gray-400 hover:text-red-600 transition-colors"
												title="Remove member"
											>
												<svg class="w-4 h-4" fill="none" stroke="currentColor" viewBox="0 0 24 24">
													<path stroke-linecap="round" stroke-linejoin="round" stroke-width="2" d="M19 7l-.867 12.142A2 2 0 0116.138 21H7.862a2 2 0 01-1.995-1.858L5 7m5 4v6m4-6v6m1-10V4a1 1 0 00-1-1h-4a1 1 0 00-1 1v3M4 7h16" />
												</svg>
											</button>
										{/if}
									</div>
								</div>
							{/if}
						</div>
					{/each}
				</div>
			{/if}
		</div>

		<!-- Info Box -->
		{#if canManageMembers()}
			<div class="p-4 bg-blue-50 border border-blue-200 rounded-lg">
				<div class="flex gap-3">
					<svg class="w-5 h-5 text-blue-600 shrink-0 mt-0.5" fill="none" stroke="currentColor" viewBox="0 0 24 24">
						<path stroke-linecap="round" stroke-linejoin="round" stroke-width="2" d="M13 16h-1v-4h-1m1-4h.01M21 12a9 9 0 11-18 0 9 9 0 0118 0z" />
					</svg>
					<div class="text-sm text-blue-800">
						<p class="font-medium mb-1">Member Association</p>
						<p>Users are automatically associated with your organization when they log in with an email matching your organization's domain. Use GitHub/GitLab usernames to match PR authors with team members.</p>
					</div>
				</div>
			</div>
		{/if}
	</div>
{/if}
