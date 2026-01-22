from fastapi import FastAPI, HTTPException, Body, UploadFile, File, Request
from fastapi.responses import StreamingResponse
from fastapi.middleware.cors import CORSMiddleware
from pydantic import BaseModel
from typing import List, Optional
import agent
import os
import subprocess
import asyncio
import json
import tempfile
import shutil
import logging
import sys
import uuid
import time
from collections import defaultdict
from contextlib import contextmanager

# Configure logging
LOG_FILE = "/tmp/codemedic.log"
logging.basicConfig(
    level=logging.DEBUG,
    format='%(asctime)s - %(name)s - %(levelname)s - %(message)s',
    handlers=[
        logging.StreamHandler(sys.stdout),
        logging.FileHandler(LOG_FILE)
    ]
)
logger = logging.getLogger(__name__)

# --- Job Registry for Queue Visibility ---
# Stores list of jobs per repo_path
# Structure: { repo_path: [ { id, type, status, created_at, details } ] }
job_registry = defaultdict(list)

@contextmanager
def track_job(repo_path: str, job_type: str, details: str = ""):
    """
    Context manager to track a job in the global registry.
    This provides visibility into what is currently running or queued (waiting for lock).
    """
    job_id = str(uuid.uuid4())
    job_info = {
        "id": job_id,
        "type": job_type,
        "status": "running", # effectively "queued or running"
        "created_at": time.time(),
        "details": details
    }
    
    # Add to registry
    job_registry[repo_path].append(job_info)
    logger.info(f"➕ Job added to queue: {job_type} for {repo_path} (ID: {job_id})")
    
    try:
        yield job_id
    finally:
        # Remove from registry upon completion OR mark as done if we wanted history
        if repo_path in job_registry:
            # Filter out this job
            job_registry[repo_path] = [j for j in job_registry[repo_path] if j["id"] != job_id]
            # Clean up empty keys
            if not job_registry[repo_path]:
                del job_registry[repo_path]
        logger.info(f"➖ Job removed from queue: {job_type} for {repo_path} (ID: {job_id})")

app = FastAPI(title="CodeMedic API")

# Add middleware to log all requests
@app.middleware("http")
async def log_requests(request: Request, call_next):
    logger.info(f"📥 {request.method} {request.url.path}")
    logger.debug(f"Headers: {dict(request.headers)}")

    # Log body for POST/PUT requests
    if request.method in ["POST", "PUT"]:
        try:
            body = await request.body()
            if body:
                logger.debug(f"Body: {body[:500]}")  # Log first 500 chars
        except Exception as e:
            logger.debug(f"Could not log body: {e}")

    response = await call_next(request)

    logger.info(f"📤 {request.method} {request.url.path} - Status: {response.status_code}")
    return response

# Enable CORS for frontend
app.add_middleware(
    CORSMiddleware,
    allow_origins=["*"], # Allow all for dev
    allow_credentials=True,
    allow_methods=["*"],
    allow_headers=["*"],
)

# --- Pydantic Models ---
# ConfigResponse is now a dynamic dict of repo_name -> repo_path
ConfigResponse = dict[str, str]

class ErrorCluster(BaseModel):
    message: str
    count: int
    trace: str

class AnalyzeRequest(BaseModel):
    log_content: str

class FixRequest(BaseModel):
    repo_path: str
    error_trace: str
    model: Optional[str] = None

class RepoRequest(BaseModel):
    repo_path: str

class CommitRequest(BaseModel):
    repo_path: str
    message: str
    branch_name: Optional[str] = None  # For worktree flow - use specific branch for PR

class PullRequestRequest(BaseModel):
    repo_path: str
    title: str
    body: Optional[str] = None

class CancelRequest(BaseModel):
    job_id: str

# --- Endpoints ---

@app.get("/config", response_model=ConfigResponse)
def get_config():
    """Load default config."""
    logger.info("Getting config")
    config = agent.load_config()
    logger.debug(f"Config loaded: {config}")
    return config

@app.get("/models")
def get_models():
    """List available OpenCode models."""
    logger.info("Fetching available models")
    models = agent.get_available_models()
    logger.info(f"Found {len(models)} models")
    return models

@app.get("/queue")
def get_queue(repo_path: Optional[str] = None):
    """
    Get the current job queue. 
    If repo_path is provided, returns jobs for that specific repo.
    Otherwise returns all jobs.
    """
    if repo_path:
        return {"repo": repo_path, "jobs": job_registry.get(repo_path, [])}
    
    # Return all
    return {
        "queues": {k: v for k, v in job_registry.items()}
    }

@app.post("/logs/upload")
async def upload_log_file(file: UploadFile = File(...)):
    """Upload a log file to temp directory and return the path."""
    try:
        # Create a temporary file in /tmp
        suffix = os.path.splitext(file.filename)[1] if file.filename else '.log'
        temp_file = tempfile.NamedTemporaryFile(delete=False, suffix=suffix, mode='wb', dir='/tmp')

        print(f"[upload] Uploading file: {file.filename}")
        print(f"[upload] Temp file created at: {temp_file.name}")

        # Write uploaded file to temp location in chunks (handle large files)
        chunk_size = 1024 * 1024  # 1MB chunks
        total_bytes = 0
        while True:
            chunk = await file.read(chunk_size)
            if not chunk:
                break
            temp_file.write(chunk)
            total_bytes += len(chunk)

        temp_file.close()

        file_size = os.path.getsize(temp_file.name)
        print(f"[upload] Upload complete: {total_bytes} bytes written")
        print(f"[upload] File exists after close: {os.path.exists(temp_file.name)}")
        print(f"[upload] File size on disk: {file_size} bytes")

        return {
            "temp_path": temp_file.name,
            "original_filename": file.filename,
            "size": file_size
        }
    except Exception as e:
        print(f"[upload] ERROR: {str(e)}")
        raise HTTPException(status_code=500, detail=f"Failed to upload file: {str(e)}")

@app.post("/logs/analyze", response_model=List[ErrorCluster])
def analyze_logs(request: AnalyzeRequest):
    """Parse log content and return clusters."""
    if not request.log_content or not request.log_content.strip():
        raise HTTPException(status_code=400, detail="Log content is empty")

    errors = agent.parse_log_content(request.log_content)
    return errors

@app.post("/logs/analyze_file", response_model=List[ErrorCluster])
def analyze_log_file(file_path: str = Body(..., embed=True)):
    """Parse log file from path and return clusters."""
    print(f"[analyze_log_file] Request received for file: {file_path}")
    print(f"[analyze_log_file] File exists: {os.path.exists(file_path)}")

    if not os.path.exists(file_path):
        print(f"[analyze_log_file] ERROR: File not found at {file_path}")
        # List temp directory to debug
        import glob
        temp_files = glob.glob(os.path.join(tempfile.gettempdir(), "tmp*"))
        print(f"[analyze_log_file] Temp directory has {len(temp_files)} tmp files")
        if temp_files:
            print(f"[analyze_log_file] First few temp files: {temp_files[:5]}")
        raise HTTPException(status_code=404, detail=f"Log file not found at {file_path}")

    # Don't delete temp file here - keep it for re-analysis
    print(f"[analyze_log_file] Starting analysis...")
    errors = agent.parse_log_clusters(file_path)
    print(f"[analyze_log_file] Analysis complete, found {len(errors)} error clusters")
    return errors

@app.post("/logs/cleanup")
def cleanup_temp_file(file_path: str = Body(..., embed=True)):
    """Clean up a temporary log file."""
    print(f"[cleanup] Request to cleanup: {file_path}")
    # Allow cleanup of files in /tmp or system temp directory
    is_temp_file = file_path and (file_path.startswith('/tmp/') or file_path.startswith(tempfile.gettempdir()))
    if is_temp_file:
        try:
            if os.path.exists(file_path):
                os.remove(file_path)
                print(f"[cleanup] File deleted: {file_path}")
                return {"message": "Temp file cleaned up"}
            else:
                print(f"[cleanup] File does not exist: {file_path}")
                return {"message": "File already deleted"}
        except Exception as e:
            print(f"[cleanup] ERROR: {str(e)}")
            raise HTTPException(status_code=500, detail=f"Failed to cleanup: {str(e)}")
    print(f"[cleanup] Not a temp file, skipping cleanup")
    return {"message": "No cleanup needed"}

@app.post("/repo/sync")
def sync_repo(request: RepoRequest):
    """Checkout master and pull."""
    logger.info(f"Syncing repo: {request.repo_path}")
    
    with track_job(request.repo_path, "sync", "Syncing repository"):
        success, msg = agent.prepare_repo(request.repo_path)
        if success:
            logger.info(f"Repo sync successful: {msg}")
        else:
            logger.error(f"Repo sync failed: {msg}")
            raise HTTPException(status_code=500, detail=msg)
    return {"message": msg}

@app.get("/repo/diff")
def get_diff(repo_path: str):
    """Get current git diff."""
    return {"diff": agent.get_git_diff(repo_path)}

@app.post("/repo/discard")
def discard_changes(request: RepoRequest):
    """Discard changes in repo."""
    success, msg = agent.discard_changes(request.repo_path)
    if not success:
        raise HTTPException(status_code=500, detail=msg)
    return {"message": msg}

@app.post("/repo/commit")
def commit_changes(request: CommitRequest):
    """Commit changes."""
    logger.info(f"Committing changes to repo: {request.repo_path}")
    logger.debug(f"Commit message: {request.message[:100]}")
    
    with track_job(request.repo_path, "commit", f"Committing: {request.message[:50]}"):
        success, msg = agent.run_git_commands(request.repo_path, request.message)
        if success:
            logger.info(f"Commit successful: {msg}")
        else:
            logger.error(f"Commit failed: {msg}")
            raise HTTPException(status_code=500, detail=msg)
    return {"message": msg}

@app.post("/repo/push")
def push_branch(request: RepoRequest):
    """Push current branch to remote origin."""
    logger.info(f"Pushing branch for repo: {request.repo_path}")
    
    with track_job(request.repo_path, "push", "Pushing branch"):
        success, msg = agent.push_branch(request.repo_path)
        if success:
            logger.info(f"Push successful: {msg}")
        else:
            logger.error(f"Push failed: {msg}")
            raise HTTPException(status_code=500, detail=msg)
    return {"message": msg}

@app.post("/repo/commit-and-push")
def commit_and_push(request: CommitRequest):
    """Commit changes and push to remote in one operation."""
    logger.info(f"🚀 One-click commit and push for repo: {request.repo_path}")
    logger.debug(f"Commit message: {request.message[:100]}")

    with track_job(request.repo_path, "commit_push", f"Commit & Push: {request.message[:50]}"):
        # First commit
        commit_success, commit_msg = agent.run_git_commands(request.repo_path, request.message)
        if not commit_success:
            logger.error(f"Commit failed: {commit_msg}")
            raise HTTPException(status_code=500, detail=f"Commit failed: {commit_msg}")
        logger.info(f"✅ Commit successful: {commit_msg}")

        # Then push
        push_success, push_msg = agent.push_branch(request.repo_path)
        if not push_success:
            logger.error(f"Push failed: {push_msg}")
            raise HTTPException(status_code=500, detail=f"Push failed: {push_msg}")
        logger.info(f"✅ Push successful: {push_msg}")

        return {
            "message": f"✅ Successfully committed and pushed changes!",
            "commit_message": commit_msg,
            "push_message": push_msg
        }

@app.post("/repo/commit-push-and-pr")
def commit_push_and_pr(request: CommitRequest):
    """Commit changes, push to remote, and create PR in one operation."""
    logger.info(f"🚀 One-click commit, push & PR for repo: {request.repo_path}")
    logger.debug(f"Commit message: {request.message[:100]}")
    logger.debug(f"Branch name from request: {request.branch_name}")

    with track_job(request.repo_path, "commit_push_pr", f"Commit, Push & PR: {request.message[:50]}"):
        # If branch_name is provided (worktree flow), use it directly for PR creation
        # This handles the case where multiple users are working and the current checkout
        # might be a different user's branch
        if request.branch_name and request.branch_name.startswith("fix/"):
            branch_name = request.branch_name
            logger.info(f"✅ Using provided branch {branch_name} (worktree flow). Skipping commit/push.")
            commit_msg = f"Changes already committed in branch: {branch_name}"
            push_msg = f"Branch {branch_name} already pushed to origin"
        else:
            # Check if we're on a worktree-created branch that's already pushed
            is_ready, branch_name = agent.is_worktree_branch_ready(request.repo_path)

            if is_ready:
                logger.info(f"✅ Branch {branch_name} is already committed and pushed (worktree flow). Skipping to PR creation.")
                commit_msg = f"Changes already committed in branch: {branch_name}"
                push_msg = f"Branch {branch_name} already pushed to origin"
            else:
                # Standard flow: commit and push first
                # First commit
                commit_success, commit_msg = agent.run_git_commands(request.repo_path, request.message)
                if not commit_success:
                    logger.error(f"Commit failed: {commit_msg}")
                    raise HTTPException(status_code=500, detail=f"Commit failed: {commit_msg}")
                logger.info(f"✅ Commit successful: {commit_msg}")
                # Get the branch name from commit message
                branch_name = agent.get_current_branch(request.repo_path)

                # Then push
                push_success, push_msg = agent.push_branch(request.repo_path)
                if not push_success:
                    logger.error(f"Push failed: {push_msg}")
                    raise HTTPException(status_code=500, detail=f"Push failed: {push_msg}")
                logger.info(f"✅ Push successful: {push_msg}")

        # Finally create PR using the specific branch name
        pr_title = request.message[:100]  # Use commit message as PR title
        pr_success, pr_msg, pr_url = agent.create_pull_request(request.repo_path, pr_title, branch_name=branch_name)
        if not pr_success:
            logger.error(f"PR creation failed: {pr_msg}")
            raise HTTPException(status_code=500, detail=f"PR creation failed: {pr_msg}")
        logger.info(f"✅ PR created successfully: {pr_url}")

        return {
            "message": f"✅ Successfully committed, pushed, and created PR!",
            "commit_message": commit_msg,
            "push_message": push_msg,
            "pr_message": pr_msg,
            "pr_url": pr_url
        }

@app.post("/repo/create-pr")
def create_pull_request(request: PullRequestRequest):
    """Create a pull request using GitHub CLI."""
    success, msg, pr_url = agent.create_pull_request(
        request.repo_path,
        request.title,
        request.body
    )
    if not success:
        raise HTTPException(status_code=500, detail=msg)
    return {"message": msg, "pr_url": pr_url}

@app.post("/fix/start")
def start_fix(request: FixRequest):
    """Trigger OpenCode analysis with streaming output."""
    logger.info(f"🔧 Starting fix for repo: {request.repo_path}")
    logger.debug(f"Model: {request.model}")
    logger.debug(f"Error trace length: {len(request.error_trace)} chars")
    logger.debug(f"First 200 chars of error: {request.error_trace[:200]}")

    # Generate job_id upfront so we can send it to client and use for cancellation
    job_id = str(uuid.uuid4())

    def generate():
        line_count = 0
        try:
            logger.info(f"Starting opencode process with job_id: {job_id}")

            # Send job_id as first event so client can use it for cancellation
            yield f"event: job_id\ndata: {job_id}\n\n"

            # Track job inside the generator (pass job_id for consistency)
            with track_job(request.repo_path, "fix", f"Applying AI Fix (job: {job_id})"):
                # Iterate over generator - pass job_id for process registration
                for item in agent.run_opencode_fix(request.repo_path, request.error_trace, job_id=job_id, model=request.model):
                    line_count += 1
                    if isinstance(item, tuple):
                        # Tuple can be (success, msg) or (success, msg, branch_name)
                        if len(item) == 3:
                            success, msg, branch_name = item
                        else:
                            success, msg = item
                            branch_name = None
                        logger.info(f"OpenCode process completed. Success: {success}, Branch: {branch_name}")
                        logger.debug(f"Final message: {msg[:200]}")
                        # Final result event - include branch_name for PR creation
                        result_data = json.dumps({
                            "success": success,
                            "message": msg,
                            "job_id": job_id,
                            "branch_name": branch_name
                        })
                        yield f"event: complete\ndata: {result_data}\n\n"
                    else:
                        # Log line event
                        logger.debug(f"OpenCode output: {item[:100]}")
                        # Sanitize newlines to ensure SSE format
                        safe_line = item.replace('\n', ' ')
                        yield f"data: {safe_line}\n\n"

            logger.info(f"Stream ended. Total lines: {line_count}")
        except Exception as e:
            logger.error(f"Error in generate(): {e}", exc_info=True)
            # If we crash, we might want to yield an error event
            err_data = json.dumps({"success": False, "message": str(e), "job_id": job_id})
            yield f"event: complete\ndata: {err_data}\n\n"
            raise

    return StreamingResponse(generate(), media_type="text/event-stream")

@app.post("/fix/cancel")
def cancel_fix(request: CancelRequest):
    """Cancel running OpenCode process for a specific job."""
    success, msg = agent.cancel_opencode_fix(request.job_id)
    if not success:
        raise HTTPException(status_code=400, detail=msg)
    return {"message": msg}

if __name__ == "__main__":
    import uvicorn
    logger.info("🚀 Starting CodeMedic API server on http://0.0.0.0:8000")
    uvicorn.run(
        app,
        host="0.0.0.0",
        port=8000,
        log_level="debug",
        access_log=True
    )
