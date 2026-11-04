import { afterEach, describe, expect, it, vi } from "vitest";

import { request, setAuthEnabled } from "./api";

function stubFetch() {
  const fetchMock = vi.fn(
    async (_input: RequestInfo | URL, _init?: RequestInit) =>
      new Response(JSON.stringify({ ok: true }), {
        status: 200,
        headers: { "Content-Type": "application/json" },
      }),
  );
  vi.stubGlobal("fetch", fetchMock);
  return fetchMock;
}

function headersOf(fetchMock: ReturnType<typeof stubFetch>) {
  const init = fetchMock.mock.calls[0]?.[1];
  return (init?.headers ?? {}) as Record<string, string>;
}

describe("browser request CSRF handling", () => {
  afterEach(() => {
    setAuthEnabled(true);
    vi.unstubAllGlobals();
    document.cookie = "tf_csrf=; expires=Thu, 01 Jan 1970 00:00:00 GMT";
  });

  it("does not require a CSRF token when administrator authentication is disabled", async () => {
    const fetchMock = stubFetch();
    setAuthEnabled(false);

    await expect(
      request("/settings/create", { method: "POST", body: "{}" }),
    ).resolves.toEqual({ ok: true });

    expect(fetchMock).toHaveBeenCalledTimes(1);
    expect(headersOf(fetchMock)["X-CSRF-Token"]).toBeUndefined();
  });

  it("attaches the CSRF cookie value for writes when authentication is enabled", async () => {
    const fetchMock = stubFetch();
    setAuthEnabled(true);
    document.cookie = "tf_csrf=fixture-csrf";

    await expect(
      request("/settings/create", { method: "POST", body: "{}" }),
    ).resolves.toEqual({ ok: true });

    expect(headersOf(fetchMock)["X-CSRF-Token"]).toBe("fixture-csrf");
  });

  it("still blocks writes when authentication is enabled but the CSRF cookie is missing", async () => {
    const fetchMock = stubFetch();
    setAuthEnabled(true);

    await expect(
      request("/settings/create", { method: "POST", body: "{}" }),
    ).rejects.toThrow("CSRF");
    expect(fetchMock).not.toHaveBeenCalled();
  });
});
