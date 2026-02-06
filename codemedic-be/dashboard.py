import streamlit as st
import agent
import pandas as pd
import os

st.set_page_config(page_title="CodeMedic", layout="wide")

st.title("🩺 CodeMedic Dashboard")

# --- Sidebar: Configuration ---
# --- Sidebar: Configuration ---
st.sidebar.header("Configuration")

# Available Models
available_models = agent.get_available_models()
default_model_index = 0
desired_default = "opencode/glm-4.7-free"

if desired_default in available_models:
    default_model_index = available_models.index(desired_default)
    
selected_model = st.sidebar.selectbox("Select AI Model", available_models, index=default_model_index)

# Load defaults from config file if available
repo_config = agent.load_config()
repo_names = list(repo_config.keys()) if isinstance(repo_config, dict) else []

log_path = st.sidebar.text_input("Log File Path")

repo_name = st.sidebar.selectbox("Repository", repo_names) if repo_names else None
repo_path = repo_config.get(repo_name) if repo_name else None

if not repo_path and not repo_names:
     # Fallback for manual entry if config is empty or invalid
     repo_path = st.sidebar.text_input("Repository Path (Manual)")

if st.sidebar.button("Reload Config"):
    st.experimental_rerun()

# --- Main Logic ---

if not log_path or not repo_path:
    st.warning("Please configure Log File Path and Repository Path in the sidebar.")
    st.stop()

# 1. Analyze Logs
st.header("1. Log Analysis")

if "errors" not in st.session_state:
    st.session_state.errors = []

if st.button("Analyze Logs"):
    with st.spinner("Parsing logs..."):
        errors = agent.parse_log_clusters(log_path)
        st.session_state.errors = errors
        if not errors:
            st.info("No errors found in the log.")
        else:
            st.success(f"Found {len(errors)} unique error clusters.")

if st.session_state.errors:
    # Convert to DataFrame for display
    df = pd.DataFrame(st.session_state.errors)
    # Reorder columns
    df = df[["count", "message"]]
    
    st.subheader("Error Clusters")
    
    # Interactive Table
    selected_indices = st.dataframe(
        df,
        use_container_width=True,
        on_select="rerun", # Requires Streamlit 1.35+, fallback handled if older
        selection_mode="single-row"
    )
    
    # Handle selection (Streamlit version dependent, simplified fallback)
    selected_row_idx = None
    if hasattr(selected_indices, "selection") and selected_indices.selection.rows:
         selected_row_idx = selected_indices.selection.rows[0]
    
    # --- Detail View & Fix ---
    st.header("2. Error Details & Fix")
    
    # Fallback selection UI if dataframe interactive selection isn't working/available
    # or just to be explicit
    error_options = [f"[{e['count']}] {e['message'][:80]}..." for e in st.session_state.errors]
    selected_option = st.selectbox("Select an Error to Fix:", error_options, index=selected_row_idx if selected_row_idx is not None else 0)
    
    if selected_option:
        # Find index in original list
        idx = error_options.index(selected_option)
        selected_error = st.session_state.errors[idx]
        
        st.markdown(f"**Full Error Context:**")
        st.code(selected_error['trace'], language="text")
        
        st.subheader("Automated Fix")
        st.info("The agent will delegate the fix to OpenCode AI.")
        
        if "fix_applied" not in st.session_state:
            st.session_state.fix_applied = False
            
        # Apply Fix Button (Only if not already applied)
        if not st.session_state.fix_applied:
            if st.button("Auto-Fix with OpenCode", type="primary"):
                
                with st.status("Running Agent...", expanded=True) as status:
                    # 1. Prepare Repo
                    st.write("Syncing repository (checkout master & pull)...")
                    prep_success, prep_msg = agent.prepare_repo(repo_path)
                    if not prep_success:
                        status.update(label="Repo Sync Failed", state="error")
                        st.error(prep_msg)
                        st.stop()
                    
                    st.write("✅ Repo synced.")
                    
                    # 2. OpenCode Analysis & Fix
                    st.write(f"Analyzing and fixing error via OpenCode...")
                    st.write(f"🧠 Using Model: **{selected_model}**")
                    
                    log_container = st.empty()
                    logs = []
                    success = False
                    msg = ""
                    
                    for item in agent.run_opencode_fix(repo_path, selected_error['trace'], model=selected_model):
                        if isinstance(item, tuple):
                            success, msg = item
                        else:
                            logs.append(item)
                            if "jdtls" in item and "lsp.client" in item:
                                st.info("ℹ️ Initializing Java Language Server (JDTLS). This index process effectively 'reads' your codebase and can take a minute for large projects.")
                            log_container.code("\n".join(logs), language="text")
                    
                    if success:
                        status.update(label="Fix Generated!", state="complete", expanded=False)
                        st.success("OpenCode has finished. Please review changes below.")
                        st.session_state.fix_applied = True
                        st.session_state.fix_message = f"Fix: {selected_error['message']}"
                        st.rerun()
                    else:
                        status.update(label="Fix Failed", state="error")
                        st.error(msg)
        
        # Review Phase
        if st.session_state.fix_applied:
            st.header("Review Changes")
            st.info("Review the changes made by OpenCode before committing.")
            
            # Show Diff
            diff = agent.get_git_diff(repo_path)
            if not diff.strip():
                st.warning("No changes detected in the repository.")
            else:
                st.code(diff, language="diff")
            
            col1, col2 = st.columns(2)
            with col1:
                if st.button("✅ Approve & Commit", type="primary"):
                    st.write("Committing changes...")
                    git_success, git_msg = agent.run_git_commands(repo_path, st.session_state.fix_message)
                    
                    if git_success:
                        st.success(git_msg)
                        # Reset state
                        st.session_state.fix_applied = False
                        st.balloons()
                    else:
                        st.error(git_msg)
            
            with col2:
                if st.button("❌ Discard Changes"):
                     st.write("Discarding changes...")
                     disc_success, disc_msg = agent.discard_changes(repo_path)
                     if disc_success:
                         st.warning("Changes discarded.")
                         st.session_state.fix_applied = False
                         st.rerun()
                     else:
                         st.error(disc_msg)
