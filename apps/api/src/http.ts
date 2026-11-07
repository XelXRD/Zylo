// Small fetch wrapper shared by integration adapters: timeout, JSON, polite retry on 429/5xx.
export class UpstreamError extends Error {
  constructor(public service: string, public status: number, message: string) {
    super(message);
  }
}

const sleep = (ms: number) => new Promise((r) => setTimeout(r, ms));

export async function fetchJson<T>(service: string, url: string, init: RequestInit = {}, attempt = 1): Promise<T> {
  let res: Response;
  try {
    res = await fetch(url, { ...init, signal: AbortSignal.timeout(10_000), headers: { accept: "application/json", ...init.headers } });
  } catch (e) {
    if (attempt < 2) return fetchJson(service, url, init, attempt + 1);
    throw new UpstreamError(service, 0, `${service} didn't respond (${(e as Error).name === "TimeoutError" ? "timed out" : "network error"}).`);
  }
  if ((res.status === 429 || res.status >= 500) && attempt < 3) {
    const retryAfter = Number(res.headers.get("retry-after"));
    await sleep(Number.isFinite(retryAfter) && retryAfter > 0 ? Math.min(retryAfter, 10) * 1000 : 500 * attempt);
    return fetchJson(service, url, init, attempt + 1);
  }
  if (!res.ok) {
    const body = (await res.json().catch(() => ({}))) as { status_message?: string; message?: string };
    throw new UpstreamError(service, res.status, body.status_message ?? body.message ?? `${service} returned ${res.status}.`);
  }
  return res.json() as Promise<T>;
}
