import { secretProblem } from "./secrets.js";
import { Queue } from "bullmq";
import { Redis } from "ioredis";
import { buildApp } from "./app.js";

// Fail fast on a bad secret: otherwise the first integration save fails with a confusing error.
const secretError = secretProblem();
if (secretError) {
  console.error(`\n  Zylo can't start: ${secretError}\n`);
  process.exit(1);
}

const databaseUrl = process.env.DATABASE_URL;
if (!databaseUrl) throw new Error("DATABASE_URL is not set");

// Long jobs (Trakt syncs) go to zylo-worker through Redis.
const queue = process.env.REDIS_URL ? new Queue("zylo", { connection: new Redis(process.env.REDIS_URL, { maxRetriesPerRequest: null }) }) : null;

const app = buildApp({
  enqueueTraktSync: queue
    ? async (profileId) => {
        // One pending sync per profile: a second click while queued is a no-op.
        await queue.add("trakt-sync", { profileId }, { jobId: `trakt-sync-${profileId}`, removeOnComplete: true, removeOnFail: true });
      }
    : undefined,
  databaseUrl,
  secureCookies: process.env.COOKIE_SECURE === "true",
  logger: true,
  // Only for testing against a mock; normally unset.
  bases: { tmdb: process.env.TMDB_BASE_URL, tvdb: process.env.TVDB_BASE_URL, trakt: process.env.TRAKT_BASE_URL },
  animeBases: { anilist: process.env.ANILIST_BASE_URL, anilistAuth: process.env.ANILIST_AUTH_URL, malApi: process.env.MAL_API_URL, malAuth: process.env.MAL_AUTH_URL, ids: process.env.ANIME_IDS_URL },
  debridBases: { torbox: process.env.TORBOX_BASE_URL, realdebrid: process.env.REALDEBRID_BASE_URL, premiumize: process.env.PREMIUMIZE_BASE_URL },
});
await app.listen({ host: "0.0.0.0", port: Number(process.env.PORT ?? 4000) });

for (const sig of ["SIGINT", "SIGTERM"]) process.on(sig, () => Promise.all([app.close(), queue?.close()]).then(() => process.exit(0)));
