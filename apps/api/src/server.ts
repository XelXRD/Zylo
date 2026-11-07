import { buildApp } from "./app.js";

const databaseUrl = process.env.DATABASE_URL;
if (!databaseUrl) throw new Error("DATABASE_URL is not set");

const app = buildApp({
  databaseUrl,
  secureCookies: process.env.COOKIE_SECURE === "true",
  logger: true,
  // Only for testing against a mock; normally unset.
  bases: { tmdb: process.env.TMDB_BASE_URL, tvdb: process.env.TVDB_BASE_URL },
});
await app.listen({ host: "0.0.0.0", port: Number(process.env.PORT ?? 4000) });

for (const sig of ["SIGINT", "SIGTERM"]) process.on(sig, () => app.close().then(() => process.exit(0)));
