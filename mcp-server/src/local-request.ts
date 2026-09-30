/** Request headers the local-origin check reads (node's IncomingHttpHeaders fits). */
export interface RequestHeaders {
  host?: string;
  origin?: string;
}

/**
 * Whether a request comes from a local client of the HTTP transport. The server listens on 127.0.0.1
 * only, but a web page in the user's browser can still reach it (DNS rebinding, or a plain cross-site
 * POST), so the Host must name this loopback server and a browser Origin, when present, must be this
 * same server.
 */
export function isLocalRequest(headers: RequestHeaders, port: number): boolean {
  const hosts = ["127.0.0.1", "localhost", "[::1]"].map((host) => `${host}:${port}`);
  const host = headers.host?.toLowerCase();
  if (!host || !hosts.includes(host)) return false;
  const origin = headers.origin;
  return origin === undefined || hosts.some((allowed) => origin.toLowerCase() === `http://${allowed}`);
}
