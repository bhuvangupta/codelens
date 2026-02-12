import { browser } from '$app/environment';
import { PUBLIC_CODEMEDIC_PORT } from '$env/static/public';

/**
 * CodeMedic API client for error log analysis and automated fixes
 * Connects to the codemedic-be Python backend
 */

// Get the CodeMedic backend URL
export function getCodeMedicApiBase(): string {
	const port = PUBLIC_CODEMEDIC_PORT || '8000';
	if (browser) {
		return `/api/codemedic`;
	}
	return `http://localhost:${port}`;
}

// Types
export interface ErrorCluster {
	message: string;
	count: number;
	trace: string;
}

export type CodeMedicConfig = Record<string, string>;

export interface UploadResult {
	temp_path: string;
	original_filename: string;
	size: number;
}

export interface DiffResult {
	diff: string;
}

export interface CommitPushPrResult {
	message: string;
	commit_message: string;
	push_message: string;
	pr_message: string;
	pr_url: string;
}

export interface PrResult {
	message: string;
	pr_url: string;
}

export interface QueueJob {
	id: string;
	type: string;
	status: string;
	created_at: number;
	details: string;
}

export interface QueueResult {
	repo: string;
	jobs: QueueJob[];
}

// API Client
export const codeMedicApi = {
	getConfig: async (): Promise<CodeMedicConfig> => {
		const res = await fetch(`${getCodeMedicApiBase()}/config`);
		if (!res.ok) throw new Error('Failed to load config');
		return res.json();
	},

	getModels: async (): Promise<string[]> => {
		const res = await fetch(`${getCodeMedicApiBase()}/models`);
		if (!res.ok) throw new Error('Failed to load models');
		return res.json();
	},

	uploadLogFile: async (
		file: File,
		onProgress?: (progress: number) => void
	): Promise<UploadResult> => {
		return new Promise((resolve, reject) => {
			const xhr = new XMLHttpRequest();
			const formData = new FormData();
			formData.append('file', file);

			xhr.upload.addEventListener('progress', (e) => {
				if (e.lengthComputable && onProgress) {
					const percent = Math.round((e.loaded * 100) / e.total);
					onProgress(percent);
				}
			});

			xhr.addEventListener('load', () => {
				if (xhr.status >= 200 && xhr.status < 300) {
					resolve(JSON.parse(xhr.responseText));
				} else {
					reject(new Error(`Upload failed: ${xhr.statusText}`));
				}
			});

			xhr.addEventListener('error', () => reject(new Error('Upload failed')));

			xhr.open('POST', `${getCodeMedicApiBase()}/logs/upload`);
			xhr.send(formData);
		});
	},

	analyzeLogFile: async (filePath: string): Promise<ErrorCluster[]> => {
		const res = await fetch(`${getCodeMedicApiBase()}/logs/analyze_file`, {
			method: 'POST',
			headers: { 'Content-Type': 'application/json' },
			body: JSON.stringify({ file_path: filePath })
		});
		if (!res.ok) {
			const err = await res.json().catch(() => ({}));
			throw new Error(err.detail || 'Failed to analyze log file');
		}
		return res.json();
	},

	analyzeLogs: async (logContent: string): Promise<ErrorCluster[]> => {
		const res = await fetch(`${getCodeMedicApiBase()}/logs/analyze`, {
			method: 'POST',
			headers: { 'Content-Type': 'application/json' },
			body: JSON.stringify({ log_content: logContent })
		});
		if (!res.ok) {
			const err = await res.json().catch(() => ({}));
			throw new Error(err.detail || 'Failed to analyze logs');
		}
		return res.json();
	},

	cleanupTempFile: async (filePath: string): Promise<void> => {
		await fetch(`${getCodeMedicApiBase()}/logs/cleanup`, {
			method: 'POST',
			headers: { 'Content-Type': 'application/json' },
			body: JSON.stringify({ file_path: filePath })
		});
	},

	syncRepo: async (repoPath: string): Promise<void> => {
		const res = await fetch(`${getCodeMedicApiBase()}/repo/sync`, {
			method: 'POST',
			headers: { 'Content-Type': 'application/json' },
			body: JSON.stringify({ repo_path: repoPath })
		});
		if (!res.ok) {
			const err = await res.json().catch(() => ({}));
			throw new Error(err.detail || 'Failed to sync repo');
		}
	},

	getDiff: async (repoPath: string): Promise<DiffResult> => {
		const res = await fetch(
			`${getCodeMedicApiBase()}/repo/diff?repo_path=${encodeURIComponent(repoPath)}`
		);
		if (!res.ok) {
			const err = await res.json().catch(() => ({}));
			throw new Error(err.detail || 'Failed to get diff');
		}
		return res.json();
	},

	discardChanges: async (repoPath: string): Promise<void> => {
		const res = await fetch(`${getCodeMedicApiBase()}/repo/discard`, {
			method: 'POST',
			headers: { 'Content-Type': 'application/json' },
			body: JSON.stringify({ repo_path: repoPath })
		});
		if (!res.ok) {
			const err = await res.json().catch(() => ({}));
			throw new Error(err.detail || 'Failed to discard changes');
		}
	},

	commitChanges: async (repoPath: string, message: string): Promise<void> => {
		const res = await fetch(`${getCodeMedicApiBase()}/repo/commit`, {
			method: 'POST',
			headers: { 'Content-Type': 'application/json' },
			body: JSON.stringify({ repo_path: repoPath, message })
		});
		if (!res.ok) {
			const err = await res.json().catch(() => ({}));
			throw new Error(err.detail || 'Failed to commit');
		}
	},

	pushBranch: async (repoPath: string): Promise<void> => {
		const res = await fetch(`${getCodeMedicApiBase()}/repo/push`, {
			method: 'POST',
			headers: { 'Content-Type': 'application/json' },
			body: JSON.stringify({ repo_path: repoPath })
		});
		if (!res.ok) {
			const err = await res.json().catch(() => ({}));
			throw new Error(err.detail || 'Failed to push');
		}
	},

	commitAndPush: async (repoPath: string, message: string): Promise<void> => {
		const res = await fetch(`${getCodeMedicApiBase()}/repo/commit-and-push`, {
			method: 'POST',
			headers: { 'Content-Type': 'application/json' },
			body: JSON.stringify({ repo_path: repoPath, message })
		});
		if (!res.ok) {
			const err = await res.json().catch(() => ({}));
			throw new Error(err.detail || 'Failed to commit and push');
		}
	},

	commitPushAndPr: async (
		repoPath: string,
		message: string,
		branchName?: string
	): Promise<CommitPushPrResult> => {
		const res = await fetch(`${getCodeMedicApiBase()}/repo/commit-push-and-pr`, {
			method: 'POST',
			headers: { 'Content-Type': 'application/json' },
			body: JSON.stringify({ repo_path: repoPath, message, branch_name: branchName })
		});
		if (!res.ok) {
			const err = await res.json().catch(() => ({}));
			throw new Error(err.detail || 'Failed to commit, push, and create PR');
		}
		return res.json();
	},

	createPullRequest: async (repoPath: string, title: string, body?: string): Promise<PrResult> => {
		const res = await fetch(`${getCodeMedicApiBase()}/repo/create-pr`, {
			method: 'POST',
			headers: { 'Content-Type': 'application/json' },
			body: JSON.stringify({ repo_path: repoPath, title, body })
		});
		if (!res.ok) {
			const err = await res.json().catch(() => ({}));
			throw new Error(err.detail || 'Failed to create PR');
		}
		return res.json();
	},

	getQueue: async (repoPath?: string): Promise<QueueResult> => {
		const url = repoPath
			? `${getCodeMedicApiBase()}/queue?repo_path=${encodeURIComponent(repoPath)}`
			: `${getCodeMedicApiBase()}/queue`;
		const res = await fetch(url);
		if (!res.ok) {
			const err = await res.json().catch(() => ({}));
			throw new Error(err.detail || 'Failed to get queue');
		}
		return res.json();
	},

	cancelFix: async (jobId: string): Promise<void> => {
		const res = await fetch(`${getCodeMedicApiBase()}/fix/cancel`, {
			method: 'POST',
			headers: { 'Content-Type': 'application/json' },
			body: JSON.stringify({ job_id: jobId })
		});
		if (!res.ok) {
			const err = await res.json().catch(() => ({}));
			throw new Error(err.detail || 'Failed to cancel fix');
		}
	}
};

// SSE streaming helper for fix endpoint
export async function* streamFix(
	repoPath: string,
	errorTrace: string
): AsyncGenerator<
	| { type: 'job_id'; data: string }
	| { type: 'log'; data: string }
	| { type: 'complete'; data: { success: boolean; message?: string; branch_name?: string } }
> {
	const response = await fetch(`${getCodeMedicApiBase()}/fix/start`, {
		method: 'POST',
		headers: { 'Content-Type': 'application/json' },
		body: JSON.stringify({ repo_path: repoPath, error_trace: errorTrace })
	});

	if (!response.ok) {
		const err = await response.json().catch(() => ({}));
		throw new Error(err.detail || 'Failed to start fix');
	}

	if (!response.body) {
		throw new Error('No response body');
	}

	const reader = response.body.getReader();
	const decoder = new TextDecoder();
	let buffer = '';

	while (true) {
		const { done, value } = await reader.read();
		if (done) break;

		buffer += decoder.decode(value, { stream: true });

		let eventIndex;
		while ((eventIndex = buffer.indexOf('\n\n')) >= 0) {
			const message = buffer.slice(0, eventIndex);
			buffer = buffer.slice(eventIndex + 2);

			if (message.startsWith('event: job_id')) {
				const dataLine = message.split('\n').find((l) => l.startsWith('data: '));
				if (dataLine) {
					yield { type: 'job_id', data: dataLine.slice(6).trim() };
				}
			} else if (message.startsWith('event: complete')) {
				const dataLine = message.split('\n').find((l) => l.startsWith('data: '));
				if (dataLine) {
					yield { type: 'complete', data: JSON.parse(dataLine.slice(6)) };
				}
			} else if (message.startsWith('data: ')) {
				yield { type: 'log', data: message.slice(6) };
			}
		}
	}
}
