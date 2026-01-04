# CodeLens Architecture

## System Overview

```
┌─────────────────────────────────────────────────────────────────┐
│                         CodeLens                                 │
├─────────────────────────────────────────────────────────────────┤
│                                                                  │
│  ┌──────────────┐    ┌──────────────┐    ┌──────────────┐       │
│  │   Frontend   │    │   Backend    │    │   Database   │       │
│  │  (SvelteKit) │───▶│ (Spring Boot)│───▶│   (MySQL)    │       │
│  │  Port 5174   │    │  Port 9292   │    │  Port 3306   │       │
│  └──────────────┘    └──────┬───────┘    └──────────────┘       │
│                             │                                    │
│              ┌──────────────┼──────────────┐                    │
│              ▼              ▼              ▼                    │
│        ┌──────────┐  ┌──────────┐  ┌──────────┐                │
│        │  GitHub  │  │  GitLab  │  │   LLMs   │                │
│        │   API    │  │   API    │  │          │                │
│        └──────────┘  └──────────┘  └──────────┘                │
│                                                                  │
└─────────────────────────────────────────────────────────────────┘
```

## Component Details

### Backend (codelens-be)

**Technology**: Java 17+ / Spring Boot 3.2+

**Key Packages**:
- `api/` - REST controllers
- `core/` - Review engine, diff parser
- `llm/` - LLM provider abstraction (LangChain4j)
- `git/` - GitHub/GitLab integration
- `analysis/` - Static analyzers by language
- `service/` - Business logic
- `model/entity/` - JPA entities

### Frontend (codelens-fe)

**Technology**: SvelteKit 5 / Tailwind CSS

**Key Routes**:
- `/dashboard` - Overview with stats
- `/reviews` - Review list
- `/reviews/[id]` - Review details with progress
- `/reviews/new` - Submit PR for review
- `/analytics` - Usage and cost analytics
- `/settings` - Configuration

### LLM Providers

| Provider | Model | Use Case |
|----------|-------|----------|
| Gemini | gemini-2.5-flash | Default (fast, cheap) |
| Gemini Pro | gemini-2.5-pro | Security analysis (thorough) |
| Claude | claude-sonnet-4 | High-quality reviews |
| OpenAI | gpt-4o | Alternative |
| GLM | glm-4.7 | ZhipuAI |
| Ollama | devstral-small-2 | Local/offline |

### Static Analyzers

| Language | Tools |
|----------|-------|
| Java | PMD, Checkstyle, SpotBugs |
| JavaScript/TypeScript | ESLint, npm audit |
| Python | Ruff, Bandit, pip-audit |
| Go | Staticcheck, Gosec |
| Rust | Clippy |

## Data Flow

### Review Submission

```
1. User submits PR URL via frontend
2. Backend validates and creates Review entity (PENDING)
3. Async task starts, status → IN_PROGRESS
4. For each changed file:
   a. Run static analysis (language-specific)
   b. Send diff + static results to LLM
   c. Parse LLM response into issues
5. Aggregate results, status → COMPLETED
6. Frontend polls for progress updates
```

### Transaction Isolation

Status updates use `@Transactional(propagation = REQUIRES_NEW)` to commit immediately, making progress visible during long-running reviews.

## Security

- Google OAuth for user authentication
- JWT tokens for API access
- API key authentication for CI/CD endpoints
- IP whitelisting support for CI
- Secrets via environment variables

## Deployment

### Docker Compose (Development)

```yaml
services:
  backend:
    build: ./codelens-be
    ports: ["9292:9292"]
    depends_on: [mysql]

  frontend:
    build: ./codelens-fe
    ports: ["5174:5174"]
    depends_on: [backend]

  mysql:
    image: mysql:8
    ports: ["3306:3306"]
```

### Production

- Backend: Docker container or JAR on any Java 17+ runtime
- Frontend: Static build served by any CDN/nginx
- Database: Managed MySQL (AWS RDS, Cloud SQL, etc.)
