# Vayu Protocol Dashboard

Next.js 15 (App Router) read-only dashboard that queries the Ponder indexer's GraphQL
API and visualises on-chain activity for the Vayu DePIN air quality network.

## Stack

| Layer | Choice |
|---|---|
| Framework | Next.js 15 (App Router, server components) |
| Styling | Tailwind CSS v4 |
| Data | Ponder GraphQL (`/graphql`) — `epochss`, `reporterss`, `relayss` |
| Rendering | Server-side, `cache: "no-store"` — fresh data on every request |

---

## What it shows

| Section | Data |
|---|---|
| **Stat cards** | Latest epoch ID, epochs committed, reporter count, active relay count |
| **Recent Epochs** | Last 15 epochs — relay, active cells, readings, total reward, IPFS status badge, commit time |
| **Top Reporters** | Top 20 reporters by stake — stake, readings, rewards earned, claimed, last epoch, slashed status |

---

## Prerequisites

- Node.js 22+
- The Ponder indexer running (see [`indexer/README.md`](../indexer/README.md))

---

## Configuration

```bash
cp .env.local.example .env.local
```

| Variable | Default | Description |
|---|---|---|
| `PONDER_URL` | `http://localhost:42069` | Base URL of the running Ponder indexer. GraphQL is served at `PONDER_URL/graphql`. |

> **Note:** `PONDER_URL` is read server-side only — do not prefix it with `NEXT_PUBLIC_`.

---

## Local Development

```bash
npm install
npm run dev     # http://localhost:3000
```

If the indexer is not reachable the dashboard renders an amber warning banner rather
than crashing. Start the indexer first for data to appear:

```bash
# From indexer/
npm run dev     # Ponder GraphQL on http://localhost:42069
```

## Production build

```bash
npm run build
npm run start
```

---

## Port

Runs on **3000** by default (Next.js default). See the root port map in
[`relay/README.md`](../relay/README.md) for the full stack layout — no port overlaps
with the relay (8080), Ponder indexer (42069), or Kubo IPFS gateway.
