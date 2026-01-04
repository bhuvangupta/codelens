<script lang="ts">
	import type { FileDiff } from '$lib/api/client';

	export let files: FileDiff[] = [];
	export let loading = false;

	let expandedFiles: Set<string> = new Set();
	let viewMode: 'unified' | 'split' = 'unified';

	function toggleFile(fileId: string) {
		if (expandedFiles.has(fileId)) {
			expandedFiles.delete(fileId);
		} else {
			expandedFiles.add(fileId);
		}
		expandedFiles = new Set(expandedFiles); // Trigger reactivity
	}

	function expandAll() {
		expandedFiles = new Set(files.map(f => f.id));
	}

	function collapseAll() {
		expandedFiles = new Set();
	}

	function getStatusBadge(status: string): { text: string; class: string } {
		switch (status) {
			case 'ADDED': return { text: 'Added', class: 'bg-green-100 text-green-700' };
			case 'MODIFIED': return { text: 'Modified', class: 'bg-blue-100 text-blue-700' };
			case 'DELETED': return { text: 'Deleted', class: 'bg-red-100 text-red-700' };
			case 'RENAMED': return { text: 'Renamed', class: 'bg-amber-100 text-amber-700' };
			default: return { text: status, class: 'bg-gray-100 text-gray-700' };
		}
	}

	function getFileIcon(filePath: string): { icon: string; color: string } {
		const ext = filePath.split('.').pop()?.toLowerCase() || '';
		if (ext === 'tsx' || ext === 'jsx') return { icon: 'fab fa-react', color: 'text-blue-500' };
		if (ext === 'ts') return { icon: 'fab fa-js', color: 'text-blue-600' };
		if (ext === 'js') return { icon: 'fab fa-js', color: 'text-amber-500' };
		if (ext === 'json') return { icon: 'fas fa-cube', color: 'text-slate-500' };
		if (ext === 'java') return { icon: 'fab fa-java', color: 'text-red-500' };
		if (ext === 'py') return { icon: 'fab fa-python', color: 'text-blue-600' };
		if (ext === 'svelte') return { icon: 'fas fa-fire', color: 'text-orange-500' };
		if (ext === 'css' || ext === 'scss' || ext === 'sass') return { icon: 'fab fa-css3-alt', color: 'text-blue-500' };
		if (ext === 'html') return { icon: 'fab fa-html5', color: 'text-orange-600' };
		if (ext === 'md') return { icon: 'fab fa-markdown', color: 'text-slate-600' };
		if (ext === 'sql') return { icon: 'fas fa-database', color: 'text-indigo-500' };
		if (ext === 'yaml' || ext === 'yml') return { icon: 'fas fa-cog', color: 'text-red-400' };
		if (ext === 'go') return { icon: 'fas fa-code', color: 'text-cyan-500' };
		if (ext === 'rs') return { icon: 'fas fa-cog', color: 'text-orange-600' };
		return { icon: 'fas fa-file-code', color: 'text-slate-500' };
	}

	function parsePatch(patch: string | null): { type: 'add' | 'del' | 'context' | 'hunk'; content: string; oldNum?: number; newNum?: number }[] {
		if (!patch) return [];

		const lines: { type: 'add' | 'del' | 'context' | 'hunk'; content: string; oldNum?: number; newNum?: number }[] = [];
		const patchLines = patch.split('\n');

		let oldLineNum = 0;
		let newLineNum = 0;

		for (const line of patchLines) {
			if (line.startsWith('@@')) {
				// Parse hunk header: @@ -oldStart,oldCount +newStart,newCount @@
				const match = line.match(/@@ -(\d+)(?:,\d+)? \+(\d+)(?:,\d+)? @@/);
				if (match) {
					oldLineNum = parseInt(match[1], 10);
					newLineNum = parseInt(match[2], 10);
				}
				lines.push({ type: 'hunk', content: line });
			} else if (line.startsWith('+')) {
				lines.push({ type: 'add', content: line.substring(1), newNum: newLineNum++ });
			} else if (line.startsWith('-')) {
				lines.push({ type: 'del', content: line.substring(1), oldNum: oldLineNum++ });
			} else if (line.startsWith(' ') || line === '') {
				lines.push({ type: 'context', content: line.substring(1) || '', oldNum: oldLineNum++, newNum: newLineNum++ });
			}
		}

		return lines;
	}

	$: totalAdditions = files.reduce((sum, f) => sum + f.additions, 0);
	$: totalDeletions = files.reduce((sum, f) => sum + f.deletions, 0);
</script>

<div class="diff-viewer">
	{#if loading}
		<div class="flex items-center justify-center py-12">
			<div class="animate-spin rounded-full h-8 w-8 border-b-2 border-amber-700"></div>
		</div>
	{:else if files.length === 0}
		<div class="text-center py-12 text-slate-500">
			<i class="fas fa-file-alt text-4xl text-slate-300 mb-3"></i>
			<p>No diff available for this review</p>
		</div>
	{:else}
		<!-- Header -->
		<div class="flex items-center justify-between mb-4">
			<div class="flex items-center gap-4">
				<span class="text-sm text-slate-600">
					<span class="font-medium">{files.length}</span> files changed
				</span>
				<span class="text-sm text-green-600">
					<span class="font-medium">+{totalAdditions}</span> additions
				</span>
				<span class="text-sm text-red-600">
					<span class="font-medium">-{totalDeletions}</span> deletions
				</span>
			</div>
			<div class="flex items-center gap-2">
				<button
					on:click={expandAll}
					class="px-3 py-1.5 text-xs text-slate-600 hover:text-slate-900 border border-slate-200 hover:border-slate-300 rounded-lg transition-colors"
				>
					Expand all
				</button>
				<button
					on:click={collapseAll}
					class="px-3 py-1.5 text-xs text-slate-600 hover:text-slate-900 border border-slate-200 hover:border-slate-300 rounded-lg transition-colors"
				>
					Collapse all
				</button>
			</div>
		</div>

		<!-- Files -->
		<div class="space-y-3">
			{#each files as file}
				<div class="border border-slate-200 rounded-xl overflow-hidden">
					<!-- File header -->
					<button
						on:click={() => toggleFile(file.id)}
						class="w-full flex items-center justify-between px-4 py-3 bg-slate-50 hover:bg-slate-100 transition-colors"
					>
						<div class="flex items-center gap-3">
							<i class="fas {expandedFiles.has(file.id) ? 'fa-chevron-down' : 'fa-chevron-right'} text-slate-400 text-xs w-3"></i>
							<i class="{getFileIcon(file.filePath).icon} {getFileIcon(file.filePath).color}"></i>
							<span class="font-mono text-sm text-slate-700">
								{#if file.oldPath && file.oldPath !== file.filePath}
									<span class="text-slate-400">{file.oldPath}</span>
									<i class="fas fa-arrow-right text-xs mx-2 text-slate-400"></i>
								{/if}
								{file.filePath}
							</span>
							<span class="px-2 py-0.5 {getStatusBadge(file.status).class} rounded text-xs font-medium">
								{getStatusBadge(file.status).text}
							</span>
						</div>
						<div class="flex items-center gap-3 text-xs">
							{#if file.additions > 0}
								<span class="text-green-600">+{file.additions}</span>
							{/if}
							{#if file.deletions > 0}
								<span class="text-red-600">-{file.deletions}</span>
							{/if}
							<div class="w-20 h-1.5 bg-slate-200 rounded-full overflow-hidden flex">
								{#if file.additions + file.deletions > 0}
									<div
										class="h-full bg-green-500"
										style="width: {(file.additions / (file.additions + file.deletions)) * 100}%"
									></div>
									<div
										class="h-full bg-red-500"
										style="width: {(file.deletions / (file.additions + file.deletions)) * 100}%"
									></div>
								{/if}
							</div>
						</div>
					</button>

					<!-- Diff content -->
					{#if expandedFiles.has(file.id) && file.patch}
						<div class="overflow-x-auto bg-white border-t border-slate-200">
							<table class="w-full text-xs font-mono">
								<tbody>
									{#each parsePatch(file.patch) as line}
										{#if line.type === 'hunk'}
											<tr class="bg-blue-50 text-blue-700">
												<td colspan="3" class="px-4 py-1.5">{line.content}</td>
											</tr>
										{:else if line.type === 'add'}
											<tr class="bg-green-50 hover:bg-green-100">
												<td class="w-12 text-right pr-2 py-0.5 text-slate-400 select-none border-r border-slate-100"></td>
												<td class="w-12 text-right pr-2 py-0.5 text-green-600 select-none border-r border-slate-100">{line.newNum}</td>
												<td class="pl-4 py-0.5 text-green-800 whitespace-pre">+{line.content}</td>
											</tr>
										{:else if line.type === 'del'}
											<tr class="bg-red-50 hover:bg-red-100">
												<td class="w-12 text-right pr-2 py-0.5 text-red-600 select-none border-r border-slate-100">{line.oldNum}</td>
												<td class="w-12 text-right pr-2 py-0.5 text-slate-400 select-none border-r border-slate-100"></td>
												<td class="pl-4 py-0.5 text-red-800 whitespace-pre">-{line.content}</td>
											</tr>
										{:else}
											<tr class="hover:bg-slate-50">
												<td class="w-12 text-right pr-2 py-0.5 text-slate-400 select-none border-r border-slate-100">{line.oldNum}</td>
												<td class="w-12 text-right pr-2 py-0.5 text-slate-400 select-none border-r border-slate-100">{line.newNum}</td>
												<td class="pl-4 py-0.5 text-slate-700 whitespace-pre"> {line.content}</td>
											</tr>
										{/if}
									{/each}
								</tbody>
							</table>
						</div>
					{:else if expandedFiles.has(file.id) && !file.patch}
						<div class="p-6 text-center text-slate-500 border-t border-slate-200">
							<p>No patch content available</p>
						</div>
					{/if}
				</div>
			{/each}
		</div>
	{/if}
</div>

<style>
	.diff-viewer table {
		border-collapse: collapse;
	}
	.diff-viewer td {
		vertical-align: top;
	}
</style>
