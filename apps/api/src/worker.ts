// Background worker: keeps cached metadata fresh. Later phases add Trakt, tracker and library syncs here.
import { secretProblem } from "./secrets.js";
import { Queue, Worker } from "bullmq";
import { Redis } from "ioredis";
import { PrismaPg } from "@prisma/adapter-pg";
import { PrismaClient } from "./generated/prisma/client.js";
import { metadataService, NotConfigured } from "./metadata.js";

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
const metadata = metadataService(db);

const handlers: Record<string, () => Promise<unknown>> = {
  async "refresh-metadata"() {
    try {
      return { refreshed: await metadata.refreshStale() };
    } catch (e) {
      if (e instanceof NotConfigured) return { skipped: "TMDB not connected" };
      throw e;
    }
  },
};

const queue = new Queue("zylo", { connection });
await queue.upsertJobScheduler("refresh-metadata", { every: 60 * 60 * 1000 }, { name: "refresh-metadata" });

const worker = new Worker(
  "zylo",
  async (job) => {
    const handler = handlers[job.name];
    if (!handler) throw new Error(`No handler for job "${job.name}"`);
    return handler();
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
