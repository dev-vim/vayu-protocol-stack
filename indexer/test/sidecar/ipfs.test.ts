import { describe, it, expect, vi, beforeEach, afterEach } from "vitest";
import { IpfsFetchError } from "../../src/sidecar/ipfs.js";

// ── fetchBlob tests ───────────────────────────────────────────────────────────
// We spy on globalThis.fetch so no real network calls are made.

const MOCK_CID   = "QmTestCidAbcdef1234567890";
const MOCK_BLOB  = JSON.stringify({ epochId: 1 });

describe("fetchBlob", () => {
  beforeEach(() => {
    vi.stubGlobal("fetch", vi.fn());
  });

  afterEach(() => {
    vi.unstubAllGlobals();
  });

  it("returns the response body on a successful fetch", async () => {
    vi.mocked(fetch).mockResolvedValueOnce(
      new Response(MOCK_BLOB, { status: 200 }),
    );

    const { fetchBlob } = await import("../../src/sidecar/ipfs.js");
    const result = await fetchBlob(MOCK_CID);

    expect(result).toBe(MOCK_BLOB);
    expect(fetch).toHaveBeenCalledOnce();
  });

  it("throws IpfsFetchError after all retries fail with non-200", async () => {
    vi.mocked(fetch).mockResolvedValue(
      new Response("Not Found", { status: 404, statusText: "Not Found" }),
    );

    const { fetchBlob } = await import("../../src/sidecar/ipfs.js");
    await expect(fetchBlob(MOCK_CID)).rejects.toThrow(IpfsFetchError);
    expect(fetch).toHaveBeenCalledTimes(3);
  });

  it("throws IpfsFetchError after all retries fail with network error", async () => {
    vi.mocked(fetch).mockRejectedValue(new TypeError("network failure"));

    const { fetchBlob } = await import("../../src/sidecar/ipfs.js");
    await expect(fetchBlob(MOCK_CID)).rejects.toThrow(IpfsFetchError);
    expect(fetch).toHaveBeenCalledTimes(3);
  });

  it("succeeds on second attempt after an initial failure", async () => {
    vi.mocked(fetch)
      .mockRejectedValueOnce(new TypeError("transient error"))
      .mockResolvedValueOnce(new Response(MOCK_BLOB, { status: 200 }));

    const { fetchBlob } = await import("../../src/sidecar/ipfs.js");
    const result = await fetchBlob(MOCK_CID);

    expect(result).toBe(MOCK_BLOB);
    expect(fetch).toHaveBeenCalledTimes(2);
  });

  it("IpfsFetchError includes the CID and cause", async () => {
    vi.mocked(fetch).mockRejectedValue(new TypeError("connection refused"));

    const { fetchBlob } = await import("../../src/sidecar/ipfs.js");
    try {
      await fetchBlob(MOCK_CID);
      expect.fail("should have thrown");
    } catch (err) {
      expect(err).toBeInstanceOf(IpfsFetchError);
      expect((err as IpfsFetchError).cid).toBe(MOCK_CID);
      expect((err as IpfsFetchError).message).toContain(MOCK_CID);
    }
  });
});
