// Background worker: keeps cached metadata fresh, and syncs Trakt and the anime trackers.
import { secretProblem } from "./secrets.js";
import { Queue, Worker } from "bullmq";
import { Redis } from "ioredis";
import { PrismaPg } from "@prisma/adapter-pg";
import { PrismaClient } from "./generated/prisma/client.js";
import { metadataService, NotConfigured } from "./metadata.js";
import { traktService } from "./trakt.js";
import { animeService, type Tracker } from "./anime.js";

// Fail fast on a bad secret: otherwise the first integration save fails with a confusing error.
const secretError = secretProblem();
if (secretError) {
  console.error(`\n  Zylo can't start: ${secretError}\n`);
  process.exit(1);
}

const databaseUrl = process.env.DATABASE_URL;
if (!databaseUrl) throw new Error("DATABASE_URL is not set");

// BullMQ needs maxRetriesPerRequest: null on worker connections.
const connection = new Redis(process.env.REDIS_URL ?? "redis://localhost:6379", { maxRetriesPerRequest: null });
const db = new PrismaClient({ adapter: new PrismaPg({ connectionString: databaseUrl }) });
const bases = { tmdb: process.env.TMDB_BASE_URL, tvdb: process.env.TVDB_BASE_URL };
const metadata = metadataService(db, bases);
const trakt = traktService(db, metadata, process.env.TRAKT_BASE_URL);
const anime = animeService(db, metadata, { anilist: process.env.ANILIST_BASE_URL, malApi: process.env.MAL_API_URL, malAuth: process.env.MAL_AUTH_URL, ids: process.env.ANIME_IDS_URL });

const handlers: Record<string, (data: { profileId?: string }) => Promise<unknown>> = {
  async "refresh-metadata"() {
    try {
      return { refreshed: await metadata.refreshStale() };
    } catch (e) {
      if (e instanceof NotConfigured) return { skipped: "TMDB not connected" };
      throw e;
    }
  },
  async "trakt-sync"({ profileId }) {
    await trakt.sync(profileId!);
    // Fill in posters and details for newly imported titles straight away, not at the next scheduled run.
    const filled = await metadata.refreshStale().catch(() => 0);
    return { profileId, filled };
  },
  // Every connected profile with auto-sync on; one failing account doesn't stop the rest.
  async "trakt-sync-all"() {
    const accounts = await db.traktAccount.findMany({ where: { syncEnabled: true }, select: { profileId: true } });
    let ok = 0;
    for (const a of accounts) await trakt.sync(a.profileId).then(() => ok++, () => {});
    return { synced: ok, of: accounts.length };
  },
  // AniList / MyAnimeList: import lists, send progress that didn't get through at the time, match new titles.
  async "anime-sync-all"() {
    const accounts = await db.animeAccount.findMany({ select: { profileId: true, tracker: true } });
    let ok = 0;
    for (const a of accounts) await anime.sync(a.profileId, a.tracker as Tracker).then(() => ok++, () => {});
    const filled = await metadata.refreshStale().catch(() => 0);
    return { synced: ok, of: accounts.length, filled };
  },
};

const queue = new Queue("zylo", { connection });
await queue.upsertJobScheduler("refresh-metadata", { every: 15 * 60 * 1000 }, { name: "refresh-metadata" });
await queue.upsertJobScheduler("trakt-sync-all", { every: 15 * 60 * 1000 }, { name: "trakt-sync-all" });
await queue.upsertJobScheduler("anime-sync-all", { every: 30 * 60 * 1000 }, { name: "anime-sync-all" }); // AniList allows 30-90 requests a minute

const worker = new Worker(
  "zylo",
  async (job) => {
    const handler = handlers[job.name];
    if (!handler) throw new Error(`No handler for job "${job.name}"`);
    return handler(job.data);
  },
  { connection },
);

worker.on("ready", () => console.log("zylo-worker ready"));
worker.on("completed", (job, result) => console.log(`${job.name}:`, JSON.stringify(result)));
worker.on("error", (err) => console.error("worker error:", err.message));
worker.on("failed", (job, err) => console.error(`job ${job?.name} failed:`, err.message));

for (const sig of ["SIGINT", "SIGTERM"]) {
  process.on(sig, async () => {
    await worker.close();
    await queue.close();
    await db.$disconnect();
    process.exit(0);
  });
}
