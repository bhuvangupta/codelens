import { API_BASE } from './config';

interface ApiOptions {
	method?: 'GET' | 'POST' | 'PUT' | 'DELETE';
	body?: unknown;
	headers?: Record<string, string>;
	skipRefresh?: boolean; // Skip token refresh attempt on 401
}

class ApiError extends Error {
	constructor(
		public status: number,
		message: string
	) {
		super(message);
		this.name = 'ApiError';
	}
}

// Track if we're currently refreshing to prevent concurrent refresh calls
let isRefreshing = false;
let refreshPromise: Promise<boolean> | null = null;

/**
 * Attempt to refresh the access token using the refresh token cookie.
 * Returns true if refresh succeeded, false otherwise.
 */
async function refreshToken(): Promise<boolean> {
	// If already refreshing, wait for that to complete
	if (isRefreshing && refreshPromise) {
		return refreshPromise;
	}

	isRefreshing = true;
	refreshPromise = (async () => {
		try {
			const response = await fetch('/auth/refresh', {
				method: 'POST',
				credentials: 'include'
			});

			if (response.ok) {
				const data = await response.json();
				return data.success === true;
			}
			return false;
		} catch {
			return false;
		} finally {
			isRefreshing = false;
			refreshPromise = null;
		}
	})();

	return refreshPromise;
}

async function request<T>(endpoint: string, options: ApiOptions = {}): Promise<T> {
	const { method = 'GET', body, headers = {}, skipRefresh = false } = options;

	const requestHeaders: Record<string, string> = {
		'Content-Type': 'application/json',
		...headers
	};

	const config: RequestInit = {
		method,
		headers: requestHeaders,
		credentials: 'include'
	};

	if (body) {
		config.body = JSON.stringify(body);
	}

	let response = await fetch(`${API_BASE}${endpoint}`, config);

	// If we get a 401 and haven't already tried refreshing, attempt token refresh
	if (response.status === 401 && !skipRefresh) {
		const refreshed = await refreshToken();
		if (refreshed) {
			// Retry the original request
			response = await fetch(`${API_BASE}${endpoint}`, config);
		}
	}

	if (!response.ok) {
		let errorMessage = response.statusText;
		try {
			const errorData = await response.json();
			errorMessage = errorData.message || errorData.error || errorData.details || response.statusText;
		} catch {
			try {
				errorMessage = await response.text() || response.statusText;
			} catch {
				// Use status text as fallback
			}
		}
		throw new ApiError(response.status, errorMessage);
	}

	if (response.status === 204) {
		return {} as T;
	}

	return response.json();
}

// Reviews API
export const reviews = {
	submit: (prUrl: string, options?: { includeOptimization?: boolean; ticketContent?: string; ticketId?: string }) =>
		request<Review>('/reviews', {
			method: 'POST',
			body: {
				prUrl,
				includeOptimization: options?.includeOptimization,
				ticketContent: options?.ticketContent,
				ticketId: options?.ticketId
			}
		}),

	submitCommit: (commitUrl: string, options?: { includeOptimization?: boolean; ticketContent?: string; ticketId?: string }) =>
		request<Review>('/reviews/commit', {
			method: 'POST',
			body: {
				commitUrl,
				includeOptimization: options?.includeOptimization,
				ticketContent: options?.ticketContent,
				ticketId: options?.ticketId
			}
		}),

	get: (id: string) => request<ReviewDetail>(`/reviews/${id}`),

	getStatus: (id: string) => request<ReviewStatus>(`/reviews/${id}/status`),

	getIssues: (id: string) => request<ReviewIssue[]>(`/reviews/${id}/issues`),

	getComments: (id: string) => request<ReviewComment[]>(`/reviews/${id}/comments`),

	getRecent: (limit = 50, repository?: string) => {
		const params = new URLSearchParams({ limit: String(limit) });
		if (repository) params.append('repository', repository);
		return request<Review[]>(`/reviews/recent?${params}`);
	},

	getRecentPaged: (page = 0, size = 20, repository?: string) => {
		const params = new URLSearchParams({ page: String(page), size: String(size) });
		if (repository) params.append('repository', repository);
		return request<PagedResponse<Review>>(`/reviews/recent/paged?${params}`);
	},

	getMy: (repository?: string) => {
		const params = repository ? `?repository=${encodeURIComponent(repository)}` : '';
		return request<Review[]>(`/reviews/my${params}`);
	},

	getMyPaged: (page = 0, size = 20, repository?: string) => {
		const params = new URLSearchParams({ page: String(page), size: String(size) });
		if (repository) params.append('repository', repository);
		return request<PagedResponse<Review>>(`/reviews/my/paged?${params}`);
	},

	getRepositories: (all = false) => request<string[]>(`/reviews/repositories?all=${all}`),

	optimize: (id: string) => request<OptimizationResult>(`/reviews/${id}/optimize`, { method: 'POST' }),

	getOptimizations: (id: string) => request<OptimizationStatus>(`/reviews/${id}/optimizations`),

	cancel: (id: string, reason?: string) =>
		request<CancelResponse>(`/reviews/${id}/cancel`, { method: 'POST', body: { reason } }),

	getDiff: (id: string) => request<DiffResponse>(`/reviews/${id}/diff`),

	submitIssueFeedback: (reviewId: string, issueId: string, feedback: IssueFeedback) =>
		request<{ message: string }>(`/reviews/${reviewId}/issues/${issueId}/feedback`, {
			method: 'POST',
			body: feedback
		})
};

// Analytics API
export const analytics = {
	getDashboard: () => request<DashboardStats>('/analytics/dashboard'),

	getTrends: (days = 30) => request<TrendData>(`/analytics/trends?days=${days}`),

	getLlmUsage: (days = 30) => request<LlmUsage>(`/analytics/llm-usage?days=${days}`),

	getIssues: (days = 30) => request<IssueAnalytics>(`/analytics/issues?days=${days}`),

	getSidebarStats: () => request<SidebarStats>('/analytics/sidebar-stats'),

	getActivity: (limit = 10) => request<ActivityItem[]>(`/analytics/activity?limit=${limit}`),

	getTopRepositories: (days = 30, limit = 10) =>
		request<TopRepository[]>(`/analytics/top-repositories?days=${days}&limit=${limit}`),

	getTopIssueTypes: (days = 30, limit = 10) =>
		request<TopIssueType[]>(`/analytics/top-issue-types?days=${days}&limit=${limit}`),

	// Monthly trend analytics
	getMonthlyTrends: (orgId: string, months = 6) =>
		request<MonthlyTrendResponse>(`/analytics/trends/organization/${orgId}?months=${months}`),

	getMyTrends: (months = 6) =>
		request<MonthlyTrendResponse>(`/analytics/trends/me?months=${months}`),

	getAllTrends: (months = 6) =>
		request<MonthlyTrendResponse>(`/analytics/trends/all?months=${months}`),

	getCategoryBreakdown: (orgId: string, months = 6) =>
		request<Record<string, number>>(`/analytics/trends/organization/${orgId}/categories?months=${months}`),

	getLlmQuota: () => request<LlmQuotaStatus>('/analytics/llm-quota'),

	// Developer Activity Analytics
	getDeveloperLeaderboard: (days = 30, limit = 10) =>
		request<DeveloperStats[]>(`/analytics/developers/leaderboard?days=${days}&limit=${limit}`),

	getDeveloperStats: (userId: string, days = 30) =>
		request<DeveloperStats>(`/analytics/developers/${userId}?days=${days}`),

	getMyDeveloperStats: (days = 30) =>
		request<DeveloperStats>(`/analytics/developers/me?days=${days}`),

	getDeveloperActivity: (userId: string, days = 30) =>
		request<DailyActivity[]>(`/analytics/developers/${userId}/activity?days=${days}`),

	getDeveloperSummary: (days = 30) =>
		request<DeveloperSummaryStats>(`/analytics/developers/summary?days=${days}`),

	getPrSizeDistribution: (days = 30) =>
		request<SizeDistribution[]>(`/analytics/developers/pr-sizes?days=${days}`),

	getDeveloperPrSizeDistribution: (userId: string, days = 30) =>
		request<SizeDistribution[]>(`/analytics/developers/${userId}/pr-sizes?days=${days}`),

	getCycleTimeTrend: (days = 30) =>
		request<CycleTimeTrend[]>(`/analytics/developers/cycle-time?days=${days}`),

	getFeedbackStats: (days = 30) =>
		request<FeedbackStats>(`/analytics/developers/feedback?days=${days}`),

	getMyWeeklySummary: (days = 7) =>
		request<WeeklySummary>(`/analytics/developers/me/weekly-summary?days=${days}`),

	getDeveloperWeeklySummary: (userId: string, days = 7) =>
		request<WeeklySummary>(`/analytics/developers/${userId}/weekly-summary?days=${days}`)
};

// Settings API
export const settings = {
	getUser: () => request<UserSettings>('/settings/user'),

	updateUser: (data: Partial<UserSettings>) =>
		request<UserSettings>('/settings/user', { method: 'PUT', body: data }),

	getOrg: () => request<OrgSettings>('/settings/organization'),

	getOrgById: (id: string) => request<OrgSettings>(`/settings/organization/${id}`),

	updateOrg: (id: string, data: Partial<OrgSettings>) =>
		request<OrgSettings>(`/settings/organization/${id}`, { method: 'PUT', body: data }),

	getLlmProviders: () => request<LlmProviderSettings>('/settings/llm-providers'),

	getRepositories: () => request<RepositorySettings[]>('/settings/repositories'),

	updateRepository: (id: string, data: { autoReviewEnabled?: boolean }) =>
		request<RepositorySettings>(`/settings/repositories/${id}`, { method: 'PUT', body: data })
};

// User API
export const user = {
	getMe: () => request<User>('/users/me'),

	updateMe: (data: Partial<User>) => request<User>('/users/me', { method: 'PUT', body: data }),

	getOrganizations: () => request<Organization[]>('/users/me/organizations'),

	createOrganization: (name: string) =>
		request<Organization>('/users/me/organizations', { method: 'POST', body: { name } }),

	// Sync user from session - creates user if not exists, updates last login
	sync: () => request<User>('/users/sync', { method: 'POST' })
};

// LLM API
export const llm = {
	getProviders: () => request<LlmProvider[]>('/llm/providers'),

	getEnabledProviders: () => request<string[]>('/llm/providers/enabled'),

	testProvider: (name: string) =>
		request<TestResult>(`/llm/providers/${name}/test`, { method: 'POST' }),

	getRouting: () => request<RoutingConfig>('/llm/routing'),

	chat: (message: string, provider?: string) =>
		request<ChatResponse>('/llm/chat', { method: 'POST', body: { message, provider } })
};

// Types
export interface PagedResponse<T> {
	content: T[];
	page: number;
	size: number;
	totalElements: number;
	totalPages: number;
	hasNext: boolean;
	hasPrevious: boolean;
}

export interface Review {
	id: string;
	prUrl: string | null;
	commitUrl: string | null;
	prNumber: number | null;
	prTitle: string;
	prAuthor: string;
	submittedBy?: string | null;
	repositoryName?: string;
	llmProvider?: string;
	status: 'PENDING' | 'IN_PROGRESS' | 'COMPLETED' | 'FAILED' | 'CANCELLED';
	errorMessage?: string | null;
	summary: string | null;
	filesReviewed: number;
	linesAdded: number;
	linesRemoved: number;
	totalIssues: number;
	criticalIssues: number;
	highIssues: number;
	mediumIssues: number;
	lowIssues: number;
	inputTokens?: number | null;
	outputTokens?: number | null;
	estimatedCost?: number | null;
	createdAt: string;
	completedAt: string | null;
	cancelledAt?: string | null;
	cancellationReason?: string | null;
	// Ticket scope validation
	ticketContent?: string | null;
	ticketId?: string | null;
	ticketScopeResult?: string | null;
	ticketScopeAligned?: boolean | null;
}

export interface CancelResponse {
	id: string;
	status: string;
	message: string;
	cancelledAt: string;
}

export interface ReviewDetail extends Review {
	issues: ReviewIssue[];
	comments: ReviewComment[];
}

export interface ReviewStatus {
	id: string;
	status: string;
	progress: number;
	totalFiles?: number;
	filesReviewed?: number;
	currentFile?: string;
	startedAt?: string;
	elapsedMs?: number;
}

export interface ReviewIssue {
	id: string;
	filePath: string;
	lineNumber: number;
	severity: 'CRITICAL' | 'HIGH' | 'MEDIUM' | 'LOW';
	category: string;
	description: string;
	suggestion: string | null;
	source: 'AI' | 'STATIC';
	cveId: string | null;
	cvssScore: number | null;
	aiExplanation?: string | null;
	// Feedback fields
	isHelpful?: boolean | null;
	isFalsePositive?: boolean | null;
	feedbackAt?: string | null;
}

export interface IssueFeedback {
	isHelpful?: boolean;
	isFalsePositive?: boolean;
	note?: string;
}

export interface OptimizationResult {
	status: 'started' | 'completed';
	message?: string;
	summary?: string;
	optimizations?: ReviewIssue[];
}

export interface OptimizationStatus {
	completed: boolean;
	inProgress: boolean;
	summary: string;
	optimizations: ReviewIssue[];
	// Progress info (when inProgress=true)
	totalFiles?: number;
	filesAnalyzed?: number;
	currentFile?: string;
	elapsedMs?: number;
	progress?: number;
}

export interface ReviewComment {
	id: string;
	filePath: string;
	lineNumber: number;
	body: string;
	severity: string;
	category: string;
	suggestion: string | null;
	posted: boolean;
}

export interface DashboardStats {
	totalReviews: number;
	completedReviews: number;
	completedThisWeek: number;
	reviewsThisWeek: number;
	pendingReviews: number;
	inProgressReviews: number;
	totalIssues: number;
	issuesBySeverity: Record<string, number>;
	llmCostThisMonth: number;
	tokensThisMonth: number;
	timeSavedHours?: number;
}

export interface TrendData {
	dailyReviews: Array<[string, number]>;
	dailyIssues: Array<[string, number]>;
}

export interface MonthlyTrendResponse {
	monthlyData: MonthData[];
	trend: 'improving' | 'stable' | 'degrading' | 'insufficient_data' | 'error';
	totalReviews: number;
	totalCriticalIssues: number;
	averageIssuesPerReview: number;
}

export interface MonthData {
	month: string;        // "2024-01"
	reviewCount: number;
	avgIssues: number;
	criticalIssues: number;
	highIssues: number;
}

export interface LlmUsage {
	costByProvider: Record<string, number>;
	totalInputTokens: number;
	totalOutputTokens: number;
}

export interface LlmQuotaStatus {
	usedToday: number;
	dailyLimit: number;
	remaining: number;
	percentUsed: number;
	exceeded: boolean;
	warning: boolean;
	enforcementEnabled: boolean;
}

// Developer Analytics Types
export interface DeveloperStats {
	rank?: number;
	userId: string;
	userName: string;
	userEmail?: string;
	avatarUrl?: string;
	reviewCount: number;
	linesReviewed: number;
	issuesFound: number;
	avgIssuesPerReview: number;
	criticalIssues: number;
	highIssues?: number;
	avgCycleTimeSeconds: number;
	repositoriesReviewed?: number;
}

export interface DailyActivity {
	date: string;
	reviewCount: number;
	linesReviewed: number;
}

export interface DeveloperSummaryStats {
	totalDevelopers: number;
	totalReviews: number;
	totalLinesReviewed: number;
	totalIssuesFound: number;
	avgCycleTimeSeconds: number;
}

export interface SizeDistribution {
	category: string;
	count: number;
}

export interface CycleTimeTrend {
	date: string;
	avgCycleTimeSeconds: number;
}

export interface FeedbackStats {
	totalWithFeedback: number;
	falsePositives: number;
	helpful: number;
	falsePositiveRate: number;
	helpfulRate: number;
}

export interface CategoryBreakdown {
	category: string;
	count: number;
}

export interface WeeklySummary {
	developerName: string;
	period: string;
	summary: string;
	highlights: string[];
	issueCategories: CategoryBreakdown[];
	primaryRepository: string | null;
}

export interface IssueAnalytics {
	bySource: Record<string, number>;
	byCategory: Record<string, number>;
	cveCount: number;
}

export interface SidebarStats {
	reviewsToday: number;
	dailyGoal: number;
	progressPercent: number;
	pendingCount: number;
}

export interface TopRepository {
	repositoryName: string;
	reviewCount: number;
	issueCount: number;
	criticalCount: number;
}

export interface TopIssueType {
	category: string;
	count: number;
}

export interface ActivityItem {
	userName: string;
	userAvatar: string | null;
	action: string;
	timestamp: string;
	timeAgo: string;
}

export interface LlmProviderStatus {
	name: string;
	available: boolean;
	description: string;
	isDefault: boolean;
}

export interface UserSettings {
	id: string;
	email: string;
	name: string;
	avatarUrl: string | null;
	role: 'ADMIN' | 'MEMBER' | 'VIEWER';
	defaultLlmProvider: string | null;
	organizationId: string | null;
	organizationName: string | null;
}

export interface OrgSettings {
	id: string;
	name: string;
	defaultLlmProvider: string | null;
	autoReviewEnabled: boolean;
	postCommentsEnabled: boolean;
	postInlineCommentsEnabled: boolean;
	securityScanEnabled: boolean;
	staticAnalysisEnabled: boolean;
	autoApproveMembers: boolean;
	hasGithubToken: boolean;
	hasGitlabToken: boolean;
	gitlabUrl: string | null;
}

export interface RepositorySettings {
	id: string;
	fullName: string;
	name: string;
	owner: string;
	provider: 'GITHUB' | 'GITLAB';
	description: string | null;
	language: string | null;
	isPrivate: boolean;
	autoReviewEnabled: boolean;
	createdAt: string;
}

export interface User {
	id: string;
	email: string;
	name: string;
	avatarUrl: string;
	role: 'ADMIN' | 'MEMBER' | 'VIEWER';
	defaultLlmProvider: string;
	createdAt: string;
	lastLoginAt: string | null;
	pendingMembership: PendingMembership | null;
}

export interface PendingMembership {
	requestId: string;
	organizationId: string;
	organizationName: string;
	status: 'PENDING' | 'APPROVED' | 'REJECTED';
	requestedAt: string;
}

export interface Organization {
	id: string;
	name: string;
	autoReviewEnabled: boolean;
}

export interface LlmProvider {
	name: string;
	available: boolean;
	description: string;
}

export interface LlmProviderSettings {
	providers: string[];
	defaultProvider: string;
}

export interface TestResult {
	success: boolean;
	message: string;
}

export interface RoutingConfig {
	taskRouting: Record<string, string>;
	defaultProvider: string;
}

export interface ChatResponse {
	response: string;
	provider: string;
	inputTokens: number;
	outputTokens: number;
}

// Team API (uses organization member endpoints)
export const team = {
	getMembers: (orgId: string) => request<OrgMember[]>(`/settings/organization/${orgId}/members`),

	addMember: (orgId: string, data: AddMemberRequest) =>
		request<OrgMember>(`/settings/organization/${orgId}/members`, { method: 'POST', body: data }),

	updateMember: (orgId: string, memberId: string, data: UpdateMemberRequest) =>
		request<OrgMember>(`/settings/organization/${orgId}/members/${memberId}`, { method: 'PUT', body: data }),

	removeMember: (orgId: string, memberId: string) =>
		request<{ message: string }>(`/settings/organization/${orgId}/members/${memberId}`, { method: 'DELETE' })
};

// Team Types
export interface OrgMember {
	id: string;
	email: string;
	name: string;
	avatarUrl: string | null;
	role: 'ADMIN' | 'MEMBER' | 'VIEWER';
	githubUsername: string | null;
	gitlabUsername: string | null;
	lastLoginAt: string | null;
}

export interface AddMemberRequest {
	email: string;
	name?: string;
	role?: 'ADMIN' | 'MEMBER' | 'VIEWER';
	githubUsername?: string;
	gitlabUsername?: string;
}

export interface UpdateMemberRequest {
	role?: 'ADMIN' | 'MEMBER' | 'VIEWER';
	githubUsername?: string;
	gitlabUsername?: string;
}

// Membership Requests API
export const membershipRequests = {
	getPending: (orgId: string) =>
		request<MembershipRequest[]>(`/settings/organization/${orgId}/membership-requests`),

	getCount: (orgId: string) =>
		request<{ count: number }>(`/settings/organization/${orgId}/membership-requests/count`),

	approve: (orgId: string, requestId: string) =>
		request<MembershipRequest>(`/settings/organization/${orgId}/membership-requests/${requestId}/approve`, {
			method: 'POST'
		}),

	reject: (orgId: string, requestId: string) =>
		request<MembershipRequest>(`/settings/organization/${orgId}/membership-requests/${requestId}/reject`, {
			method: 'POST'
		})
};

// Membership Request Types
export interface MembershipRequest {
	id: string;
	userId: string;
	userEmail: string;
	userName: string;
	userAvatarUrl: string | null;
	status: 'PENDING' | 'APPROVED' | 'REJECTED';
	requestedAt: string;
	reviewedAt: string | null;
	reviewedByEmail: string | null;
}

// Rules API
export const rules = {
	getAll: () => request<ReviewRule[]>('/rules'),

	create: (rule: Omit<ReviewRule, 'id' | 'isCustom' | 'createdAt'>) =>
		request<ReviewRule>('/rules', { method: 'POST', body: rule }),

	update: (ruleId: string, data: Partial<ReviewRule>) =>
		request<ReviewRule>(`/rules/${ruleId}`, { method: 'PUT', body: data }),

	delete: (ruleId: string) =>
		request<void>(`/rules/${ruleId}`, { method: 'DELETE' })
};

// Audit API
export const audit = {
	getLogs: (params: { page?: number; limit?: number; action?: string; user?: string; days?: number }) => {
		const queryParams = new URLSearchParams();
		if (params.page !== undefined) queryParams.append('page', String(params.page));
		if (params.limit !== undefined) queryParams.append('limit', String(params.limit));
		if (params.action) queryParams.append('action', params.action);
		if (params.user) queryParams.append('user', params.user);
		if (params.days !== undefined) queryParams.append('days', String(params.days));
		return request<{ logs: AuditLogEntry[]; hasMore: boolean }>(`/audit/logs?${queryParams.toString()}`);
	}
};

// Notifications API
export const notifications = {
	getAll: (page = 0, size = 20) => request<NotificationItem[]>(`/notifications?page=${page}&size=${size}`),

	getUnreadCount: () => request<{ count: number }>('/notifications/count'),

	markAsRead: (id: string) => request<void>(`/notifications/${id}/read`, { method: 'POST' }),

	markAllAsRead: () => request<{ count: number }>('/notifications/read-all', { method: 'POST' }),

	getPreferences: () => request<NotificationPreferences>('/notifications/preferences'),

	updatePreferences: (prefs: Partial<NotificationPreferences>) =>
		request<NotificationPreferences>('/notifications/preferences', { method: 'PUT', body: prefs })
};

// Webhooks API
export const webhooks = {
	getAll: () => request<Webhook[]>('/webhooks'),

	create: (webhook: CreateWebhookRequest) =>
		request<Webhook>('/webhooks', { method: 'POST', body: webhook }),

	update: (id: string, webhook: Partial<CreateWebhookRequest>) =>
		request<Webhook>(`/webhooks/${id}`, { method: 'PUT', body: webhook }),

	delete: (id: string) => request<void>(`/webhooks/${id}`, { method: 'DELETE' }),

	getEventTypes: () => request<WebhookEventType[]>('/webhooks/events')
};


// Rules Types
export interface ReviewRule {
	id: string;
	name: string;
	description: string;
	severity: 'CRITICAL' | 'HIGH' | 'MEDIUM' | 'LOW';
	category: string;
	pattern: string;
	suggestion: string;
	enabled: boolean;
	languages: string[];
	isCustom: boolean;
	createdAt: string;
}

// Audit Types
export interface AuditLogEntry {
	id: string;
	action: string;
	description: string;
	userId: string;
	userName: string;
	userAvatar: string | null;
	ipAddress: string | null;
	timestamp: string;
	metadata: Record<string, unknown>;
}

// Notification Types
export interface NotificationItem {
	id: string;
	type: 'REVIEW_COMPLETED' | 'REVIEW_FAILED' | 'CRITICAL_ISSUES_FOUND' | 'MEMBERSHIP_APPROVED' | 'MEMBERSHIP_REJECTED';
	title: string;
	message: string;
	referenceType: string | null;
	referenceId: string | null;
	isRead: boolean;
	createdAt: string;
}

export interface NotificationPreferences {
	emailEnabled: boolean;
	inAppEnabled: boolean;
	reviewCompleted: boolean;
	reviewFailed: boolean;
	criticalIssues: boolean;
}

// Webhook Types
export interface Webhook {
	id: string;
	name: string;
	url: string;
	hasSecret: boolean;
	events: string[];
	enabled: boolean;
	failureCount: number;
	lastDeliveryAt: string | null;
	createdAt: string;
}

export interface CreateWebhookRequest {
	name: string;
	url: string;
	secret?: string;
	events: string[];
	enabled?: boolean;
}

export interface WebhookEventType {
	id: string;
	name: string;
	description: string;
}

// Diff Types
export interface DiffResponse {
	rawDiff: string | null;
	files: FileDiff[];
}

export interface FileDiff {
	id: string;
	filePath: string;
	oldPath: string | null;
	status: 'ADDED' | 'MODIFIED' | 'DELETED' | 'RENAMED';
	additions: number;
	deletions: number;
	patch: string | null;
}

export { ApiError };
