#!/usr/bin/env bash
set -euo pipefail

# ── Postgres ──────────────────────────────────────────────────────────────────
until pg_isready -d "$DATABASE_URL" -q; do
  echo "waiting for postgres..."
  sleep 2
done

# ── Anvil (only when targeting the local chain) ───────────────────────────────
if [ "${PONDER_CHAIN:-anvil}" = "anvil" ]; then
  ANVIL_URL="${PONDER_RPC_URL_31337:-http://localhost:8545}"
  until curl -sf -X POST "$ANVIL_URL" \
      -H 'Content-Type: application/json' \
      -d '{"jsonrpc":"2.0","method":"eth_chainId","id":1}' \
      >/dev/null 2>&1; do
    echo "waiting for anvil..."
    sleep 2
  done
fi
