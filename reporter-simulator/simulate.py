#!/usr/bin/env python3
"""
Vayu Reporter Simulator

Generates EIP-712 signed AQI readings and submits them to the relay.
All private keys are Anvil's deterministic test accounts — safe for local dev only.

Quick start (docker compose stack must be running):
    python simulate.py

Submit 3 rounds spread across epochs (relay epochs are 60 s in compose):
    python simulate.py --rounds 3 --interval 61

Generate signed fixture payloads for Spring integration tests (no submission):
    python simulate.py --output-fixtures signed_readings.json --dry-run

Note: the relay enforces a per-reporter rate limit (default 300 s). When running
multiple rounds, use --interval >= 300 or expect 429 responses on later rounds.
"""

from __future__ import annotations

import argparse
import json
import random
import sys
import time
from typing import Any

import requests
from eth_account import Account

# ── Reporters (Anvil test accounts 1-5; account 0 is the relay/deployer) ──────

REPORTERS: list[dict[str, str]] = [
    {
        "private_key": "0x59c6995e998f97a5a0044966f0945389dc9e86dae88c7a8412f4603b6b78690d",
        "address": "0x70997970C51812dc3A010C7d01b50e0d17dc79C8",
        "label": "reporter-1",
    },
    {
        "private_key": "0x5de4111afa1a4b94908f83103eb1f1706367c2e68ca870fc3fb9a804cdab365a",
        "address": "0x3C44CdDdB6a900fa2b585dd299e03d12FA4293BC",
        "label": "reporter-2",
    },
    {
        "private_key": "0x7c852118294e51e653712a81e05800f419141751be58f605c371e15141b007a6",
        "address": "0x90F79bf6EB2c4f870365E785982E1f101E93b906",
        "label": "reporter-3",
    },
    {
        "private_key": "0x47e179ec197488593b187f80a00eb0da91f1b9d0b13f8733639f19c30a34926a",
        "address": "0x15d34AAf54267DB7D7c367839AAf71A00a2C6A65",
        "label": "reporter-4",
    },
    {
        "private_key": "0x8b3a350cf5c34c9194ca85829a2df0ec3153be0318b5e2d3348e872092edffba",
        "address": "0x9965507D1a55bcC2695C58ba16FB37d819B0A4dc",
        "label": "reporter-5",
    },
]

# Valid H3 resolution-8 cells (Bay Area cluster).
# Resolution is encoded in bits 55-52 of the uint64; all entries have those bits = 1000 (= 8).
H3_CELLS: list[str] = [
    "0x0882830a1fffffff",
    "0x0882830a3fffffff",
    "0x0882830a5fffffff",
    "0x0882830a7fffffff",
    "0x0882830a9fffffff",
    "0x0882830abfffffff",
]

# EIP-712 type definition — must mirror Eip712SignatureVerifier.java exactly.
# Field order and types must match the READING_TYPEHASH string in the Java verifier.
READING_TYPES: dict[str, list[dict[str, str]]] = {
    "AQIReading": [
        {"name": "reporter",  "type": "address"},
        {"name": "h3Index",   "type": "uint64"},
        {"name": "epochId",   "type": "uint32"},
        {"name": "timestamp", "type": "uint32"},
        {"name": "aqi",       "type": "uint16"},
        {"name": "pm25",      "type": "uint16"},
        {"name": "pm10",      "type": "uint16"},
        {"name": "o3",        "type": "uint16"},
        {"name": "no2",       "type": "uint16"},
        {"name": "so2",       "type": "uint16"},
        {"name": "co",        "type": "uint16"},
    ]
}


# ── EIP-712 signing ───────────────────────────────────────────────────────────

def make_domain(chain_id: int, verifying_contract: str) -> dict[str, Any]:
    return {
        "name": "VayuProtocol",
        "version": "1",
        "chainId": chain_id,
        "verifyingContract": verifying_contract,
    }


def sign_reading(
    private_key: str,
    reporter: str,
    h3_hex: str,
    epoch_id: int,
    timestamp: int,
    aqi: int,
    pm25: int,
    domain: dict[str, Any],
) -> str:
    """Return a 0x-prefixed 65-byte EIP-712 signature."""
    message_data: dict[str, Any] = {
        "reporter":  reporter,
        "h3Index":   int(h3_hex, 16),   # relay decodes h3Index as uint64 integer
        "epochId":   epoch_id,
        "timestamp": timestamp,
        "aqi":       aqi,
        "pm25":      pm25,
        "pm10":      0,
        "o3":        0,
        "no2":       0,
        "so2":       0,
        "co":        0,
    }
    signed = Account.sign_typed_data(private_key, domain, READING_TYPES, message_data)
    return "0x" + signed.signature.hex()


# ── Payload construction and submission ───────────────────────────────────────

def build_payload(
    reporter: str,
    h3_hex: str,
    epoch_id: int,
    timestamp: int,
    aqi: int,
    pm25: int,
    signature: str,
) -> dict[str, Any]:
    return {
        "reporter":  reporter,
        "h3Index":   h3_hex,
        "epochId":   epoch_id,
        "timestamp": timestamp,
        "aqi":       aqi,
        "pm25":      pm25,
        "signature": signature,
    }


def submit_reading(relay_url: str, payload: dict[str, Any]) -> tuple[int, dict[str, Any]]:
    url = f"{relay_url}/v1/readings"
    try:
        resp = requests.post(url, json=payload, timeout=10)
    except requests.exceptions.ConnectionError:
        print(f"\nERROR: Cannot connect to relay at {relay_url}", file=sys.stderr)
        print("       Is the relay running? Try: docker compose up relay", file=sys.stderr)
        sys.exit(1)
    except requests.exceptions.Timeout:
        print(f"\nERROR: Request to relay timed out ({relay_url})", file=sys.stderr)
        sys.exit(1)
    except requests.exceptions.RequestException as exc:
        print(f"\nERROR: Unexpected network error: {exc}", file=sys.stderr)
        sys.exit(1)
    try:
        body: dict[str, Any] = resp.json()
    except Exception:
        body = {"raw": resp.text}
    return resp.status_code, body


def tamper_signature(signature: str) -> str:
    """Flip the last byte of a valid signature to produce an invalid one."""
    raw = bytes.fromhex(signature.removeprefix("0x"))
    flipped = raw[:-1] + bytes([(raw[-1] ^ 0xFF)])
    return "0x" + flipped.hex()


# ── Round execution ───────────────────────────────────────────────────────────

def run_round(
    reporters: list[dict[str, str]],
    cells: list[str],
    domain: dict[str, Any],
    epoch_duration: int,
    relay_url: str,
    dry_run: bool,
    tamper: bool = False,
) -> list[dict[str, Any]]:
    """Sign and optionally submit one reading per (reporter, cell) pair."""
    now = int(time.time())
    epoch_id = now // epoch_duration
    payloads: list[dict[str, Any]] = []

    for reporter in reporters:
        for h3 in cells:
            aqi  = random.randint(10, 150)
            pm25 = random.randint(5, 80)

            signature = sign_reading(
                reporter["private_key"],
                reporter["address"],
                h3,
                epoch_id,
                now,
                aqi,
                pm25,
                domain,
            )
            if tamper:
                signature = tamper_signature(signature)
            payload = build_payload(reporter["address"], h3, epoch_id, now, aqi, pm25, signature)
            payloads.append(payload)

            if dry_run:
                print(f"  [dry-run] {reporter['label']} @ {h3}  epoch={epoch_id}  aqi={aqi}  pm25={pm25}")
                continue

            status, body = submit_reading(relay_url, payload)
            if status == 200:
                result = f"✓ accepted  (epochId={body.get('epochId', '?')})"
                if tamper:
                    result = f"✗ UNEXPECTED ACCEPT — relay should have rejected tampered signature!"
            elif status == 429:
                result = "⚠ 429 rate-limited"
            elif status == 400 and tamper:
                result = "✓ 400 rejected (tampered signature correctly refused)"
            else:
                result = f"✗ {status}  {body}"

            print(f"  {reporter['label']} @ {h3}  aqi={aqi}  → {result}")

    return payloads


# ── CLI ───────────────────────────────────────────────────────────────────────

def main() -> None:
    parser = argparse.ArgumentParser(
        description="Submit EIP-712 signed AQI readings to the Vayu relay.",
        formatter_class=argparse.ArgumentDefaultsHelpFormatter,
    )
    parser.add_argument(
        "--relay-url",
        default="http://localhost:8080",
        help="Relay base URL",
    )
    parser.add_argument(
        "--chain-id",
        type=int,
        default=31337,
        help="EIP-712 chain ID (matches relay RELAY_SECURITY_EIP712_CHAIN_ID)",
    )
    parser.add_argument(
        "--verifying-contract",
        default="0x9fE46736679d2D9a65F0992F2272dE9f3c7fa6e0",
        help="EIP-712 verifying contract — deterministic VayuEpochSettlement address on local Anvil",
    )
    parser.add_argument(
        "--epoch-duration",
        type=int,
        default=60,
        help="Epoch duration in seconds (must match relay RELAY_EPOCH_DURATION_SECONDS)",
    )
    parser.add_argument(
        "--reporters",
        type=int,
        default=3,
        metavar="N",
        help=f"Number of reporters to simulate (1-{len(REPORTERS)})",
    )
    parser.add_argument(
        "--cells",
        type=int,
        default=2,
        metavar="N",
        help=f"H3 cells per reporter per round (1-{len(H3_CELLS)})",
    )
    parser.add_argument(
        "--rounds",
        type=int,
        default=1,
        help="Number of submission rounds",
    )
    parser.add_argument(
        "--interval",
        type=int,
        default=30,
        metavar="SECONDS",
        help="Pause between rounds (seconds). Local dev default matches "
             "RELAY_VALIDATION_RATE_LIMIT_WINDOW_SECONDS=30 (2 submissions per 60 s epoch).",
    )
    parser.add_argument(
        "--rate-limit-window",
        type=int,
        default=300,
        metavar="SECONDS",
        help="Assumed relay rate-limit window (RELAY_VALIDATION_RATE_LIMIT_WINDOW_SECONDS). "
             "Used only to emit a warning when --interval is shorter. "
             "Production default is 300 s; local compose uses 30 s.",
    )
    parser.add_argument(
        "--tamper",
        action="store_true",
        help="Flip the last byte of every signature before submitting. "
             "Expects 400 responses when signature verification is enabled.",
    )
    parser.add_argument(
        "--dry-run",
        action="store_true",
        help="Sign readings but do not submit them to the relay",
    )
    parser.add_argument(
        "--output-fixtures",
        metavar="FILE",
        help="Write all signed payloads to a JSON file (implies --dry-run). "
             "Useful for feeding into Spring sig-enabled integration tests.",
    )

    args = parser.parse_args()

    if args.epoch_duration < 1:
        parser.error("--epoch-duration must be >= 1")

    n_reporters = max(1, min(args.reporters, len(REPORTERS)))
    n_cells     = max(1, min(args.cells, len(H3_CELLS)))
    reporters   = REPORTERS[:n_reporters]
    cells       = H3_CELLS[:n_cells]
    dry_run     = args.dry_run or bool(args.output_fixtures)

    domain = make_domain(args.chain_id, args.verifying_contract)

    print("Vayu Reporter Simulator")
    print(f"  relay:              {args.relay_url}")
    print(f"  chain-id:           {args.chain_id}")
    print(f"  verifying-contract: {args.verifying_contract}")
    print(f"  epoch-duration:     {args.epoch_duration}s")
    print(f"  interval:           {args.interval}s")
    print(f"  rounds:             {args.rounds}")
    print(f"  reporters:          {n_reporters}  ({', '.join(r['label'] for r in reporters)})")
    print(f"  cells:              {n_cells}")
    if dry_run:
        print(f"  mode:               dry-run (signatures computed, not submitted)")
    print()

    if args.rounds > 1 and 0 < args.interval < args.rate_limit_window and not dry_run:
        print(
            f"⚠  Warning: --interval ({args.interval}s) is less than the assumed rate-limit "
            f"window ({args.rate_limit_window}s).\n"
            "   Rounds after the first may receive 429 responses from the relay.\n"
            "   Set --rate-limit-window to match RELAY_VALIDATION_RATE_LIMIT_WINDOW_SECONDS "
            "if it differs from the default.",
            file=sys.stderr,
        )

    all_payloads: list[dict[str, Any]] = []

    for i in range(1, args.rounds + 1):
        if args.rounds > 1:
            print(f"Round {i}/{args.rounds}")
        payloads = run_round(reporters, cells, domain, args.epoch_duration, args.relay_url, dry_run, args.tamper)
        all_payloads.extend(payloads)

        if i < args.rounds and args.interval > 0:
            print(f"  ↳ waiting {args.interval}s before next round...")
            time.sleep(args.interval)

    if args.output_fixtures:
        with open(args.output_fixtures, "w") as fh:
            json.dump(all_payloads, fh, indent=2)
        print(f"\n✓ {len(all_payloads)} signed payloads written to {args.output_fixtures}")
    elif dry_run:
        print(f"\n{len(all_payloads)} payloads signed (not submitted)")


if __name__ == "__main__":
    main()
