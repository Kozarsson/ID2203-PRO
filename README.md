# Omnipaxos-kv
This is an example repo showcasing the use of the [Omnipaxos](https://omnipaxos.com) consensus library to create a simple distributed key-value store. The source can be used to build server and client binaries which communicate over TCP. The repo also contains a benchmarking example which delploys Omnipaxos servers and clients onto [GCP](https://cloud.google.com) instances and runs an experiment collecting client response latencies (see `benchmarks/README.md`).

# Prerequisites
 - [Rust](https://www.rust-lang.org/tools/install)
 - [Docker](https://www.docker.com/)

# How to run
The `build_scripts` directory contains various utilities for configuring and running OmniPaxos clients and servers. Also contains examples of TOML file configuration.
 - `run-local-client.sh` runs two clients in separate local processes. Configuration such as which server to connect to defined in TOML files.
 - `run-local-cluster.sh` runs a 3 server cluster in separate local processes.
 - `docker-compose.yml` docker compose for a 3 server cluster.
 - See `benchmarks/README.md` for benchmarking scripts 

# How to run and test api_shim.rs
- Run `bash run-local-cluster.sh` (ensure you're in the `build_scripts` directory)
- In another terminal, run `API_LISTEN_ADDR=127.0.0.1:7001 CONFIG_FILE=./client-1-config.toml cargo run --manifest-path ../Cargo.toml --bin api-shim`
- In a third terminal, test the endpoints via `curl -i "http://127.0.0.1:7001/health"`, `curl -i -X PUT "http://127.0.0.1:7001/kv/x" --data-binary "1"` or `curl -i "http://127.0.0.1:7001/kv/x"`
Optional reset before rerun
- `pkill -f '/target/debug/server' || true`
- `pkill -f '/target/debug/api-shim' || true`

## One-command smoke test script
From `build_scripts`, run:
- `./run-api-shim-smoke.sh`

Useful options:
- `API_PORT=7002 ./run-api-shim-smoke.sh` (use a different port)
- `KEEP_RUNNING=1 ./run-api-shim-smoke.sh` (do not auto-stop services)

# Jepsen linearizability testing

The `jepsen-omnipaxos/` directory contains a Clojure Jepsen test suite that verifies OmniPaxos maintains linearizability under network partitions and node crashes.

## Repository layout

```
omnipaxos-kv/              — Rust OmniPaxos KV server + HTTP shim (this repo)
jepsen-omnipaxos/          — Clojure Jepsen test suite
jepsen-main/               — Jepsen Docker environment (control node + DB nodes)
```

## Architecture

```
Jepsen control node
  worker 0  →  HTTP :7000  →  n1 api-shim  →  n1 OmniPaxos server  ─┐
  worker 1  →  HTTP :7000  →  n2 api-shim  →  n2 OmniPaxos server  ─┼─ consensus
  worker 2  →  HTTP :7000  →  n3 api-shim  →  n3 OmniPaxos server  ─┘
```

All reads are linearizable because they go through `ClientMessage::Append` (same consensus path as writes), assigning each read a specific position in the log.

## How to run Jepsen tests

```bash
# 1. Build Rust binaries
cargo build --release --bin server --bin api-shim

# 2. Start Jepsen Docker environment
cd jepsen-main/docker && bin/up --n 3

# 3. Copy binaries and test suite to control node
docker cp target/release/server jepsen-control:/root/server
docker cp target/release/api-shim jepsen-control:/root/api-shim
docker cp jepsen-omnipaxos jepsen-control:/root/jepsen-omnipaxos

# 4. Open control node shell
bin/console

# 5. Run the test (inside control node)
cd /root/jepsen-omnipaxos
lein run -- test --nodes n1,n2,n3 --time-limit 120 \
  --server-bin /root/server --shim-bin /root/api-shim

# 6. Copy results back — run from repo root (ID2203-PRO/)
docker cp jepsen-control:/root/jepsen-omnipaxos/store ./jepsen-results
```

## Test structure

| File | Purpose |
|------|---------|
| `core.clj` | Entry point — wires db, client, generator, checker, nemesis |
| `client.clj` | HTTP client translating ops to PUT/GET requests |
| `db.clj` | Deploys and manages binaries on nodes via SSH |
| `nemesis.clj` | Fault injection: network partitions + node crashes |