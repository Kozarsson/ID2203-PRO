#!/bin/bash
# run-nemesis-local.sh
# Simple Nemesis for OmniPaxos cluster on MacOS (local processes)

# --- Configuration ---
sleep_crash=5          # seconds to stop a node
client_url="http://localhost:8000/kv"
log_file="./logs/nemesis.log"

# --- Functions ---
log() {
    echo "[$(date -u +"%Y-%m-%dT%H:%M:%SZ")] $1" | tee -a $log_file
}

# Send PUT via API-shim
put_key() {
    key=$1
    value=$2
    echo "$(date -u +"%T") PUT $key=$value" | tee -a $log_file
    curl -s -X PUT "$client_url/$key" -d "{\"value\":\"$value\"}" | tee -a $log_file
}

# Send GET via API-shim
get_key() {
    key=$1
    echo "$(date -u +"%T") GET $key" | tee -a $log_file
    curl -s -X GET "$client_url/$key" | tee -a $log_file
}

# --- Nemesis ---
log "Starting Nemesis (local process mode)..."

# Find PIDs for nodes
NODE1=$(ps aux | grep "target/debug/server" | grep -v grep | sed -n '1p' | awk '{print $2}')
NODE2=$(ps aux | grep "target/debug/server" | grep -v grep | sed -n '2p' | awk '{print $2}')
NODE3=$(ps aux | grep "target/debug/server" | grep -v grep | sed -n '3p' | awk '{print $2}')

log "Detected nodes: NODE1=$NODE1 NODE2=$NODE2 NODE3=$NODE3"

# --- Step 1: Crash/restart node2 ---
log "Crashing node2 (PID $NODE2)..."
kill -9 $NODE2
log "Node2 stopped. Waiting $sleep_crash seconds..."
sleep $sleep_crash

log "Restarting node2..."
RUST_LOG=info SERVER_CONFIG_FILE=build_scripts/server-2-config.toml CLUSTER_CONFIG_FILE=build_scripts/cluster-config.toml cargo run --bin server &
sleep 2
log "Node2 restarted."

# --- Step 2: Send PUT/GET under Nemesis ---
log "Sending test PUT operations..."
put_key "x" "42"
put_key "y" "100"

log "Sending test GET operations..."
get_key "x"
get_key "y"

log "Nemesis completed."