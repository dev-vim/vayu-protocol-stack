const IPFS_GATEWAY_URL =
  (process.env.IPFS_GATEWAY_URL ?? "http://localhost:8080/ipfs").replace(/\/$/, "");

const MAX_ATTEMPTS   = 3;
const BASE_DELAY_MS  = 1_000;
const FETCH_TIMEOUT_MS = 10_000;

export class IpfsFetchError extends Error {
  constructor(
    public readonly cid: string,
    message: string,
    public readonly cause?: unknown,
  ) {
    super(`IPFS fetch failed for CID ${cid}: ${message}`);
    this.name = "IpfsFetchError";
  }
}

/**
 * Fetches the raw text content of an IPFS blob by CID.
 * Retries up to MAX_ATTEMPTS times with exponential back-off.
 * Throws IpfsFetchError on permanent failure.
 */
export async function fetchBlob(cid: string): Promise<string> {
  const url = `${IPFS_GATEWAY_URL}/${cid}`;
  let lastError: unknown;

  for (let attempt = 1; attempt <= MAX_ATTEMPTS; attempt++) {
    try {
      const controller = new AbortController();
      const timer = setTimeout(() => controller.abort(), FETCH_TIMEOUT_MS);

      const response = await fetch(url, { signal: controller.signal });
      clearTimeout(timer);

      if (!response.ok) {
        throw new Error(`HTTP ${response.status} ${response.statusText}`);
      }

      return await response.text();
    } catch (err) {
      lastError = err;
      if (attempt < MAX_ATTEMPTS) {
        await sleep(BASE_DELAY_MS * 2 ** (attempt - 1));
      }
    }
  }

  throw new IpfsFetchError(cid, String(lastError), lastError);
}

function sleep(ms: number): Promise<void> {
  return new Promise((resolve) => setTimeout(resolve, ms));
}
