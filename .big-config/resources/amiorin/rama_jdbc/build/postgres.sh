#!/usr/bin/env nix-shell
#!nix-shell -i bash

# --- Configuration ---
# Define a temporary directory for PostgreSQL data.
# This ensures a clean slate each time the script runs.
PG_DATA_DIR=$(mktemp -d -t pg_data_XXXXX)
# Define the port for PostgreSQL to listen on.
# Choose a port that is unlikely to be in use.
PG_PORT=${1:-5432}
# Define the PostgreSQL user for your Clj application.
PG_USER={{postgres-user}}
# Define the PostgreSQL database name for your Clj application.
PG_DB={{postgres-dbname}}
# Path to the PostgreSQL executable. Adjust if 'postgres' is not in your PATH.
# Example: PG_BIN="/usr/local/pgsql/bin/postgres"
PG_BIN="postgres"
# Path to the initdb executable. Adjust if 'initdb' is not in your PATH.
INITDB_BIN="initdb"
# Path to the createuser executable. Adjust if 'createuser' is not in your PATH.
CREATEUSER_BIN="createuser"
# Path to the createdb executable. Adjust if 'createdb' is not in your PATH.
CREATEDB_BIN="createdb"
# Path to sql-migrate executable. Adjust if 'sql-migrate' is not in your PATH.
SQL_MIGRATE_BIN="sql-migrate"
# Path for coordination
WAIT_FOR_PATH=/tmp/port-$PG_PORT

# Kill previous PostgreSQL if running
echo "--- Kill previous PostgreSQL if running ---"
lsof -t -i :$PG_PORT | xargs -r kill

set -eo pipefail

# --- Functions ---

# Function to clean up PostgreSQL data directory and kill the process.
cleanup() {
    if [[ $SIGINT_CAUGHT -eq 1 ]]; then
        return 0
    fi
    echo "--- Cleaning up ---"
    # Check if PG_PID is set and the process is still running.
    if [ -n "$PG_PID" ] && ps -p "$PG_PID" > /dev/null; then
        echo "Killing PostgreSQL process (PID: $PG_PID)..."
        kill "$PG_PID"
        # Wait a bit for the process to terminate.
        wait "$PG_PID" 2>/dev/null
    fi
    # Remove the temporary data directory.
    if [ -d "$PG_DATA_DIR" ]; then
        echo "Removing PostgreSQL data directory: $PG_DATA_DIR"
        rm -rf "$PG_DATA_DIR"
    fi
    # Remove the path for coordination.
    if [ -d "$WAIT_FOR_PATH" ]; then
        echo "Removing path for coordination: $WAIT_FOR_PATH"
        rm -rf "$WAIT_FOR_PATH"
    fi
    echo "Cleanup complete."
    SIGINT_CAUGHT=1
}

# --- Main Script Logic ---

echo "--- Starting PostgreSQL ---"

# Set up a trap to call the cleanup function on script exit (success or failure).
# This ensures PostgreSQL is killed and data is removed even if the script is interrupted.
trap cleanup EXIT INT TERM

# 1. Initialize PostgreSQL Data Directory
echo "1. Initializing PostgreSQL data directory: $PG_DATA_DIR"
if ! "$INITDB_BIN" -D "$PG_DATA_DIR" > /dev/null 2>&1; then
    echo "Error: Failed to initialize PostgreSQL data directory."
    exit 1
fi
echo "PostgreSQL data directory initialized."

# 2. Start PostgreSQL Server in the background
echo "2. Starting PostgreSQL server on port $PG_PORT..."
# Run postgres directly, redirecting stdout/stderr to /dev/null to keep terminal clean.
# The '&' puts it in the background.
"$PG_BIN" -D "$PG_DATA_DIR" -p "$PG_PORT" > /dev/null 2>&1 &
# Capture the Process ID (PID) of the background PostgreSQL process.
PG_PID=$!
echo "PostgreSQL started with PID: $PG_PID"

# 3. Wait for PostgreSQL to be ready
echo "3. Waiting for PostgreSQL to start up (up to 5 seconds)..."
bb -e "(babashka.wait/wait-for-port \"localhost\" $PG_PORT {:timeout 5000})"

# Verify if PostgreSQL process is still running.
if ! ps -p "$PG_PID" > /dev/null; then
    echo "Error: PostgreSQL process (PID: $PG_PID) did not start or crashed."
    exit 1
fi
echo "PostgreSQL appears to be running."

# 4. Create User and Database for Clj Application
echo "4. Creating PostgreSQL user '$PG_USER' and database '$PG_DB'..."
# Create user (using 'postgres' as the superuser for this operation).
# We use --no-password for simplicity. For production, consider handling passwords securely.
if ! "$CREATEUSER_BIN" -h localhost -p "$PG_PORT" -U "$USER" --no-password "$PG_USER" > /dev/null 2>&1; then
    echo "Error: User '$PG_USER' might already exist or creation failed."
    exit 1
fi

# Create database (owned by the new user).
if ! "$CREATEDB_BIN" -h localhost -p "$PG_PORT" -U "$USER" -O "$PG_USER" "$PG_DB" > /dev/null 2>&1; then
    echo "Error: Failed to create database '$PG_DB'. It might already exist or permissions are wrong."
    exit 1
fi
echo "User '$PG_USER' and database '$PG_DB' created."

# 5. Ready
if ! touch $WAIT_FOR_PATH > /dev/null 2>&1; then
    echo "Error: Failed to create database '$PG_DB'. It might already exist or permissions are wrong."
    exit 1
fi

# 6. Wait for SIGINT
echo "6. Wait for SIGINT"
while true; do
    if [[ $SIGINT_CAUGHT -eq 1 ]]; then
        echo "Exiting loop due to SIGINT."
        break
    fi
    sleep 0.1 # Sleep for a short period
done

echo "--- Script Finished ---"
