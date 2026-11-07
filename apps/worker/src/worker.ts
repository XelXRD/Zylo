// Background worker. Phase 1 has no jobs yet; metadata refresh and syncs register here from Phase 2.
import { Worker } from "bullmq";
import { Redis } from "ioredis";

// BullMQ needs maxRetriesPerRequest: null on worker connections.
const connection = new Redis(process.env.REDIS_URL ?? "redis://localhost:6379", { maxRetriesPerRequest: null });

const handlers: Record<string, (data: unknown) => Promise<unknown>> = {};

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
worker.on("error", (err) => console.error("worker error:", err.message));
worker.on("failed", (job, err) => console.error(`job ${job?.name} failed:`, err.message));

for (const sig of ["SIGINT", "SIGTERM"]) process.on(sig, () => worker.close().then(() => process.exit(0)));
