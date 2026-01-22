# CodeMedic

An automated error analysis and fixing tool that parses application logs, clusters errors, and uses AI to automatically apply fixes to your codebase.

## Features

- **Log Parsing & Clustering**: Parses structured logs (v1 format) and clusters similar errors
- **AI-Powered Fixes**: Uses OpenCode AI to analyze errors and apply code fixes automatically
- **Git Integration**: Automated branch creation, commits, and pull request creation
- **Web Interface**: Modern Next.js frontend for visual error inspection and fix management
- **Streaming Output**: Real-time streaming of OpenCode fix process

## Project Structure

```
codeMedic/
├── agent.py          # Core logic for log parsing, OpenCode integration, and Git operations
├── server.py         # FastAPI backend server
├── dashboard.py      # Streamlit dashboard (alternative UI)
├── config.json       # Configuration file (log path, repo path)
├── pyproject.toml    # Python dependencies
├── frontend/         # Next.js web application
│   ├── app/
│   ├── components/
│   └── package.json
└── .python-version   # Python 3.13
```

## Prerequisites

### Required Tools

1. **Python 3.13+**
   - Install via pyenv: `brew install pyenv` then `pyenv install 3.13`
   - Or download from [python.org](https://www.python.org/)

2. **uv** (Python package manager)
   ```bash
   curl -LsSf https://astral.sh/uv/install.sh | sh
   ```

3. **OpenCode CLI**
   - Must be installed and configured in your shell (https://github.com/anomalyco/opencode)
   - Used for AI-powered code analysis and fixes

4. **Node.js 20+** (for frontend)
   - Install via nvm (recommended):
     ```bash
     curl -o- https://raw.githubusercontent.com/nvm-sh/nvm/v0.40.1/install.sh | bash
     source ~/.zshrc  # or restart your terminal
     nvm install 20
     nvm use 20
     ```
   - Or via Homebrew: `brew install node`
   - Or from [nodejs.org](https://nodejs.org/)

5. **GitHub CLI** (optional, for PR creation)
   ```bash
   brew install gh
   gh auth login
   ```

6. **Git**
   - Required for all repository operations

## Installation


### 1. Install Python Dependencies

The project uses `uv` for dependency management. Dependencies are automatically installed via the lock file.

```bash
uv sync
```

Note: You don't need to activate the virtual environment. Use `uv run` to execute commands (e.g., `uv run python agent.py`).

### 2. Install Frontend Dependencies

```bash
cd frontend
npm install
cd ..
```

### 3. Configure Application

The `config.json` file is **optional for web users** but **required for CLI/dashboard users**.

**For CLI or Streamlit Dashboard mode**, create `config.json` in the project root:

```json
{
  "repo_path": "/path/to/your/codebase"
}
```

**For Web Interface users:**
- No config needed - upload logs directly via the UI
- Set `REPO_BASE_PATH` in `frontend/app/page.tsx` (line 11) to your codebase directory

## Running the Application

### Option 1: Full Web Application (Recommended)

Start both the FastAPI backend and Next.js frontend:

**Terminal 1 - Backend:**
```bash
uv run uvicorn server:app --reload --host 0.0.0.0 --port 8000
```

**Terminal 2 - Frontend (Development):**
```bash
cd frontend
npm run dev
```

Then open:
- Frontend: http://localhost:3000
- Backend API: http://localhost:8000
- API Docs: http://localhost:8000/docs

### Option 2: Streamlit Dashboard

```bash
uv run streamlit run dashboard.py
```

### Option 3: Command Line Interface

```bash
uv run python agent.py <path_to_log_file>
```

This will:
1. Parse the log file specified in the argument
2. Display clustered errors with occurrence counts
3. Prompt you to select an error to fix
4. Run OpenCode to apply the fix
5. Create a new branch and commit the changes

## API Endpoints

### Configuration
- `GET /config` - Get current configuration
- `GET /models` - List available OpenCode models

### Log Analysis
- `POST /logs/upload` - Upload a log file
- `POST /logs/analyze` - Analyze log content
- `POST /logs/analyze_file` - Analyze log file by path
- `POST /logs/cleanup` - Clean up temporary files

### Repository Operations
- `POST /repo/sync` - Checkout master and pull latest
- `GET /repo/diff?repo_path=...` - Get git diff
- `POST /repo/discard` - Discard changes
- `POST /repo/commit` - Commit changes
- `POST /repo/push` - Push branch to remote
- `POST /repo/create-pr` - Create pull request

### Fix Operations
- `POST /fix/start` - Start OpenCode fix (streaming)
- `POST /fix/cancel` - Cancel running fix

## Usage Workflow

### Web Interface
1. **Upload Logs**: Use the file upload in the sidebar OR paste log content directly
2. **Select Repository**: Choose your repository from the dropdown (configured via `REPO_BASE_PATH`)
3. **Select Error**: Choose an error from the clustered list
4. **Apply Fix**: Click "Fix" to run OpenCode AI analysis
5. **Review Changes**: View the git diff to see what changed
6. **Commit & Push**: Commit the fix to a new branch and push to remote
7. **Create PR**: Optionally create a pull request automatically

### CLI Interface
1. **Configure**: Set up `config.json` with your log and repo paths
2. **Run**: `python agent.py`
3. **Select**: Choose an error from the displayed list
4. **Confirm**: OpenCode will automatically apply the fix and commit to a new branch

## Log Format

The parser expects structured logs in the following format:

```
v1|timestamp|...|level|...|message
at com.example.Class.method(Class.java:123)
at com.example.Another.method(Another.java:456)
```

- Lines starting with `v1|` indicate new log entries
- Lines with `ERROR` in the level field are treated as errors
- Stack traces starting with `at `, `Caused by:`, or `... ` are clustered together

## Development

### Backend (FastAPI)

```bash
uv run uvicorn server:app --reload
```

### Frontend (Next.js)

#### Development
```bash
cd frontend
npm run dev
```

#### Production Deployment
```bash
cd frontend
npm run build   # Build for production
npm run start   # Start production server (serves on port 3000)
```

#### Linting
```bash
cd frontend
npm run lint
```

## Troubleshooting

### OpenCode Not Found
Ensure OpenCode is installed and sourced in your `~/.zshrc`:
```bash
source ~/.zshrc && opencode models
```

### Python Version Issues
Ensure Python 3.13+ is installed:
```bash
python --version  # Should show 3.13.x
```

### Port Already in Use
Change the port if 8000 or 3000 are occupied:
```bash
uv run uvicorn server:app --port 8001
cd frontend && npm run dev -- --port 3001
```

### GitHub CLI Issues
Authenticate if PR creation fails:
```bash
gh auth login
```

## License

Add your license information here.

## Contributing

Add contribution guidelines here.
