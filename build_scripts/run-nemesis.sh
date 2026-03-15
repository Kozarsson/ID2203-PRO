#!/bin/bash
# run-nemesis.sh
# Simple Nemesis for OmniPaxos cluster

# --- Configuration ---
cluster_size=3
sleep_partition=5    # seconds to isolate a node
sleep_crash=5        # seconds to stop a node
client_url="http://localhost:8000/kv"

# --- Functions ---
log() {
    echo "[$(date -u +"%Y-%m-%dT%H:%M:%SZ")] $1"
}

# Find Docker container IDs for the nodes
get_node_container() {
    docker ps --filter "name=server-$1" --format "{{.ID}}"
}

# Send PUT operation via curl
put_key() {
    key=$1
    value=$2
    curl -s -X PUT "$client_url/$key" -d "{\"value\":\"$value\"}"
}

# Send GET operation via curl
get_key() {
    key=$1
    curl -s -X GET "$client_url/$key"
}

# --- Start Nemesis ---
log "Starting Nemesis..."

# 1. Partition: isolate node1 from node2 & node3
log "Partitioning node1 from node2 & node3..."
NODE1=$(get_node_container 1)
NODE2=$(get_node_container 2)
NODE3=$(get_node_container 3)

# Disconnect node1 from bridge network for a short time
docker network disconnect bridge $NODE1
sleep $sleep_partition
docker network connect bridge $NODE1
log "Partition healed."

# 2. Crash & Restart: node2
log "Crashing node2..."
docker stop $NODE2
sleep $sleep_crash
docker start $NODE2
log "Node2 restarted."

# 3. Send some test operations
log "Sending PUT operations..."
put_key "x" "42"
put_key "y" "100"

log "Sending GET operations..."
get_key "x"
get_key "y"

log "Nemesis completed."