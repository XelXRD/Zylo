import { secretProblem } from "./secrets.js";
import { buildApp } from "./app.js";

// Fail fast on a bad secret: otherwise the first integration save fails with a confusing error.
const secretError = secretProblem();
if (secretError) {
  console.error(`\n  Zylo can't start: ${secretError}\n`);
  process.exit(1);
}

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
