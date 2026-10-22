// Proxies /wallet-api/** to the wallet API (when the frontend is not behind a reverse proxy that does this)
export default defineEventHandler(async (event) => {
  const config = useRuntimeConfig();
  const path = getRouterParam(event, "path") || "";

  // Stay inside the wallet API: refuse "..", "." and backslashes, also when (double) percent-encoded
  let decoded = path;
  for (let i = 0; i < 3; i++) {
    const next = decodeURIComponent(decoded);
    if (next === decoded) break;
    decoded = next;
  }
  if (decoded.split(/[\\/]/).some((segment) => segment === ".." || segment === ".")) {
    throw createError({ statusCode: 400, statusMessage: "Invalid path" });
  }

  // Keep the query string (e.g. ?organizationId=… for super admins)
  const query = getRequestURL(event).search;
  return proxyRequest(event, `${config.walletApiInternal}/${path}${query}`);
});
