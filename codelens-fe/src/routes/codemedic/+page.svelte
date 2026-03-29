<script lang="ts">
	import { onMount } from 'svelte';
	import { codeMedicStore } from '$lib/stores/codemedic';
	import {
		codeMedicApi,
		streamFix,
		type ErrorCluster
	} from '$lib/api/codemedic';

	// Subscribe to store
	let state = $derived($codeMedicStore);

	// Local UI state (doesn't need persistence)
	let isAnalyzing = $state(false);
	let isUploading = $state(false);
	let uploadProgress = $state(0);
	let diffLoading = $state(false);
	let actionStatus: 'idle' | 'committing' | 'pushing' | 'creating-pr' | 'committing-push-and-pr' | 'discarding' = $state('idle');
	let isRequestingChanges = $state(false);
	let feedback = $state('');
	let showFeedback = $state(false);

	// Refs
	let fileInput: HTMLInputElement;
	let terminalBottom: HTMLDivElement;

	// Computed from store
	let repoPath = $derived(state.repoConfig[state.repoName] || '');

	let packages = $derived.by(() => {
		const pkgSet = new Set<string>();
		state.errors.forEach((e) => {
			const { packageName } = parseException(e.message);
			pkgSet.add(packageName);
		});
		return Array.from(pkgSet).sort();
	});

	let filteredErrors = $derived.by(() => {
		return state.errors.filter((e) => {
			const { name, packageName } = parseException(e.message);
			const matchesSearch =
				state.searchQuery === '' ||
				e.message.toLowerCase().includes(state.searchQuery.toLowerCase()) ||
				name.toLowerCase().includes(state.searchQuery.toLowerCase());
			const matchesPackage = state.packageFilter === 'all' || packageName === state.packageFilter;
			return matchesSearch && matchesPackage;
		});
	});

	let parsedDiff = $derived.by(() => parseDiff(state.diff));

	// Load config on mount (only if not already loaded)
	onMount(async () => {
		if (Object.keys(state.repoConfig).length === 0) {
			try {
				const config = await codeMedicApi.getConfig();
				codeMedicStore.setRepoConfig(config);
			} catch (e) {
				console.error('Failed to load CodeMedic config:', e);
				// Optionally set an error state in the store if needed
			}
		}
	});

	// Auto-scroll terminal
	$effect(() => {
		if (state.fixLogs.length > 0 && terminalBottom) {
			terminalBottom.scrollIntoView({ behavior: 'smooth' });
		}
	});

	// Helper: Parse exception from error message
	function parseException(message: string): { name: string; description: string; packageName: string } {
		const lines = message.split('\n');
		const logMessage = lines[0]?.trim() || '';
		const exceptionLine = lines[1]?.trim() || '';

		const colonIndex = exceptionLine.indexOf(':');
		let exceptionClassName = exceptionLine;
		if (colonIndex > 0) {
			exceptionClassName = exceptionLine.slice(0, colonIndex).trim();
		}

		const lastDot = exceptionClassName.lastIndexOf('.');
		const packageName = lastDot > 0 ? exceptionClassName.slice(0, lastDot) : 'unknown';

		return {
			name: exceptionClassName || logMessage,
			description: logMessage,
			packageName
		};
	}

	// Helper: Parse unified diff
	interface DiffFile {
		path: string;
		hunks: DiffHunk[];
	}
	interface DiffHunk {
		header: string;
		lines: DiffLine[];
	}
	interface DiffLine {
		type: 'add' | 'remove' | 'context';
		content: string;
		oldLineNum?: number;
		newLineNum?: number;
	}

	function parseDiff(diffText: string): DiffFile[] {
		const files: DiffFile[] = [];
		const lines = diffText.split('\n');
		let currentFile: DiffFile | null = null;
		let currentHunk: DiffHunk | null = null;
		let oldLine = 0;
		let newLine = 0;

		for (const line of lines) {
			if (line.startsWith('diff --git')) {
				if (currentFile) files.push(currentFile);
				const match = line.match(/diff --git a\/(.*) b\/(.*)/);
				currentFile = { path: match ? match[2] : 'unknown', hunks: [] };
				currentHunk = null;
			} else if (line.startsWith('@@')) {
				const match = line.match(/@@ -(\d+),?\d* \+(\d+),?\d* @@/);
				oldLine = match ? parseInt(match[1]) : 0;
				newLine = match ? parseInt(match[2]) : 0;
				currentHunk = { header: line, lines: [] };
				if (currentFile) currentFile.hunks.push(currentHunk);
			} else if (currentHunk) {
				if (line.startsWith('+') && !line.startsWith('+++')) {
					currentHunk.lines.push({ type: 'add', content: line.slice(1), newLineNum: newLine++ });
				} else if (line.startsWith('-') && !line.startsWith('---')) {
					currentHunk.lines.push({ type: 'remove', content: line.slice(1), oldLineNum: oldLine++ });
				} else if (line.startsWith(' ')) {
					currentHunk.lines.push({
						type: 'context',
						content: line.slice(1),
						oldLineNum: oldLine++,
						newLineNum: newLine++
					});
				}
			}
		}
		if (currentFile) files.push(currentFile);
		return files;
	}

	// File upload handler
	async function handleFileUpload(e: Event) {
		const target = e.target as HTMLInputElement;
		const file = target.files?.[0];
		if (!file) return;

		if (state.uploadedFilePath) {
			try {
				await codeMedicApi.cleanupTempFile(state.uploadedFilePath);
			} catch {}
		}

		isUploading = true;
		uploadProgress = 0;
		codeMedicStore.clearUploadedFile();

		try {
			const result = await codeMedicApi.uploadLogFile(file, (progress) => {
				uploadProgress = progress;
			});
			codeMedicStore.setUploadedFile(result.temp_path, result.original_filename, result.size);
			setTimeout(() => {
				isUploading = false;
				uploadProgress = 0;
			}, 500);
		} catch (e: any) {
			alert(`Failed to upload file: ${e.message}`);
			isUploading = false;
			uploadProgress = 0;
		}

		if (fileInput) fileInput.value = '';
	}

	// Analyze logs
	async function handleAnalyze() {
		if (!state.uploadedFilePath && !state.logContent) {
			alert('Please upload a log file or paste log content');
			return;
		}

		isAnalyzing = true;
		try {
			let errors: ErrorCluster[];
			if (state.uploadedFilePath) {
				errors = await codeMedicApi.analyzeLogFile(state.uploadedFilePath);
			} else {
				errors = await codeMedicApi.analyzeLogs(state.logContent);
			}
			codeMedicStore.setErrors(errors);
		} catch (e: any) {
			alert(`Failed to analyze logs: ${e.message}`);
		} finally {
			isAnalyzing = false;
		}
	}

	// Select error
	function handleSelectError(error: ErrorCluster) {
		codeMedicStore.selectError(error);
	}

	// Back to list
	function handleBackToList() {
		codeMedicStore.selectError(null);
	}

	// Start fix
	async function handleStartFix() {
		if (!state.selectedError) {
			alert('Please select an error to fix');
			return;
		}
		if (!repoPath) {
			alert('Please select a repository');
			return;
		}

		codeMedicStore.startSync();

		// Sync repo
		try {
			await codeMedicApi.syncRepo(repoPath);
			codeMedicStore.syncSuccess();
		} catch (e: any) {
			codeMedicStore.syncError(`Repo sync failed: ${e.message}`);
			return;
		}

		// Start streaming fix
		try {
			for await (const event of streamFix(repoPath, state.selectedError.trace)) {
				if (event.type === 'job_id') {
					codeMedicStore.setJobId(event.data);
				} else if (event.type === 'log') {
					codeMedicStore.addLog(event.data);
				} else if (event.type === 'complete') {
					if (event.data.success) {
						codeMedicStore.fixSuccess(event.data.branch_name);
						await loadDiff();
					} else {
						codeMedicStore.fixError(event.data.message || 'Fix failed');
					}
				}
			}
		} catch (e: any) {
			codeMedicStore.fixError(`Fix failed: ${e.message}`);
		}
	}

	// Cancel fix
	async function handleCancelFix() {
		if (!state.currentJobId) return;
		try {
			await codeMedicApi.cancelFix(state.currentJobId);
			codeMedicStore.cancelFix();
		} catch (e: any) {
			console.error('Cancel failed:', e);
		}
	}

	// Load diff
	async function loadDiff() {
		if (!repoPath) return;
		diffLoading = true;
		try {
			const result = await codeMedicApi.getDiff(repoPath);
			codeMedicStore.setDiff(result.diff);
		} catch (e: any) {
			codeMedicStore.setDiff('Error loading diff.');
		} finally {
			diffLoading = false;
		}
	}

	// Git actions
	async function handleCommitPushAndPr() {
		if (!repoPath || !state.selectedError) return;
		actionStatus = 'committing-push-and-pr';
		try {
			const result = await codeMedicApi.commitPushAndPr(
				repoPath,
				`Fix: ${state.selectedError.message.split('\n')[0]}`,
				state.currentBranchName || undefined
			);
			codeMedicStore.setPrUrl(result.pr_url);
		} catch (e: any) {
			alert(`Failed: ${e.message}`);
		} finally {
			actionStatus = 'idle';
		}
	}

	async function handleDiscard() {
		if (!repoPath) return;
		actionStatus = 'discarding';
		try {
			await codeMedicApi.discardChanges(repoPath);
			codeMedicStore.discardChanges();
		} catch (e: any) {
			alert(`Discard failed: ${e.message}`);
		} finally {
			actionStatus = 'idle';
		}
	}

	// Request changes
	async function handleRequestChanges() {
		if (!state.selectedError || !repoPath || !feedback.trim()) return;

		isRequestingChanges = true;
		codeMedicStore.addLog(`--- REQUESTING CHANGES: ${feedback} ---`);

		const followUpTrace = `${state.selectedError.trace}\n\n[USER FEEDBACK ON PREVIOUS FIX]\n${feedback}\n\nPlease apply the requested changes to the code.`;

		try {
			for await (const event of streamFix(repoPath, followUpTrace)) {
				if (event.type === 'log') {
					codeMedicStore.addLog(event.data);
				} else if (event.type === 'complete') {
					if (event.data.success) {
						codeMedicStore.addLog('--- CHANGES APPLIED ---');
						await loadDiff();
					} else {
						codeMedicStore.addLog(`--- FAILED: ${event.data.message} ---`);
					}
				}
			}
		} catch (e: any) {
			codeMedicStore.addLog(`--- ERROR: ${e.message} ---`);
		} finally {
			isRequestingChanges = false;
			feedback = '';
			showFeedback = false;
		}
	}

	// Switch input mode
	async function switchToMode(mode: 'paste' | 'upload') {
		if (mode === 'paste' && state.uploadedFilePath) {
			try {
				await codeMedicApi.cleanupTempFile(state.uploadedFilePath);
			} catch {}
			codeMedicStore.clearUploadedFile();
		}
		if (mode === 'upload') {
			codeMedicStore.setLogContent('');
		}
		codeMedicStore.setInputMode(mode);
	}
</script>

<svelte:head>
	<title>CodeMedic - CodeLens</title>
</svelte:head>

<div class="p-6 max-w-6xl mx-auto">
	{#if !state.selectedError}
		<!-- Configuration Section -->
		<div class="bg-white border border-slate-200 rounded-2xl shadow-sm mb-6">
			<div class="px-6 py-4 border-b border-slate-200 flex items-center gap-3">
				<svg class="w-6 h-6 text-primary" fill="none" stroke="currentColor" viewBox="0 0 24 24">
					<path stroke-linecap="round" stroke-linejoin="round" stroke-width="2" d="M9 12l2 2 4-4m5.618-4.016A11.955 11.955 0 0112 2.944a11.955 11.955 0 01-8.618 3.04A12.02 12.02 0 003 9c0 5.591 3.824 10.29 9 11.622 5.176-1.332 9-6.03 9-11.622 0-1.042-.133-2.052-.382-3.016z" />
				</svg>
				<div>
					<h1 class="text-lg font-bold text-slate-900">CodeMedic</h1>
					<p class="text-sm text-slate-500">Analyze error logs and generate automated fixes</p>
				</div>
			</div>

			<div class="p-6">
				<div class="grid grid-cols-1 lg:grid-cols-3 gap-6">
					<!-- Log Input -->
					<div class="lg:col-span-2 space-y-3">
						<div class="flex items-center justify-between">
							<label class="text-sm font-medium text-slate-700">Log Input</label>
							<div class="flex gap-1 bg-slate-100 p-1 rounded-lg">
								<button
									onclick={() => switchToMode('paste')}
									class="px-3 py-1 text-xs font-medium rounded-md transition-colors {state.inputMode === 'paste' ? 'bg-white text-slate-900 shadow-sm' : 'text-slate-600 hover:text-slate-900'}"
								>
									Paste
								</button>
								<button
									onclick={() => switchToMode('upload')}
									class="px-3 py-1 text-xs font-medium rounded-md transition-colors {state.inputMode === 'upload' ? 'bg-white text-slate-900 shadow-sm' : 'text-slate-600 hover:text-slate-900'}"
								>
									Upload
								</button>
							</div>
						</div>

						{#if state.inputMode === 'paste'}
							<textarea
								value={state.logContent}
								oninput={(e) => codeMedicStore.setLogContent(e.currentTarget.value)}
								placeholder="Paste your log content or stacktrace here..."
								class="w-full bg-slate-50 border border-slate-200 text-slate-900 placeholder-slate-400 rounded-xl px-4 py-3 text-sm font-mono focus:ring-2 focus:ring-primary focus:border-transparent outline-none h-32 resize-none"
							></textarea>
						{:else}
							<input
								bind:this={fileInput}
								type="file"
								accept=".log,.txt,*"
								onchange={handleFileUpload}
								class="hidden"
								disabled={isUploading}
							/>
							<button
								onclick={() => !isUploading && fileInput?.click()}
								class="w-full bg-slate-50 border-2 {state.uploadedFilePath && !isUploading ? 'border-primary' : 'border-slate-200'} border-dashed rounded-xl px-4 py-8 text-center transition-colors {isUploading ? 'opacity-50 cursor-not-allowed' : 'cursor-pointer hover:border-slate-300 hover:bg-slate-100'}"
							>
								<svg class="w-8 h-8 mx-auto mb-2 {state.uploadedFilePath && !isUploading ? 'text-primary' : 'text-slate-400'}" fill="none" stroke="currentColor" viewBox="0 0 24 24">
									<path stroke-linecap="round" stroke-linejoin="round" stroke-width="2" d="M7 16a4 4 0 01-.88-7.903A5 5 0 1115.9 6L16 6a5 5 0 011 9.9M15 13l-3-3m0 0l-3 3m3-3v12" />
								</svg>
								{#if isUploading}
									<p class="text-sm text-slate-500">Uploading... {uploadProgress}%</p>
								{:else if state.uploadedFilePath}
									<p class="text-sm font-medium text-primary">{state.uploadedFileName}</p>
									<p class="text-xs text-slate-500 mt-1">{(state.uploadedFileSize / 1024 / 1024).toFixed(2)} MB</p>
								{:else}
									<p class="text-sm text-slate-600">Click to upload log file</p>
									<p class="text-xs text-slate-400 mt-1">.log, .txt or any text file</p>
								{/if}
							</button>
							{#if isUploading}
								<div class="w-full bg-slate-200 rounded-full h-1.5 overflow-hidden">
									<div class="bg-primary h-1.5 transition-all duration-300" style="width: {uploadProgress}%"></div>
								</div>
							{/if}
						{/if}
					</div>

					<!-- Repository & Action -->
					<div class="space-y-4">
						<div class="space-y-2">
							<label class="text-sm font-medium text-slate-700">Repository</label>
							<select
								value={state.repoName}
								onchange={(e) => codeMedicStore.setRepoName(e.currentTarget.value)}
								class="w-full bg-slate-50 border border-slate-200 text-slate-900 rounded-xl px-4 py-2.5 text-sm focus:ring-2 focus:ring-primary focus:border-transparent outline-none"
							>
								<option value="">Select repository...</option>
								{#each state.repos as repo}
									<option value={repo}>{repo}</option>
								{/each}
							</select>
						</div>

						<button
							onclick={handleAnalyze}
							disabled={isAnalyzing || (!state.logContent && !state.uploadedFilePath)}
							class="w-full btn-primary py-3 flex items-center justify-center gap-2 disabled:opacity-50 disabled:cursor-not-allowed"
						>
							{#if isAnalyzing}
								<svg class="w-4 h-4 animate-spin" fill="none" viewBox="0 0 24 24">
									<circle class="opacity-25" cx="12" cy="12" r="10" stroke="currentColor" stroke-width="4"></circle>
									<path class="opacity-75" fill="currentColor" d="M4 12a8 8 0 018-8V0C5.373 0 0 5.373 0 12h4zm2 5.291A7.962 7.962 0 014 12H0c0 3.042 1.135 5.824 3 7.938l3-2.647z"></path>
								</svg>
								Analyzing...
							{:else}
								<svg class="w-4 h-4" fill="none" stroke="currentColor" viewBox="0 0 24 24">
									<path stroke-linecap="round" stroke-linejoin="round" stroke-width="2" d="M21 21l-6-6m2-5a7 7 0 11-14 0 7 7 0 0114 0z" />
								</svg>
								Analyze Logs
							{/if}
						</button>
					</div>
				</div>
			</div>
		</div>

		<!-- Error Cluster List -->
		{#if state.errors.length === 0}
			<div class="flex flex-col items-center justify-center py-16 text-slate-500 border border-dashed border-slate-300 rounded-2xl bg-white">
				<svg class="w-16 h-16 mb-4 opacity-30" fill="none" stroke="currentColor" viewBox="0 0 24 24">
					<path stroke-linecap="round" stroke-linejoin="round" stroke-width="1.5" d="M9 12h6m-6 4h6m2 5H7a2 2 0 01-2-2V5a2 2 0 012-2h5.586a1 1 0 01.707.293l5.414 5.414a1 1 0 01.293.707V19a2 2 0 01-2 2z" />
				</svg>
				<p class="text-lg font-medium text-slate-600">No errors analyzed yet</p>
				<p class="text-sm mt-1">Paste or upload logs and click "Analyze" to begin</p>
			</div>
		{:else}
			<div class="bg-white border border-slate-200 rounded-2xl overflow-hidden shadow-sm">
				<!-- Header -->
				<div class="bg-gradient-to-r from-primary to-secondary px-6 py-4 flex justify-between items-center">
					<h2 class="font-semibold text-white text-lg">Error Clusters</h2>
					<span class="text-sm bg-white/20 text-white px-3 py-1 rounded-full">{filteredErrors.length} of {state.errors.length}</span>
				</div>

				<!-- Filters -->
				<div class="p-4 border-b border-slate-200 bg-slate-50 flex flex-col sm:flex-row gap-3">
					<div class="relative flex-1">
						<svg class="absolute left-3 top-1/2 -translate-y-1/2 w-4 h-4 text-slate-400" fill="none" stroke="currentColor" viewBox="0 0 24 24">
							<path stroke-linecap="round" stroke-linejoin="round" stroke-width="2" d="M21 21l-6-6m2-5a7 7 0 11-14 0 7 7 0 0114 0z" />
						</svg>
						<input
							type="text"
							placeholder="Search errors..."
							value={state.searchQuery}
							oninput={(e) => codeMedicStore.setSearchQuery(e.currentTarget.value)}
							class="w-full bg-white border border-slate-200 text-slate-900 rounded-lg pl-10 pr-4 py-2 text-sm focus:ring-2 focus:ring-primary focus:border-transparent outline-none placeholder-slate-400"
						/>
					</div>
					<select
						value={state.packageFilter}
						onchange={(e) => codeMedicStore.setPackageFilter(e.currentTarget.value)}
						class="bg-white border border-slate-200 text-slate-900 rounded-lg px-4 py-2 text-sm focus:ring-2 focus:ring-primary focus:border-transparent outline-none min-w-[200px]"
					>
						<option value="all">All Packages</option>
						{#each packages as pkg}
							<option value={pkg}>{pkg}</option>
						{/each}
					</select>
				</div>

				<!-- Error List -->
				<div class="divide-y divide-slate-100 max-h-[500px] overflow-y-auto">
					{#if filteredErrors.length === 0}
						<div class="text-center py-12 text-slate-400 text-sm">No matching errors</div>
					{:else}
						{#each filteredErrors as error}
							{@const { name, description, packageName } = parseException(error.message)}
							<button
								onclick={() => handleSelectError(error)}
								class="w-full text-left px-6 py-4 hover:bg-slate-50 transition-colors flex items-start gap-4 group"
							>
								<div class="shrink-0 w-12 h-12 rounded-xl bg-red-50 border border-red-100 flex items-center justify-center text-sm font-bold text-red-600">
									{error.count}
								</div>
								<div class="flex-1 min-w-0">
									<p class="text-sm font-medium text-slate-900 group-hover:text-primary transition-colors truncate">
										{name}
									</p>
									{#if description && description !== name}
										<p class="text-xs text-slate-500 truncate mt-0.5">{description}</p>
									{/if}
									<span class="text-xs text-slate-500 bg-slate-100 px-2 py-0.5 rounded mt-2 inline-block">
										{packageName}
									</span>
								</div>
								<svg class="w-5 h-5 text-slate-300 group-hover:text-primary group-hover:translate-x-1 transition-all flex-shrink-0 mt-1" fill="none" stroke="currentColor" viewBox="0 0 24 24">
									<path stroke-linecap="round" stroke-linejoin="round" stroke-width="2" d="M9 5l7 7-7 7" />
								</svg>
							</button>
						{/each}
					{/if}
				</div>
			</div>
		{/if}
	{:else}
		<!-- Error Detail View -->
		<div class="space-y-6">
			<!-- Header -->
			<div class="flex items-center gap-4">
				<button onclick={handleBackToList} class="text-sm text-slate-600 hover:text-primary font-medium transition-colors flex items-center gap-1">
					<svg class="w-4 h-4" fill="none" stroke="currentColor" viewBox="0 0 24 24">
						<path stroke-linecap="round" stroke-linejoin="round" stroke-width="2" d="M15 19l-7-7 7-7" />
					</svg>
					Back to List
				</button>
				<h2 class="text-xl font-bold truncate flex-1 text-slate-900">{state.selectedError.message.split('\n')[0]}</h2>
			</div>

			<!-- Error Context -->
			<div class="bg-white border border-slate-200 rounded-2xl overflow-hidden shadow-sm">
				<div class="px-6 py-4 border-b border-slate-200 bg-gradient-to-r from-primary to-secondary flex justify-between items-center">
					<h3 class="font-semibold text-white">Error Context</h3>
					<span class="text-sm text-white/80 bg-white/20 px-3 py-1 rounded-full">{state.selectedError.count} occurrences</span>
				</div>
				<div class="p-6">
					<pre class="text-xs font-mono text-slate-900 whitespace-pre-wrap break-all bg-slate-50 p-4 rounded-xl max-h-48 overflow-y-auto border border-slate-200">{state.selectedError.trace}</pre>
				</div>
			</div>

			<!-- Fix Action -->
			{#if !state.showReview}
				<div class="bg-white border border-slate-200 rounded-2xl p-6 flex flex-col sm:flex-row items-center gap-6 shadow-sm">
					<div class="w-14 h-14 bg-gradient-to-br from-primary/10 to-secondary/10 rounded-xl flex items-center justify-center shrink-0">
						<svg class="w-7 h-7 text-primary" fill="none" stroke="currentColor" viewBox="0 0 24 24">
							<path stroke-linecap="round" stroke-linejoin="round" stroke-width="2" d="M10.325 4.317c.426-1.756 2.924-1.756 3.35 0a1.724 1.724 0 002.573 1.066c1.543-.94 3.31.826 2.37 2.37a1.724 1.724 0 001.065 2.572c1.756.426 1.756 2.924 0 3.35a1.724 1.724 0 00-1.066 2.573c.94 1.543-.826 3.31-2.37 2.37a1.724 1.724 0 00-2.572 1.065c-.426 1.756-2.924 1.756-3.35 0a1.724 1.724 0 00-2.573-1.066c-1.543.94-3.31-.826-2.37-2.37a1.724 1.724 0 00-1.065-2.572c-1.756-.426-1.756-2.924 0-3.35a1.724 1.724 0 001.066-2.573c-.94-1.543.826-3.31 2.37-2.37.996.608 2.296.07 2.572-1.065z" />
							<path stroke-linecap="round" stroke-linejoin="round" stroke-width="2" d="M15 12a3 3 0 11-6 0 3 3 0 016 0z" />
						</svg>
					</div>
					<div class="flex-1 text-center sm:text-left">
						<h3 class="font-semibold text-slate-900 mb-1">Automated Fix</h3>
						<p class="text-sm text-slate-500">Delegate this error to OpenCode AI for automatic resolution</p>
					</div>
					<div class="flex gap-3">
						{#if state.fixStatus === 'running'}
							<button
								onclick={handleCancelFix}
								class="bg-red-600 hover:bg-red-500 text-white font-medium py-2.5 px-6 rounded-xl transition-all"
							>
								Stop
							</button>
						{:else}
							<button
								onclick={handleStartFix}
								disabled={!repoPath}
								class="btn-primary py-2.5 px-6 disabled:opacity-50 disabled:cursor-not-allowed"
							>
								Auto-Fix with OpenCode
							</button>
						{/if}
					</div>
				</div>
				{#if !repoPath}
					<p class="text-sm text-amber-600 bg-amber-50 border border-amber-200 rounded-lg px-4 py-2">
						Please select a repository in the configuration section above before running auto-fix.
					</p>
				{/if}
			{/if}

			<!-- Terminal View -->
			{#if state.fixStatus !== 'idle' || state.repoSyncStatus !== 'idle' || state.fixLogs.length > 0}
				<div class="bg-white border border-slate-200 rounded-2xl overflow-hidden shadow-sm flex flex-col h-[400px]">
					<div class="px-6 py-4 border-b border-slate-200 bg-gradient-to-r from-primary to-secondary flex items-center justify-between">
						<h3 class="font-semibold text-white flex items-center gap-2">
							<svg class="w-5 h-5" fill="none" stroke="currentColor" viewBox="0 0 24 24">
								<path stroke-linecap="round" stroke-linejoin="round" stroke-width="2" d="M8 9l3 3-3 3m5 0h3M5 20h14a2 2 0 002-2V6a2 2 0 00-2-2H5a2 2 0 00-2 2v12a2 2 0 002 2z" />
							</svg>
							Agent Output
						</h3>
						<div class="flex items-center gap-3 text-sm">
							{#if state.repoSyncStatus === 'syncing'}
								<span class="text-white/90 bg-white/20 px-3 py-1 rounded-full flex items-center gap-2">
									<svg class="w-3 h-3 animate-spin" fill="none" viewBox="0 0 24 24"><circle class="opacity-25" cx="12" cy="12" r="10" stroke="currentColor" stroke-width="4"></circle><path class="opacity-75" fill="currentColor" d="M4 12a8 8 0 018-8V0C5.373 0 0 5.373 0 12h4zm2 5.291A7.962 7.962 0 014 12H0c0 3.042 1.135 5.824 3 7.938l3-2.647z"></path></svg>
									Syncing...
								</span>
							{/if}
							{#if state.fixStatus === 'running'}
								<span class="text-white/90 bg-white/20 px-3 py-1 rounded-full flex items-center gap-2">
									<svg class="w-3 h-3 animate-spin" fill="none" viewBox="0 0 24 24"><circle class="opacity-25" cx="12" cy="12" r="10" stroke="currentColor" stroke-width="4"></circle><path class="opacity-75" fill="currentColor" d="M4 12a8 8 0 018-8V0C5.373 0 0 5.373 0 12h4zm2 5.291A7.962 7.962 0 014 12H0c0 3.042 1.135 5.824 3 7.938l3-2.647z"></path></svg>
									Running...
								</span>
							{/if}
							{#if state.fixStatus === 'success'}
								<span class="text-emerald-300 bg-emerald-500/20 px-3 py-1 rounded-full flex items-center gap-2">
									<svg class="w-4 h-4" fill="none" stroke="currentColor" viewBox="0 0 24 24"><path stroke-linecap="round" stroke-linejoin="round" stroke-width="2" d="M5 13l4 4L19 7" /></svg>
									Complete
								</span>
							{/if}
							{#if state.fixStatus === 'error'}
								<span class="text-red-300 bg-red-500/20 px-3 py-1 rounded-full flex items-center gap-2">
									<svg class="w-4 h-4" fill="none" stroke="currentColor" viewBox="0 0 24 24"><path stroke-linecap="round" stroke-linejoin="round" stroke-width="2" d="M6 18L18 6M6 6l12 12" /></svg>
									Failed
								</span>
							{/if}
						</div>
					</div>
					<div class="p-4 font-mono text-xs text-slate-900 bg-slate-50 flex-1 overflow-y-auto">
						{#if state.repoSyncStatus === 'success'}
							<div class="mb-3 text-emerald-700 bg-emerald-50 px-3 py-2 rounded-lg border-l-4 border-emerald-500">Repository synced successfully</div>
						{/if}
						{#each state.fixLogs as log}
							<div class="whitespace-pre-wrap break-all border-l-2 border-transparent hover:border-primary/30 hover:bg-white pl-3 py-1 transition-colors">{log}</div>
						{/each}
						<div bind:this={terminalBottom}></div>
					</div>
					{#if state.fixMsg && state.fixStatus !== 'running'}
						<div class="px-6 py-3 border-t border-slate-200 text-sm font-medium {state.fixStatus === 'success' ? 'bg-emerald-50 text-emerald-700' : 'bg-red-50 text-red-700'}">
							{state.fixMsg}
						</div>
					{/if}
				</div>
			{/if}

			<!-- Diff View -->
			{#if state.showReview && !isRequestingChanges}
				<div class="space-y-4">
					{#if diffLoading}
						<div class="p-6 flex items-center justify-center gap-2 text-slate-400 bg-white border border-slate-200 rounded-2xl">
							<svg class="w-5 h-5 animate-spin" fill="none" viewBox="0 0 24 24"><circle class="opacity-25" cx="12" cy="12" r="10" stroke="currentColor" stroke-width="4"></circle><path class="opacity-75" fill="currentColor" d="M4 12a8 8 0 018-8V0C5.373 0 0 5.373 0 12h4zm2 5.291A7.962 7.962 0 014 12H0c0 3.042 1.135 5.824 3 7.938l3-2.647z"></path></svg>
							Loading diff...
						</div>
					{:else if !state.diff || !state.diff.trim()}
						<div class="p-6 text-slate-400 bg-slate-50 rounded-2xl border border-slate-200 text-center">No changes detected.</div>
					{:else}
						<!-- Diff Display -->
						<div class="bg-slate-900 border border-slate-700 rounded-2xl overflow-hidden">
							<div class="px-6 py-4 border-b border-slate-700 bg-slate-950 flex justify-between items-center">
								<h3 class="font-semibold text-slate-200">Changes Review</h3>
								<span class="text-sm text-slate-500">{parsedDiff.length} file(s) changed</span>
							</div>
							<div class="divide-y divide-slate-700 max-h-[400px] overflow-y-auto">
								{#each parsedDiff as file}
									<div>
										<div class="px-6 py-3 bg-slate-900/80 flex items-center gap-2 sticky top-0 border-b border-slate-700">
											<svg class="w-4 h-4 text-blue-400" fill="none" stroke="currentColor" viewBox="0 0 24 24">
												<path stroke-linecap="round" stroke-linejoin="round" stroke-width="2" d="M10 20l4-16m4 4l4 4-4 4M6 16l-4-4 4-4" />
											</svg>
											<span class="text-sm font-mono text-slate-300">{file.path}</span>
										</div>
										{#each file.hunks as hunk}
											<div class="font-mono text-xs">
												<div class="px-6 py-1.5 bg-blue-950/30 text-blue-300 border-y border-blue-900/30">{hunk.header}</div>
												<div>
													{#each hunk.lines as line}
														<div class="flex {line.type === 'add' ? 'bg-green-950/40' : line.type === 'remove' ? 'bg-red-950/40' : ''}">
															<div class="w-12 px-2 py-0.5 text-right text-slate-600 select-none border-r border-slate-700 shrink-0">
																{line.type !== 'add' ? line.oldLineNum : ''}
															</div>
															<div class="w-12 px-2 py-0.5 text-right text-slate-600 select-none border-r border-slate-700 shrink-0">
																{line.type !== 'remove' ? line.newLineNum : ''}
															</div>
															<div class="w-6 text-center py-0.5 shrink-0 {line.type === 'add' ? 'text-green-400' : line.type === 'remove' ? 'text-red-400' : ''}">
																{line.type === 'add' ? '+' : line.type === 'remove' ? '-' : ' '}
															</div>
															<pre class="flex-1 py-0.5 pr-6 whitespace-pre-wrap break-all {line.type === 'add' ? 'text-green-200' : line.type === 'remove' ? 'text-red-200' : 'text-slate-400'}">{line.content}</pre>
														</div>
													{/each}
												</div>
											</div>
										{/each}
									</div>
								{/each}
							</div>
						</div>

						<!-- Request Changes -->
						<div class="bg-slate-900 border border-slate-700 rounded-2xl overflow-hidden">
							<button
								onclick={() => showFeedback = !showFeedback}
								class="w-full px-6 py-4 bg-slate-950 flex justify-between items-center cursor-pointer hover:bg-slate-900/50 transition-colors"
							>
								<div class="flex items-center gap-3">
									<svg class="w-5 h-5 text-blue-400" fill="none" stroke="currentColor" viewBox="0 0 24 24">
										<path stroke-linecap="round" stroke-linejoin="round" stroke-width="2" d="M8 12h.01M12 12h.01M16 12h.01M21 12c0 4.418-4.03 8-9 8a9.863 9.863 0 01-4.255-.949L3 20l1.395-3.72C3.512 15.042 3 13.574 3 12c0-4.418 4.03-8 9-8s9 3.582 9 8z" />
									</svg>
									<span class="font-semibold text-slate-200">Request Changes</span>
								</div>
								<svg class="w-5 h-5 text-slate-500 transition-transform {showFeedback ? 'rotate-180' : ''}" fill="none" stroke="currentColor" viewBox="0 0 24 24">
									<path stroke-linecap="round" stroke-linejoin="round" stroke-width="2" d="M19 9l-7 7-7-7" />
								</svg>
							</button>
							{#if showFeedback}
								<div class="p-6 space-y-4 border-t border-slate-700">
									<textarea
										bind:value={feedback}
										placeholder="Describe what changes you'd like to make..."
										class="w-full bg-slate-950 border border-slate-700 rounded-xl px-4 py-3 text-sm h-24 resize-none focus:ring-2 focus:ring-blue-500 outline-none placeholder-slate-600 text-slate-200"
									></textarea>
									<button
										onclick={handleRequestChanges}
										disabled={!feedback.trim() || isRequestingChanges}
										class="w-full bg-blue-600 hover:bg-blue-500 text-white py-2.5 px-4 rounded-xl flex items-center justify-center gap-2 disabled:opacity-50 font-medium transition-colors"
									>
										{#if isRequestingChanges}
											<svg class="w-4 h-4 animate-spin" fill="none" viewBox="0 0 24 24"><circle class="opacity-25" cx="12" cy="12" r="10" stroke="currentColor" stroke-width="4"></circle><path class="opacity-75" fill="currentColor" d="M4 12a8 8 0 018-8V0C5.373 0 0 5.373 0 12h4zm2 5.291A7.962 7.962 0 014 12H0c0 3.042 1.135 5.824 3 7.938l3-2.647z"></path></svg>
											Applying Changes...
										{:else}
											<svg class="w-4 h-4" fill="none" stroke="currentColor" viewBox="0 0 24 24">
												<path stroke-linecap="round" stroke-linejoin="round" stroke-width="2" d="M12 19l9 2-9-18-9 18 9-2zm0 0v-8" />
											</svg>
											Send to OpenCode
										{/if}
									</button>
								</div>
							{/if}
						</div>

						<!-- Workflow Status -->
						{#if state.workflowStep !== 'review'}
							<div class="bg-slate-900 border border-slate-700 rounded-2xl p-6">
								<div class="flex items-center gap-4">
									<div class="flex items-center gap-2 text-sm {state.workflowStep === 'committed' || state.workflowStep === 'pushed' || state.workflowStep === 'pr-created' ? 'text-green-400' : 'text-slate-500'}">
										<svg class="w-5 h-5" fill="none" stroke="currentColor" viewBox="0 0 24 24"><path stroke-linecap="round" stroke-linejoin="round" stroke-width="2" d="M5 13l4 4L19 7" /></svg>
										<span>Committed</span>
									</div>
									<div class="h-px flex-1 bg-slate-700"></div>
									<div class="flex items-center gap-2 text-sm {state.workflowStep === 'pushed' || state.workflowStep === 'pr-created' ? 'text-green-400' : 'text-slate-500'}">
										<svg class="w-5 h-5" fill="none" stroke="currentColor" viewBox="0 0 24 24"><path stroke-linecap="round" stroke-linejoin="round" stroke-width="2" d="M7 16a4 4 0 01-.88-7.903A5 5 0 1115.9 6L16 6a5 5 0 011 9.9M15 13l-3-3m0 0l-3 3m3-3v12" /></svg>
										<span>Pushed</span>
									</div>
									<div class="h-px flex-1 bg-slate-700"></div>
									<div class="flex items-center gap-2 text-sm {state.workflowStep === 'pr-created' ? 'text-green-400' : 'text-slate-500'}">
										<svg class="w-5 h-5" fill="none" stroke="currentColor" viewBox="0 0 24 24"><path stroke-linecap="round" stroke-linejoin="round" stroke-width="2" d="M8 7h12m0 0l-4-4m4 4l-4 4m0 6H4m0 0l4 4m-4-4l4-4" /></svg>
										<span>PR Created</span>
									</div>
								</div>
							</div>
						{/if}

						<!-- Action Buttons -->
						<div class="flex gap-4">
							{#if state.workflowStep === 'review'}
								<button
									onclick={handleCommitPushAndPr}
									disabled={actionStatus !== 'idle' || isRequestingChanges}
									class="flex-[2] bg-purple-600 hover:bg-purple-500 text-white py-3 px-6 rounded-xl flex items-center justify-center gap-2 disabled:opacity-50 font-medium transition-colors"
								>
									{#if actionStatus === 'committing-push-and-pr'}
										<svg class="w-5 h-5 animate-spin" fill="none" viewBox="0 0 24 24"><circle class="opacity-25" cx="12" cy="12" r="10" stroke="currentColor" stroke-width="4"></circle><path class="opacity-75" fill="currentColor" d="M4 12a8 8 0 018-8V0C5.373 0 0 5.373 0 12h4zm2 5.291A7.962 7.962 0 014 12H0c0 3.042 1.135 5.824 3 7.938l3-2.647z"></path></svg>
									{:else}
										<svg class="w-5 h-5" fill="none" stroke="currentColor" viewBox="0 0 24 24"><path stroke-linecap="round" stroke-linejoin="round" stroke-width="2" d="M5 13l4 4L19 7" /></svg>
									{/if}
									Approve & Create PR
								</button>
								<button
									onclick={handleDiscard}
									disabled={actionStatus !== 'idle' || isRequestingChanges}
									class="flex-1 bg-slate-800 hover:bg-slate-700 border border-red-900/50 text-red-300 py-3 px-6 rounded-xl flex items-center justify-center gap-2 disabled:opacity-50 font-medium transition-colors"
								>
									{#if actionStatus === 'discarding'}
										<svg class="w-5 h-5 animate-spin" fill="none" viewBox="0 0 24 24"><circle class="opacity-25" cx="12" cy="12" r="10" stroke="currentColor" stroke-width="4"></circle><path class="opacity-75" fill="currentColor" d="M4 12a8 8 0 018-8V0C5.373 0 0 5.373 0 12h4zm2 5.291A7.962 7.962 0 014 12H0c0 3.042 1.135 5.824 3 7.938l3-2.647z"></path></svg>
									{:else}
										<svg class="w-5 h-5" fill="none" stroke="currentColor" viewBox="0 0 24 24"><path stroke-linecap="round" stroke-linejoin="round" stroke-width="2" d="M6 18L18 6M6 6l12 12" /></svg>
									{/if}
									Discard
								</button>
							{/if}

							{#if state.workflowStep === 'pr-created' && state.prUrl}
								<a
									href={state.prUrl}
									target="_blank"
									rel="noopener noreferrer"
									class="flex-1 bg-green-600 hover:bg-green-500 text-white py-3 px-6 rounded-xl flex items-center justify-center gap-2 font-medium transition-colors"
								>
									<svg class="w-5 h-5" fill="none" stroke="currentColor" viewBox="0 0 24 24"><path stroke-linecap="round" stroke-linejoin="round" stroke-width="2" d="M10 6H6a2 2 0 00-2 2v10a2 2 0 002 2h10a2 2 0 002-2v-4M14 4h6m0 0v6m0-6L10 14" /></svg>
									View Pull Request
								</a>
							{/if}
						</div>
					{/if}
				</div>
			{/if}
		</div>
	{/if}
</div>
