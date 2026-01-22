import json
import os
import re
import subprocess
import sys
import shlex
import logging
import threading

import logging
import fcntl
from contextlib import contextmanager

# Thread-level locks per repository path
# fcntl.flock() only provides inter-process locking, not intra-process (thread) locking
# FastAPI runs sync endpoints in a thread pool, so we need both:
# - threading.Lock() for thread synchronization within the same process
# - fcntl.flock() for process synchronization across different processes
_repo_thread_locks: dict[str, threading.Lock] = {}
_repo_thread_locks_lock = threading.Lock()  # Lock to protect the dict itself


def _get_thread_lock(repo_path: str) -> threading.Lock:
    """Get or create a thread lock for the given repo path."""
    with _repo_thread_locks_lock:
        if repo_path not in _repo_thread_locks:
            _repo_thread_locks[repo_path] = threading.Lock()
        return _repo_thread_locks[repo_path]

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

@contextmanager
def repo_lock(repo_path):
    """
    Context manager to acquire an exclusive lock on a repository.
    Prevents concurrent operations from multiple requests corrupting the repo state.

    Uses two-level locking:
    1. threading.Lock() - for thread synchronization within the same process
       (FastAPI runs sync endpoints in a thread pool)
    2. fcntl.flock() - for process synchronization across different processes
       (in case multiple server instances are running)
    """
    # First, acquire the thread-level lock (handles concurrent threads in same process)
    thread_lock = _get_thread_lock(repo_path)

    logger.debug(f"⏳ Acquiring thread lock for {repo_path}...")
    thread_lock.acquire()
    logger.debug(f"🔒 Thread lock acquired for {repo_path}")

    try:
        # Then, acquire the file-level lock (handles concurrent processes)
        git_dir = os.path.join(repo_path, ".git")
        if not os.path.exists(git_dir):
            os.makedirs(git_dir, exist_ok=True)

        lock_file_path = os.path.join(git_dir, "codemedic_ops.lock")

        # Open file for writing (creates if not exists)
        lock_file = open(lock_file_path, 'w')

        try:
            logger.debug(f"⏳ Acquiring file lock for {repo_path}...")
            # LOCK_EX: Exclusive lock. This will BLOCK until lock is available.
            fcntl.flock(lock_file, fcntl.LOCK_EX)
            logger.debug(f"🔒 File lock acquired for {repo_path}")
            yield
        finally:
            # Unlock and close file lock
            try:
                fcntl.flock(lock_file, fcntl.LOCK_UN)
            except Exception:
                pass
            lock_file.close()
            logger.debug(f"🔓 File lock released for {repo_path}")
    finally:
        # Always release thread lock
        thread_lock.release()
        logger.debug(f"🔓 Thread lock released for {repo_path}")


# IDE files to exclude from commits and diffs
IDE_FILE_PATTERNS = [".classpath", ".project", ".factorypath", ".settings", ".idea", ".vscode"]


def is_ide_file(file_path: str) -> bool:
    """Check if a file path matches IDE file patterns."""
    normalized = file_path.replace('\\', '/')
    parts = normalized.split('/')
    for pattern in IDE_FILE_PATTERNS:
        if pattern in parts or normalized.endswith(pattern):
            return True
    return False


def unstage_ide_files(repo_path: str) -> int:
    """Unstage IDE files from git staging area. Returns count of unstaged files."""
    result = subprocess.run(
        ["git", "diff", "--cached", "--name-only"],
        cwd=repo_path, capture_output=True, text=True
    )
    staged_files = result.stdout.strip().split('\n') if result.stdout.strip() else []

    unstaged_count = 0
    for staged_file in staged_files:
        if is_ide_file(staged_file):
            logger.info(f"  Unstaging IDE file: {staged_file}")
            # Try reset first
            res = subprocess.run(
                ["git", "reset", "HEAD", "--", staged_file],
                cwd=repo_path, check=False, capture_output=True
            )
            if res.returncode != 0:
                # Fallback to git rm --cached
                subprocess.run(
                    ["git", "rm", "--cached", "--force", "--", staged_file],
                    cwd=repo_path, check=False, capture_output=True
                )
            unstaged_count += 1

    if unstaged_count > 0:
        logger.info(f"Unstaged {unstaged_count} IDE file(s)")
    return unstaged_count


def load_config(config_path="config.json"):
    try:
        with open(config_path, "r") as f:
            return json.load(f)
    except FileNotFoundError:
        print(f"Error: Config file not found at {config_path}")
        sys.exit(1)

def strip_ansi_codes(text):
    """Remove ANSI color codes from text."""
    ansi_escape = re.compile(r'\x1B(?:[@-Z\\-_]|\[[0-?]*[ -/]*[@-~])')
    return ansi_escape.sub('', text)

def parse_log_content(log_content):
    """Parse log content from a string."""
    print(f"Parsing log content ({len(log_content)} chars)")
    error_data = {} # Map msg -> {'count': int, 'trace': str}

    pending_error_msg = None
    pending_trace = []
    stack_confirmed = False

    # Regex for standard new entry (with optional prefix)
    # Matches: [Optional Prefix] [Date YYYY-MM-DD or YYYY/MM/DD]
    # Prefix: digits followed by - or :
    regex_entry = re.compile(r'^(?:(\d+[-:])\s*)?(\d{4}[-/]\d{2}[-/]\d{2})')
    # Regex to strip prefix from continuation lines
    regex_prefix_continuation = re.compile(r'^\d+[-:]')

    # Helper to finalize block
    def finalize_block(msg, trace, data_dict):
        key = msg
        # Try to append first stack line for better context/clustering
        if len(trace) > 1:
            first_trace_line = trace[1].strip()
            # Truncate if too long to keep UI clean
            key = f"{msg} \n {first_trace_line[:100]}"

        if key not in data_dict:
             data_dict[key] = {'count': 1, 'trace': "".join(trace)}
        else:
             data_dict[key]['count'] += 1

    lines = log_content.split('\n')
    for line in lines:
        # Strip ANSI codes first
        clean_line = strip_ansi_codes(line)
        
        match_entry = regex_entry.match(clean_line)
        is_new_entry = False
        
        if match_entry:
            is_new_entry = True
            # If prefix present, strip it
            if match_entry.group(1):
                clean_line = clean_line[match_entry.start(2):]
        elif clean_line.startswith("v1|"):
            is_new_entry = True
        else:
            # Try to strip line number prefix (e.g., "28266974-" or "28266974:")
            prefix_match = regex_prefix_continuation.match(clean_line)
            if prefix_match:
                clean_line = clean_line[len(prefix_match.group(0)):]
                # After stripping prefix, check if it's a v1 entry
                if clean_line.startswith("v1|"):
                    is_new_entry = True
        
        stripped = clean_line.strip()
        
        if is_new_entry:
            # End of previous block: Did we have an error with a confirmed stack trace?
            if pending_error_msg and stack_confirmed:
                finalize_block(pending_error_msg, pending_trace, error_data)

            # Reset for new block
            pending_error_msg = None
            pending_trace = []
            stack_confirmed = False

            # Check if THIS new line is an error
            # 1. v1 format
            if stripped.startswith("v1|"):
                parts = stripped.split("|")
                if len(parts) > 9:
                    level = parts[6].strip()
                    if "ERROR" in level:
                        pending_error_msg = parts[9].strip()
                        pending_trace.append(clean_line + '\n')
            # 2. Python format: "YYYY-MM-DD ... - ERROR - ..."
            else:
                if " - ERROR - " in stripped:
                    # Extract message: everything after " - ERROR - "
                    # Format: DATE - MODULE - ERROR - FILE:LINE - FUNC - MSG
                    parts = stripped.split(" - ERROR - ", 1)
                    if len(parts) == 2:
                        pending_error_msg = parts[1].strip()
                        pending_trace.append(clean_line + '\n')
        else:
            # Continuation line
            if pending_error_msg:
                pending_trace.append(clean_line + '\n')
                
                # Check for Stack Traces
                # Java: "at ...", "Caused by: ...", "... ", exception class names
                # Python: "File "...", line ...", "Traceback (most recent call last):", "During handling of the above exception..."
                if (stripped.startswith("at ") or
                    stripped.startswith("Caused by:") or
                    stripped.startswith("... ") or
                    stripped.startswith('File "') or
                    stripped.startswith("Traceback (") or
                    stripped.startswith("During handling of") or
                    # Java exception class names (e.g., "java.lang.NullPointerException:")
                    (("Exception:" in stripped or "Error:" in stripped) and "." in stripped)):
                    stack_confirmed = True

    # End of content check
    if pending_error_msg and stack_confirmed:
        finalize_block(pending_error_msg, pending_trace, error_data)

    # Convert to list of dicts
    unique_errors = []
    for msg, data in error_data.items():
        unique_errors.append({"message": msg, "count": data['count'], "trace": data['trace']})

    # Sort by count desc
    unique_errors.sort(key=lambda x: x["count"], reverse=True)
    return unique_errors

def parse_log_clusters(log_path):
    """Parse log file and return clusters."""
    print(f"Parsing log file: {log_path}")

    try:
        with open(log_path, "r", encoding="utf-8", errors="ignore") as f:
            log_content = f.read()
            return parse_log_content(log_content)
    except FileNotFoundError:
        print(f"Error: Log file not found at {log_path}")
        sys.exit(1)

def get_available_models():
    """Fetches available models from opencode."""
    try:
        cmd = 'source ~/.zshrc && opencode models'
        result = subprocess.run(cmd, shell=True, executable="/bin/zsh", capture_output=True, text=True)
        if result.returncode == 0:
            # Filter distinct non-empty lines, ignore version numbers if any
            models = [line.strip() for line in result.stdout.split('\n') if line.strip() and not line.strip()[0].isdigit()]
            return models
        return []
    except Exception:
        return []

# Process tracker for cancellation support - keyed by job_id for multi-user concurrency
_opencode_processes: dict[str, subprocess.Popen] = {}
_opencode_processes_lock = threading.Lock()


def cancel_opencode_fix(job_id: str):
    """Cancel the running OpenCode process for a specific job."""
    with _opencode_processes_lock:
        process = _opencode_processes.get(job_id)
        if process and process.poll() is None:
            process.terminate()
            try:
                process.wait(timeout=5)
            except subprocess.TimeoutExpired:
                process.kill()
            _opencode_processes.pop(job_id, None)
            return True, f"OpenCode process {job_id} cancelled."
        return False, f"No running process found for job {job_id}."


def _register_process(job_id: str, process: subprocess.Popen):
    """Register a process for a job."""
    with _opencode_processes_lock:
        _opencode_processes[job_id] = process


def _unregister_process(job_id: str):
    """Unregister a process for a job."""
    with _opencode_processes_lock:
        _opencode_processes.pop(job_id, None)


def run_opencode_fix(repo_path, error_context, job_id: str, model=None):
    """
    Runs opencode fix using Git Worktrees for concurrency.
    1. Creates temporary worktree + branch.
    2. Runs fix in worktree.
    3. Commits & Pushes from worktree.
    4. Applies changes to Main Repo (Checkout + Soft Reset) for user review.
    """
    import time
    
    logger.info(f"🔧 Delegating fix to OpenCode for repo: {repo_path}")
    logger.debug(f"Model: {model}")
    
    # 1. Setup Worktree
    import time
    import random
    # Use random suffix to avoid collision if two requests arrive in same second
    ts = int(time.time())
    rand_suffix = random.randint(1000, 9999)
    branch_name = f"fix/error-{ts}-{rand_suffix}"
    worktree_path = None
    
    try:
        yield f"Setting up isolated worktree for branch {branch_name}..."
        worktree_path = create_worktree(repo_path, branch_name)
        
        # Construct Prompts (Use worktree_path in prompt to be safe, though OpenCode mostly uses cwd)
        prompt = (
            f"Analyze the repository at {worktree_path}. "
            f"I have found the following error log:\n\n{error_context}\n\n"
            "Locate the code responsible for this error and apply a fix directly to the file(s). "
            "Do not ask for confirmation, just apply the code changes."
        )
        safe_prompt = shlex.quote(prompt)

        # Construct command - run in WORKTREE
        base_cmd = 'source ~/.zshrc && opencode run --print-logs'
        if model:
            base_cmd += f' --model {shlex.quote(model)}'
        else:
            base_cmd += ' --model zai-coding-plan/glm-4.7'
        cmd = f'{base_cmd} {safe_prompt}'

        logger.info(f"📝 Executing OpenCode command in worktree: {worktree_path}")

        full_output = []
        process = subprocess.Popen(
            cmd,
            shell=True,
            cwd=worktree_path, # IMPORTANT: Run in worktree
            executable="/bin/zsh",
            stdout=subprocess.PIPE,
            stderr=subprocess.STDOUT,
            text=True,
            bufsize=1,
            universal_newlines=True
        )
        # Register process with job_id for per-user cancellation support
        _register_process(job_id, process)

        # Stream output
        for line in process.stdout:
            line_clean = line.strip()
            if line_clean:
                full_output.append(line_clean)
                yield line_clean

        return_code = process.wait()
        combined_output = "\n".join(full_output)

        if return_code != 0:
            yield (False, f"OpenCode failed (Exit {return_code}):\n{combined_output}")
            return

        if not combined_output.strip():
            yield (True, "OpenCode completed with Exit 0 but returned NO output.")
            return

        # Success - Now we need to transfer these changes to the user's view (Main Repo)
        logger.info("✅ OpenCode completed in worktree. Committing and applying to main repo...")
        yield "Finalizing changes..."

        # 2. Commit & Push in Worktree
        # Check if changes exist
        diff_res = subprocess.run(["git", "diff", "--name-only"], cwd=worktree_path, capture_output=True, text=True)
        if not diff_res.stdout.strip():
             yield (False, "OpenCode succeeded but no files were changed.")
             return

        subprocess.run(["git", "add", "."], cwd=worktree_path, check=True)

        # Unstage IDE files before commit
        unstage_ide_files(worktree_path)

        # Check if we still have changes to commit after unstaging IDE files
        diff_check = subprocess.run(["git", "diff", "--cached", "--quiet"], cwd=worktree_path)
        if diff_check.returncode == 0:
            yield (False, "OpenCode succeeded but only IDE files were changed (excluded from commit).")
            return

        commit_env = os.environ.copy()
        commit_env["GIT_COMMITTER_NAME"] = "CodeMedic Bot"
        commit_env["GIT_COMMITTER_EMAIL"] = "codemedic@automated.local"
        subprocess.run([
            "git", "commit",
            "-m", "AI Fix (Worktree)",
            "--author", "CodeMedic Bot <codemedic@automated.local>"
        ], cwd=worktree_path, check=True, env=commit_env)

        # Each user pushes to their own unique branch - Git handles remote ref locking
        # No repo_lock needed here, allows concurrent pushes from different worktrees
        subprocess.run(["git", "push", "-u", "origin", branch_name], cwd=worktree_path, check=True)

        # IMPORTANT: Cleanup worktree BEFORE applying to main repo
        # Git doesn't allow checking out a branch that's already checked out in a worktree
        # We must remove the worktree first to free up the branch
        yield "Cleaning up worktree before applying to main workspace..."
        cleanup_worktree(repo_path, worktree_path)
        worktree_path = None  # Mark as cleaned so finally block doesn't try again

        # 3. Apply to Main Repo (Critical Section)
        # NOTE: The branch already exists on remote with the commit from worktree.
        # We just need to checkout the branch so the user can review and create PR.
        # DO NOT soft reset - it would destroy the branch reference and cause push issues.
        yield "Applying changes to main workspace..."
        with repo_lock(repo_path):
             # Fetch the new branch
             subprocess.run(["git", "fetch", "origin", branch_name], cwd=repo_path, check=True)

             # Check for uncommitted changes and stash if needed before checkout
             status_res = subprocess.run(["git", "status", "--porcelain"], cwd=repo_path, capture_output=True, text=True)
             if status_res.stdout.strip():
                 logger.warning("Unstaged changes detected in main repo. Stashing...")
                 subprocess.run(["git", "stash", "save", "-u", f"Auto-stashed before applying {branch_name}"], cwd=repo_path, capture_output=True)

             # Checkout the branch (force create/update from FETCH_HEAD)
             # We just fetched origin <branch>, so FETCH_HEAD is the tip
             subprocess.run(["git", "checkout", "-B", branch_name, "FETCH_HEAD"], cwd=repo_path, check=True)

             # Set upstream tracking for this branch
             subprocess.run(["git", "branch", "--set-upstream-to", f"origin/{branch_name}"], cwd=repo_path, check=False, capture_output=True)

        # Return branch_name so frontend can use it for PR creation even if repo state changes
        yield (True, f"Fix applied! Changes are ready for review in {branch_name}", branch_name)

    except Exception as e:
        logger.error(f"Error in worktree fix: {e}", exc_info=True)
        yield (False, f"Error: {e}")
    finally:
        # 4. Cleanup process registration and worktree
        _unregister_process(job_id)
        if worktree_path:
            cleanup_worktree(repo_path, worktree_path)

def create_worktree(repo_path, branch_name):
    """Create a temporary worktree for a new branch."""
    import tempfile
    
    # Create a temp dir outside the repo
    # using mkdtemp ensures unique path
    worktree_path = tempfile.mkdtemp(prefix="codemedic_worktree_")
    
    logger.info(f"Creating worktree at {worktree_path} for branch {branch_name}")
    
    # Create branch and worktree
    # If branch exists, we might need -B or handle error, assuming new unique branch for now
    try:
        # Git handles worktree locking internally - no need for repo_lock here
        # This allows multiple users to create worktrees concurrently
        subprocess.run(
            ["git", "worktree", "add", "-b", branch_name, worktree_path, "master"],
            cwd=repo_path,
            check=True,
            capture_output=True,
            text=True
        )
        return worktree_path
    except subprocess.CalledProcessError as e:
        logger.error(f"Failed to create worktree: {e.stderr}")
        # Cleanup dir if git failed but dir was made
        if os.path.exists(worktree_path):
             try:
                 os.rmdir(worktree_path)
             except: 
                 pass
        raise e

def cleanup_worktree(repo_path, worktree_path):
    """Remove the worktree and cleanup directory."""
    logger.info(f"Cleaning up worktree {worktree_path}")
    
    # 1. git worktree remove
    # Git handles worktree locking internally - no need for repo_lock here
    try:
        subprocess.run(
            ["git", "worktree", "remove", "--force", worktree_path],
            cwd=repo_path,
            check=False, # Don't crash if already gone
            capture_output=True
        )
    except Exception as e:
        logger.warning(f"git worktree remove failed: {e}")

    # 2. rm -rf directory if still exists (git remove usually does this, but force to be sure)
    if os.path.exists(worktree_path):
        import shutil
        try:
             shutil.rmtree(worktree_path)
        except Exception as e:
             logger.warning(f"Failed to remove worktree dir: {e}")

def prepare_repo(repo_path):
    print(f"Preparing repo at {repo_path}...")
    logger.info(f"Preparing repo at {repo_path}")

    # Acquire lock for the ENTIRE git preparation sequence
    with repo_lock(repo_path):
        # 1. Clean up stale lock files
        logger.info("Cleaning up stale lock files...")
        try:
            for root, dirs, files in os.walk(os.path.join(repo_path, ".git")):
                for file in files:
                    if file.endswith('.lock'):
                        lock_file = os.path.join(root, file)
                        try:
                            os.remove(lock_file)
                            logger.info(f"✅ Removed stale lock file: {lock_file}")
                        except Exception as e:
                            logger.debug(f"Could not remove lock file {lock_file}: {e}")
        except Exception as e:
            logger.warning(f"Error while cleaning lock files: {e}")

        # 2. Clean up corrupt git refs
        logger.info("Checking for corrupt git references...")
        refs_dir = os.path.join(repo_path, ".git", "refs", "remotes", "origin")
        try:
            if os.path.exists(refs_dir):
                for item in os.listdir(refs_dir):
                    item_path = os.path.join(refs_dir, item)
                    if os.path.isfile(item_path) and not item.startswith('.'):
                        try:
                            os.remove(item_path)
                            logger.info(f"✅ Removed potentially corrupt ref file: {item_path}")
                        except Exception as e:
                            logger.debug(f"Could not remove ref file {item_path}: {e}")
        except Exception as e:
            logger.warning(f"Error while cleaning corrupt refs: {e}")

        try:
            # 3. Prune and Fetch
            logger.info("Syncing with remote...")
            subprocess.run(["git", "remote", "prune", "origin"], cwd=repo_path, capture_output=True, text=True)
            subprocess.run(["git", "fetch", "--all", "--prune"], cwd=repo_path, capture_output=True, text=True)

            # 4. Handle uncommitted changes
            logger.info("Checking for uncommitted changes...")
            status_res = subprocess.run(["git", "status", "--porcelain"], cwd=repo_path, capture_output=True, text=True)
            if status_res.stdout.strip():
                logger.warning("⚠️ Unstaged changes detected. Stashing them...")
                subprocess.run(["git", "stash", "save", "-u", "Auto-stashed by CodeMedic"], cwd=repo_path, capture_output=True, text=True)

            # 5. Checkout and Align Master
            # Instead of 'git pull' which can fail due to divergence, we use fetch + reset --hard
            logger.info("Aligning master with origin/master...")
            subprocess.run(["git", "checkout", "master"], cwd=repo_path, check=True, capture_output=True)
            # Force master to match origin/master exactly to avoid "divergent branches" errors
            subprocess.run(["git", "reset", "--hard", "origin/master"], cwd=repo_path, check=True, capture_output=True)
            
            # Clean up untracked files if any left
            subprocess.run(["git", "clean", "-fd"], cwd=repo_path, capture_output=True)

            logger.info("✅ Repository preparation complete.")
            return True, "Repository is ready and aligned with origin/master."

        except subprocess.CalledProcessError as e:
            err_msg = e.stderr.decode() if isinstance(e.stderr, bytes) else str(e.stderr)
            logger.error(f"❌ Git preparation failed: {err_msg}")
            return False, f"Git preparation failed: {err_msg}"
        except Exception as e:
            logger.error(f"❌ Unexpected error during repo prep: {e}")
            return False, f"Unexpected error: {e}"

def get_git_diff(repo_path):
    try:
        # Build pathspec exclusions for IDE files
        # Format: ':!pattern' excludes files matching pattern
        exclusions = [f":!{pattern}" for pattern in IDE_FILE_PATTERNS]
        # Also exclude files containing these patterns in path
        exclusions.extend([f":!**/{pattern}" for pattern in IDE_FILE_PATTERNS])
        exclusions.extend([f":!**/{pattern}/**" for pattern in IDE_FILE_PATTERNS])

        # Check if we're on a feature branch (fix/*)
        branch = get_current_branch(repo_path)

        if branch and branch.startswith("fix/"):
            # On a feature branch - show diff between origin/master and current HEAD
            # This shows what changes will be in the PR
            cmd = ["git", "diff", "origin/master...HEAD", "--"] + exclusions
            result = subprocess.run(
                cmd,
                cwd=repo_path, capture_output=True, text=True, check=True
            )
            return result.stdout
        else:
            # Not on a feature branch - show staged/unstaged changes
            cmd = ["git", "diff", "HEAD", "--"] + exclusions
            result = subprocess.run(cmd, cwd=repo_path, capture_output=True, text=True, check=True)
            return result.stdout
    except Exception as e:
        return f"Error getting diff: {e}"

def discard_changes(repo_path):
    try:
        subprocess.run(["git", "checkout", "."], cwd=repo_path, check=True)
        subprocess.run(["git", "clean", "-fd"], cwd=repo_path, check=True)
        return True, "Changes discarded."
    except Exception as e:
        return False, f"Error discarding changes: {e}"

def run_git_commands(repo_path, message):
    try:
        with repo_lock(repo_path):
            # sanitize message
            safe_message = message.split('\n')[0][:100] # Take first line, max 100 chars

            # Checkout new branch with random suffix to avoid collisions
            import time
            import random
            ts = int(time.time())
            rand_suffix = random.randint(1000, 9999)
            branch_name = f"fix/error-{ts}-{rand_suffix}"

            subprocess.run(["git", "checkout", "-b", branch_name], cwd=repo_path, check=False, capture_output=True)

            # Add changes
            subprocess.run(["git", "add", "."], cwd=repo_path, check=True, capture_output=True)

            # Unstage IDE files using centralized helper
            unstage_ide_files(repo_path)

            # Log what's actually staged after cleanup
            result = subprocess.run(["git", "diff", "--cached", "--name-only"], cwd=repo_path, capture_output=True, text=True)
            staged_files = result.stdout.strip()
            if staged_files:
                logger.info(f"Staged files for commit:\n{staged_files}")
            else:
                logger.warning("No files staged after IDE file exclusion!")

            # Check if we have anything to commit
            # git diff --cached --exit-code returns 1 if there are changes, 0 if clean
            diff_result = subprocess.run(["git", "diff", "--cached", "--quiet"], cwd=repo_path, capture_output=True)
            
            if diff_result.returncode == 0:
                return False, "OpenCode completed successfully, but NO file changes were detected to commit. It might have failed to find the code."
            
            # Commit
            # Use --no-verify to bypass failing pre-commit hooks
            commit_env = os.environ.copy()
            commit_env["GIT_COMMITTER_NAME"] = "CodeMedic Bot"
            commit_env["GIT_COMMITTER_EMAIL"] = "codemedic@automated.local"
            subprocess.run([
                "git", "commit", "--no-verify",
                "-m", safe_message,
                "--author", "CodeMedic Bot <codemedic@automated.local>"
            ], cwd=repo_path, check=True, capture_output=True, env=commit_env)
            
            return True, f"Success! Fix committed to branch: {branch_name}"
    except subprocess.CalledProcessError as e:
        error_details = e.stderr if isinstance(e.stderr, str) else (e.stderr.decode('utf-8') if e.stderr else str(e))
        return False, f"Git command failed:\n{error_details}"

def get_current_branch(repo_path):
    """Get the current git branch name."""
    try:
        result = subprocess.run(
            ["git", "rev-parse", "--abbrev-ref", "HEAD"],
            cwd=repo_path,
            capture_output=True,
            text=True,
            check=True
        )
        return result.stdout.strip()
    except subprocess.CalledProcessError:
        return None


def is_worktree_branch_ready(repo_path):
    """
    Check if we're on a worktree-created branch that's already pushed.
    Returns (is_ready, branch_name) tuple.

    A branch is "ready" if:
    1. It's a fix/* branch (created by worktree flow)
    2. It has an upstream tracking branch
    3. Local and remote are in sync (no commits to push)
    """
    branch = get_current_branch(repo_path)
    if not branch or not branch.startswith("fix/"):
        return False, branch

    # Check if branch has upstream and is in sync
    try:
        # Get the upstream branch
        result = subprocess.run(
            ["git", "rev-parse", "--abbrev-ref", f"{branch}@{{upstream}}"],
            cwd=repo_path,
            capture_output=True,
            text=True
        )
        if result.returncode != 0:
            # No upstream set
            return False, branch

        # Check if local is behind or ahead of remote
        result = subprocess.run(
            ["git", "rev-list", "--left-right", "--count", f"{branch}...origin/{branch}"],
            cwd=repo_path,
            capture_output=True,
            text=True
        )
        if result.returncode == 0:
            parts = result.stdout.strip().split()
            if len(parts) == 2:
                ahead, behind = int(parts[0]), int(parts[1])
                # Branch is ready if we're not ahead (nothing to push)
                if ahead == 0:
                    return True, branch

        return False, branch
    except Exception:
        return False, branch


def push_branch(repo_path):
    """Push the current branch to the remote origin."""
    try:
        # Pushing modifies remote state and potentially local tracking refs, safest to lock
        with repo_lock(repo_path):
            branch = get_current_branch(repo_path)
            if not branch:
                return False, "Could not determine current branch."

            # Push with upstream tracking
            result = subprocess.run(
            ["git", "push", "-u", "origin", branch],
            cwd=repo_path,
            capture_output=True,
            text=True
        )

        if result.returncode != 0:
            error_msg = result.stderr or result.stdout
            return False, f"Push failed:\n{error_msg}"

        return True, f"Successfully pushed branch '{branch}' to origin."
    except Exception as e:
        return False, f"Error pushing branch: {e}"

def create_pull_request(repo_path, title, body=None, branch_name=None):
    """
    Create a pull request using GitHub CLI (gh).

    Args:
        repo_path: Path to the git repository
        title: PR title
        body: PR body/description (optional)
        branch_name: Specific branch to create PR from (optional).
                     If provided, uses --head flag to create PR from this branch
                     regardless of current checkout state.
    """
    try:
        # Use provided branch_name or fall back to current branch
        if branch_name:
            branch = branch_name
            logger.info(f"Creating PR for explicitly specified branch: {branch}")
        else:
            branch = get_current_branch(repo_path)
            if not branch:
                return False, "Could not determine current branch.", None

        # Build gh pr create command
        # Use --head to specify the branch explicitly (works even if not checked out)
        cmd = ["gh", "pr", "create", "--title", title, "--base", "master", "--head", branch]

        if body:
            cmd.extend(["--body", body])
        else:
            cmd.extend(["--body", f"Automated fix for error:\n\n{title}"])

        result = subprocess.run(
            cmd,
            cwd=repo_path,
            capture_output=True,
            text=True
        )

        if result.returncode != 0:
            error_msg = result.stderr or result.stdout
            return False, f"PR creation failed:\n{error_msg}", None

        # The output typically contains the PR URL
        pr_url = result.stdout.strip()
        return True, f"Pull request created successfully!", pr_url
    except FileNotFoundError:
        return False, "GitHub CLI (gh) is not installed. Please install it with: brew install gh", None
    except Exception as e:
        return False, f"Error creating pull request: {e}", None

if __name__ == "__main__":
    def main():
        config = load_config()
        
        # Handle multi-repo config
        if isinstance(config, dict) and not config.get("repo_path"):
             # It's likely the new format: {"Repo": "Path", ...}
             # For CLI, we default to the first one for now, or could add an arg
             first_key = next(iter(config)) if config else None
             if first_key:
                 repo_path = config[first_key]
                 print(f"Using repository: {first_key} ({repo_path})")
             else:
                 repo_path = None
        else:
             # Legacy or single path fallback
             repo_path = config.get("repo_path")
        
        # Check for command line argument for log path
        if len(sys.argv) > 1:
            log_path = sys.argv[1]
        else:
            log_path = None
        
        if not repo_path:
             print("Error: Missing repo_path in config.")
             return

        if not log_path:
            print("Usage: python agent.py <log_file_path>")
            print("Error: No log file provided via arguments or config.")
            return

        # 1. Parse & Cluster Logs
        errors = parse_log_clusters(log_path)
        if not errors:
            print("No errors found in the log.")
            return
        
        # 2. Dashboard
        print(f"\n{'='*60}")
        print(f"{'ID':<5} | {'Count':<8} | {'Error Message'}")
        print(f"{'-'*60}")
        
        for idx, err in enumerate(errors):
            msg_display = (err['message'][:70] + '...') if len(err['message']) > 70 else err['message']
            print(f"[{idx+1:<3}] | {err['count']:<8} | {msg_display}")
        print(f"{'='*60}\n")
        
        selection = input("Select an error ID to fix (or 'q' to quit): ").strip()
        if selection.lower() == 'q':
            return
            
        try:
            sel_idx = int(selection) - 1
            if sel_idx < 0 or sel_idx >= len(errors):
                print("Invalid selection.")
                return
        except ValueError:
            print("Invalid input.")
            return
            
        selected_error = errors[sel_idx]
        print(f"\nSelected: {selected_error['message']}")
        
        # 3. Apply Fix via OpenCode
        print(f"Delegating fix to OpenCode...")
        success = False
        msg = ""
        
        for item in run_opencode_fix(repo_path, selected_error['trace']):
            if isinstance(item, tuple):
                 success, msg = item
            else:
                 print(f"[OpenCode] {item}")
        
        if success:
            print("Fix applied by OpenCode.")
            # 5. Git Ops
            git_success, git_msg = run_git_commands(repo_path, f"Fix: {selected_error['message']}")
            print(git_msg)
        else:
            print(f"Failed to apply fix: {msg}")

    main()
