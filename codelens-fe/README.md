# CodeLens UI

SvelteKit frontend for CodeLens AI code review tool.

## Tech Stack

- **Framework**: SvelteKit 5
- **Styling**: Tailwind CSS
- **Auth**: Auth.js with Google OAuth
- **Language**: TypeScript

## Quick Start

### Prerequisites

- Node.js 18+
- Backend running on http://localhost:9292

### Install & Run

```bash
npm install
npm run dev
```

Open http://localhost:5175

### Build for Production

```bash
npm run build
npm run preview
```

## Configuration

Create `.env` file:

```bash
# Auth.js
AUTH_SECRET=your-auth-secret
AUTH_GOOGLE_ID=your-google-client-id
AUTH_GOOGLE_SECRET=your-google-client-secret

# Backend API
PUBLIC_API_URL=http://localhost:9292
```

## Project Structure

```
src/
├── routes/
│   ├── +layout.svelte       # Main layout with sidebar
│   ├── +page.svelte          # Redirect to dashboard
│   ├── auth/
│   │   └── login/            # Login page
│   ├── dashboard/            # Main dashboard
│   │   └── +page.svelte
│   ├── reviews/
│   │   ├── +page.svelte      # Review list
│   │   ├── new/              # Submit new PR
│   │   └── [id]/             # Review details
│   ├── analytics/            # Usage analytics
│   └── settings/
│       ├── +page.svelte      # General settings
│       ├── llm/              # LLM provider config
│       └── integrations/     # Git integrations
├── lib/
│   ├── api/
│   │   └── client.ts         # API client
│   └── components/           # Shared components
└── hooks.server.ts           # Auth.js integration
```

## Pages

### Dashboard (`/dashboard`)
- Welcome greeting based on time of day
- Quick stats (total reviews, pending, in-progress, issues)
- Recent reviews list
- AI model status
- Recent activity feed

### Reviews (`/reviews`)
- List of all reviews with status filter
- Status badges (Completed, In Progress, Pending, Failed)
- Issue severity counts
- Review ID (first 8 chars) for reference

### Review Details (`/reviews/[id]`)
- PR title, URL, and status
- Real-time progress bar (during review)
- Stats: files, lines added/removed, issues by severity
- AI-generated summary
- Issues grouped by file with inline navigation
- Review ID with click-to-copy

### Submit Review (`/reviews/new`)
- PR URL input
- LLM provider selection (optional override)
- Review options

### Analytics (`/analytics`)
- Reviews over time chart
- Issues by severity breakdown
- LLM cost tracking
- Provider usage stats

### Settings
- **General**: User profile, notification preferences
- **LLM**: Provider configuration, API keys, routing
- **Integrations**: GitHub/GitLab connection status

## API Client

```typescript
import { reviews, analytics, llm } from '$lib/api/client';

// Submit a review
const review = await reviews.submit('https://github.com/owner/repo/pull/123');

// Get review details
const details = await reviews.get(reviewId);

// Get review status (for polling)
const status = await reviews.getStatus(reviewId);

// Get dashboard stats
const stats = await analytics.getDashboard();

// Get LLM providers
const providers = await llm.getProviders();
```

## Styling

Uses Tailwind CSS with custom theme:

```css
/* Primary: Stone/Gray palette */
.bg-primary { @apply bg-stone-900; }
.text-primary { @apply text-stone-900; }

/* Accent colors for badges */
.badge-critical { @apply bg-red-100 text-red-800; }
.badge-high { @apply bg-orange-100 text-orange-800; }
.badge-medium { @apply bg-yellow-100 text-yellow-800; }
.badge-low { @apply bg-green-100 text-green-800; }
```

## Features

- [x] Google OAuth authentication
- [x] Real-time progress tracking with polling
- [x] Issue filtering by file
- [x] Status-based review filtering
- [x] Click-to-copy review ID
- [x] Responsive design
- [x] Dark mode ready (Tailwind)
- [x] Markdown rendering for summaries

## Development

```bash
# Development server with hot reload
npm run dev

# Type checking
npm run check

# Linting
npm run lint

# Format code
npm run format
```
