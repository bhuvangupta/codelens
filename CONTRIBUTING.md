# Contributing to CodeLens

Thank you for your interest in contributing to CodeLens! This document provides guidelines and instructions for contributing.

## Getting Started

### Prerequisites

- Java 21+
- Node.js 18+
- MySQL 8.0+
- Maven 3.8+

### Local Development Setup

1. **Clone the repository**
   ```bash
   git clone https://github.com/bhuvangupta/codelens.git
   cd codelens
   ```

2. **Set up environment variables**

   Copy the example files and configure:
   ```bash
   cp codelens-be/.env.example codelens-be/.env
   cp codelens-fe/.env.example codelens-fe/.env
   ```

   Required variables in `codelens-be/.env`:
   ```bash
   # Database
   DB_PASSWORD=your-secure-password

   # Security (generate with: openssl rand -base64 32)
   JWT_SECRET=your-jwt-secret-min-32-chars
   ENCRYPTION_KEY=your-encryption-key-min-32-chars

   # OAuth (for authentication)
   GOOGLE_CLIENT_ID=your-google-client-id
   GOOGLE_CLIENT_SECRET=your-google-client-secret

   # LLM Provider (at least one required)
   GOOGLE_API_KEY=your-gemini-api-key
   # OR
   ANTHROPIC_API_KEY=your-claude-api-key
   # OR
   OPENAI_API_KEY=your-openai-api-key
   ```

3. **Start MySQL**
   ```bash
   # Using Docker
   docker run -d --name codelens-mysql \
     -e MYSQL_ROOT_PASSWORD=rootpassword \
     -e MYSQL_DATABASE=codelens \
     -p 3306:3306 mysql:8.0
   ```

4. **Start the backend**
   ```bash
   cd codelens-be
   mvn spring-boot:run
   ```

5. **Start the frontend**
   ```bash
   cd codelens-fe
   npm install
   npm run dev
   ```

6. **Access the application**
   - Frontend: http://localhost:5174
   - Backend API: http://localhost:9292

## Development Workflow

### Branching Strategy

- `main` - Production-ready code
- `develop` - Integration branch for features
- `feature/*` - New features
- `fix/*` - Bug fixes
- `docs/*` - Documentation updates

### Making Changes

1. Create a new branch from `main`:
   ```bash
   git checkout -b feature/your-feature-name
   ```

2. Make your changes following our coding standards

3. Write/update tests as needed

4. Commit your changes:
   ```bash
   git commit -m "Add: brief description of changes"
   ```

### Commit Message Format

Use clear, descriptive commit messages:
- `Add:` for new features
- `Fix:` for bug fixes
- `Update:` for updates to existing features
- `Remove:` for removed features
- `Refactor:` for code refactoring
- `Docs:` for documentation changes
- `Test:` for test additions/updates

### Pull Requests

1. Push your branch and create a PR against `main`
2. Fill out the PR template
3. Ensure all checks pass
4. Request review from maintainers

## Code Standards

### Java (Backend)
- Follow standard Java conventions
- Use meaningful variable/method names
- Add Javadoc for public APIs
- Keep methods focused and small

### TypeScript/Svelte (Frontend)
- Use TypeScript for type safety
- Follow Svelte best practices
- Use meaningful component names
- Keep components focused

### Testing
- Write unit tests for new functionality
- Maintain existing test coverage
- Run tests before submitting PRs:
  ```bash
  # Backend
  cd codelens-be && mvn test

  # Frontend
  cd codelens-fe && npm run test
  ```

## Reporting Issues

### Bug Reports
Include:
- Clear description of the bug
- Steps to reproduce
- Expected vs actual behavior
- Environment details (OS, Java version, Node version)
- Relevant logs or screenshots

### Feature Requests
Include:
- Clear description of the feature
- Use case / problem it solves
- Proposed solution (if any)

## Security

If you discover a security vulnerability, please **do not** open a public issue. Instead, email bhuvangupta@yahoo.com with details.

## Questions?

- Open a [Discussion](https://github.com/bhuvangupta/codelens/discussions)
- Check existing issues and PRs

## License

By contributing, you agree that your contributions will be licensed under the Apache License 2.0.
