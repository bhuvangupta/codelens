import { writable } from 'svelte/store';
import type { ErrorCluster, CodeMedicConfig } from '$lib/api/codemedic';

interface CodeMedicState {
	// Config
	repoConfig: CodeMedicConfig;
	repos: string[];
	repoName: string;

	// Input
	inputMode: 'paste' | 'upload';
	logContent: string;
	uploadedFilePath: string;
	uploadedFileName: string;
	uploadedFileSize: number;

	// Analysis
	errors: ErrorCluster[];
	selectedError: ErrorCluster | null;
	searchQuery: string;
	packageFilter: string;

	// Fix
	fixStatus: 'idle' | 'running' | 'success' | 'error';
	fixMsg: string;
	fixLogs: string[];
	repoSyncStatus: 'idle' | 'syncing' | 'success' | 'error';
	showReview: boolean;
	currentJobId: string | null;
	currentBranchName: string | null;

	// Diff
	diff: string;
	workflowStep: 'review' | 'committed' | 'pushed' | 'pr-created';
	prUrl: string | null;
}

const initialState: CodeMedicState = {
	repoConfig: {},
	repos: [],
	repoName: '',
	inputMode: 'paste',
	logContent: '',
	uploadedFilePath: '',
	uploadedFileName: '',
	uploadedFileSize: 0,
	errors: [],
	selectedError: null,
	searchQuery: '',
	packageFilter: 'all',
	fixStatus: 'idle',
	fixMsg: '',
	fixLogs: [],
	repoSyncStatus: 'idle',
	showReview: false,
	currentJobId: null,
	currentBranchName: null,
	diff: '',
	workflowStep: 'review',
	prUrl: null
};

function createCodeMedicStore() {
	const { subscribe, set, update } = writable<CodeMedicState>(initialState);

	return {
		subscribe,
		set,
		update,
		reset: () => set(initialState),

		// Convenience methods
		setRepoConfig: (config: CodeMedicConfig) =>
			update((s) => ({ ...s, repoConfig: config, repos: Object.keys(config) })),

		setRepoName: (name: string) =>
			update((s) => ({ ...s, repoName: name })),

		setInputMode: (mode: 'paste' | 'upload') =>
			update((s) => ({ ...s, inputMode: mode })),

		setLogContent: (content: string) =>
			update((s) => ({ ...s, logContent: content })),

		setUploadedFile: (path: string, name: string, size: number) =>
			update((s) => ({ ...s, uploadedFilePath: path, uploadedFileName: name, uploadedFileSize: size })),

		clearUploadedFile: () =>
			update((s) => ({ ...s, uploadedFilePath: '', uploadedFileName: '', uploadedFileSize: 0 })),

		setErrors: (errors: ErrorCluster[]) =>
			update((s) => ({ ...s, errors, selectedError: null, fixStatus: 'idle', showReview: false })),

		selectError: (error: ErrorCluster | null) =>
			update((s) => ({
				...s,
				selectedError: error,
				fixStatus: 'idle',
				fixMsg: '',
				fixLogs: [],
				repoSyncStatus: 'idle',
				showReview: false,
				workflowStep: 'review',
				prUrl: null,
				diff: ''
			})),

		setSearchQuery: (query: string) =>
			update((s) => ({ ...s, searchQuery: query })),

		setPackageFilter: (filter: string) =>
			update((s) => ({ ...s, packageFilter: filter })),

		// Fix state
		startSync: () =>
			update((s) => ({
				...s,
				repoSyncStatus: 'syncing',
				fixLogs: [],
				fixStatus: 'running',
				fixMsg: '',
				showReview: false,
				currentJobId: null,
				currentBranchName: null
			})),

		syncSuccess: () =>
			update((s) => ({ ...s, repoSyncStatus: 'success' })),

		syncError: (msg: string) =>
			update((s) => ({ ...s, repoSyncStatus: 'error', fixStatus: 'error', fixMsg: msg })),

		setJobId: (id: string) =>
			update((s) => ({ ...s, currentJobId: id })),

		addLog: (log: string) =>
			update((s) => ({ ...s, fixLogs: [...s.fixLogs, log] })),

		fixSuccess: (branchName?: string) =>
			update((s) => ({
				...s,
				fixStatus: 'success',
				showReview: true,
				currentBranchName: branchName || s.currentBranchName
			})),

		fixError: (msg: string) =>
			update((s) => ({ ...s, fixStatus: 'error', fixMsg: msg })),

		cancelFix: () =>
			update((s) => ({
				...s,
				fixStatus: 'idle',
				fixMsg: 'Cancelled by user.',
				fixLogs: [...s.fixLogs, '--- CANCELLED ---'],
				currentJobId: null
			})),

		setDiff: (diff: string) =>
			update((s) => ({ ...s, diff })),

		setWorkflowStep: (step: 'review' | 'committed' | 'pushed' | 'pr-created') =>
			update((s) => ({ ...s, workflowStep: step })),

		setPrUrl: (url: string) =>
			update((s) => ({ ...s, prUrl: url, workflowStep: 'pr-created', showReview: true })),

		discardChanges: () =>
			update((s) => ({ ...s, showReview: false, fixStatus: 'idle', diff: '' }))
	};
}

export const codeMedicStore = createCodeMedicStore();
