// AES-256-GCM for integration credentials. The key comes from ZYLO_SECRET (any long random string).
import { createCipheriv, createDecipheriv, createHash, randomBytes } from "node:crypto";

function key(secret = process.env.ZYLO_SECRET) {
  const problem = secretProblem(secret);
  if (problem) throw new Error(problem);
  return createHash("sha256").update(secret!).digest();
}

/** Plain-English reason ZYLO_SECRET is unusable, or null if it's fine. Checked at startup. */
export function secretProblem(secret = process.env.ZYLO_SECRET) {
  if (!secret) return "ZYLO_SECRET is not set. Add it to .env (see .env.example), then run: docker compose up -d";
  if (secret.length < 32)
    return `ZYLO_SECRET in .env is ${secret.length} characters; it needs at least 32. Generate one (see .env.example), then run: docker compose up -d`;
  return null;
}

export function encrypt(value: unknown, secret?: string) {
  const iv = randomBytes(12);
  const cipher = createCipheriv("aes-256-gcm", key(secret), iv);
  const data = Buffer.concat([cipher.update(JSON.stringify(value), "utf8"), cipher.final()]);
  return ["v1", iv, cipher.getAuthTag(), data].map((p) => (typeof p === "string" ? p : p.toString("base64url"))).join(".");
}

export function decrypt<T>(payload: string, secret?: string): T {
  const [version, iv, tag, data] = payload.split(".");
  if (version !== "v1" || !iv || !tag || !data) throw new Error("Unrecognised secret format");
  const decipher = createDecipheriv("aes-256-gcm", key(secret), Buffer.from(iv, "base64url"));
  decipher.setAuthTag(Buffer.from(tag, "base64url"));
  return JSON.parse(Buffer.concat([decipher.update(Buffer.from(data, "base64url")), decipher.final()]).toString("utf8"));
}
