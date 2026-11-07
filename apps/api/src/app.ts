import Fastify, { type FastifyReply, type FastifyRequest } from "fastify";
import cookie from "@fastify/cookie";
import rateLimit from "@fastify/rate-limit";
import swagger from "@fastify/swagger";
import { PrismaPg } from "@prisma/adapter-pg";
import { createHash, randomBytes, scrypt, timingSafeEqual } from "node:crypto";
import { promisify } from "node:util";
import { PrismaClient, type User } from "./generated/prisma/client.js";

const scryptAsync = promisify(scrypt) as (pw: string, salt: Buffer, len: number) => Promise<Buffer>;

const COOKIE = "zylo_session";
const SESSION_DAYS = 30;
const MAX_PROFILES = 8;
export const PROFILE_COLORS = ["teal", "amber", "rose", "violet", "sky", "lime", "orange", "slate"] as const;

const DEFAULT_SETTINGS = { language: "en-GB", region: "GB", theme: "system" };

// --- passwords + sessions ---------------------------------------------------

export async function hashPassword(pw: string) {
  const salt = randomBytes(16);
  const key = await scryptAsync(pw, salt, 64);
  return `scrypt$${salt.toString("hex")}$${key.toString("hex")}`;
}

export async function verifyPassword(pw: string, stored: string) {
  const [algo, saltHex, keyHex] = stored.split("$");
  if (algo !== "scrypt" || !saltHex || !keyHex) return false;
  const expected = Buffer.from(keyHex, "hex");
  const actual = await scryptAsync(pw, Buffer.from(saltHex, "hex"), expected.length);
  return timingSafeEqual(actual, expected);
}

const sha256 = (s: string) => createHash("sha256").update(s).digest("hex");

// --- JSON schemas (validation + OpenAPI) -------------------------------------

const credentials = {
  type: "object",
  required: ["email", "password"],
  additionalProperties: false,
  properties: {
    email: { type: "string", format: "email", maxLength: 254 },
    password: { type: "string", minLength: 8, maxLength: 200 },
  },
} as const;

const profileName = { type: "string", minLength: 1, maxLength: 32, pattern: "\\S" } as const;
const profileColor = { type: "string", enum: PROFILE_COLORS } as const;
const idParam = { type: "object", required: ["id"], properties: { id: { type: "string", format: "uuid" } } } as const;

const settingsBody = {
  type: "object",
  additionalProperties: false,
  properties: {
    language: { type: "string", pattern: "^[a-z]{2}(-[A-Z]{2})?$" },
    region: { type: "string", pattern: "^[A-Z]{2}$" },
    theme: { type: "string", enum: ["system", "light", "dark"] },
  },
} as const;

type Creds = { email: string; password: string };
type Settings = typeof DEFAULT_SETTINGS;

// --- app ----------------------------------------------------------------------

declare module "fastify" {
  interface FastifyRequest {
    user: User;
  }
}

export function buildApp(opts: { databaseUrl: string; secureCookies?: boolean; logger?: boolean }) {
  const db = new PrismaClient({ adapter: new PrismaPg({ connectionString: opts.databaseUrl }) });
  const app = Fastify({ logger: opts.logger ?? false });

  app.register(cookie);
  app.register(rateLimit, { global: false });
  app.register(swagger, {
    openapi: { info: { title: "Zylo API", version: "0.1.0" } },
  });
  app.addHook("onClose", () => db.$disconnect());

  const startSession = async (reply: FastifyReply, userId: string) => {
    const token = randomBytes(32).toString("base64url");
    const expiresAt = new Date(Date.now() + SESSION_DAYS * 86_400_000);
    await db.session.create({ data: { tokenHash: sha256(token), userId, expiresAt } });
    reply.setCookie(COOKIE, token, {
      path: "/",
      httpOnly: true,
      sameSite: "strict",
      secure: opts.secureCookies ?? false,
      expires: expiresAt,
    });
  };

  const requireUser = async (req: FastifyRequest, reply: FastifyReply) => {
    const token = req.cookies[COOKIE];
    const session = token
      ? await db.session.findUnique({ where: { tokenHash: sha256(token) }, include: { user: true } })
      : null;
    if (!session || session.expiresAt < new Date()) {
      return reply.code(401).send({ error: "Not signed in" });
    }
    req.user = session.user;
  };

  const publicUser = (u: User) => ({
    id: u.id,
    email: u.email,
    isAdmin: u.isAdmin,
    settings: { ...DEFAULT_SETTINGS, ...(u.settings as object) },
  });

  const strictLimit = { config: { rateLimit: { max: 10, timeWindow: "1 minute" } } };

  app.after(() => {
    app.get("/health", async () => ({ ok: true }));
    app.get("/openapi.json", { schema: { hide: true } }, async () => app.swagger());

    // First run: the first account becomes the admin. After that, setup is closed.
    app.get("/setup", async () => ({ needsSetup: (await db.user.count()) === 0 }));

    app.post<{ Body: Creds & { name: string } }>(
      "/setup",
      {
        ...strictLimit,
        schema: {
          body: { ...credentials, required: [...credentials.required, "name"], properties: { ...credentials.properties, name: profileName } },
        },
      },
      async (req, reply) => {
        const { email, password, name } = req.body;
        const passwordHash = await hashPassword(password);
        // Serializable so two simultaneous setup requests can't both create an admin.
        const user = await db
          .$transaction(
            async (tx) => {
              if ((await tx.user.count()) > 0) return null;
              return tx.user.create({
                data: {
                  email: email.toLowerCase(),
                  passwordHash,
                  isAdmin: true,
                  profiles: { create: { name: name.trim(), color: PROFILE_COLORS[0] } },
                },
              });
            },
            { isolationLevel: "Serializable" },
          )
          .catch(() => null);
        if (!user) return reply.code(409).send({ error: "Zylo is already set up. Sign in instead." });
        await startSession(reply, user.id);
        return reply.code(201).send(publicUser(user));
      },
    );

    app.post<{ Body: Creds }>("/auth/login", { ...strictLimit, schema: { body: credentials } }, async (req, reply) => {
      const user = await db.user.findUnique({ where: { email: req.body.email.toLowerCase() } });
      if (!user || !(await verifyPassword(req.body.password, user.passwordHash))) {
        return reply.code(401).send({ error: "That email and password don't match." });
      }
      await startSession(reply, user.id);
      return publicUser(user);
    });

    app.post("/auth/logout", async (req, reply) => {
      const token = req.cookies[COOKIE];
      if (token) await db.session.deleteMany({ where: { tokenHash: sha256(token) } });
      reply.clearCookie(COOKIE, { path: "/" });
      return reply.code(204).send();
    });

    app.get("/auth/me", { preHandler: requireUser }, async (req) => publicUser(req.user));

    app.put<{ Body: { currentPassword: string; newPassword: string } }>(
      "/auth/password",
      {
        ...strictLimit,
        preHandler: requireUser,
        schema: {
          body: {
            type: "object",
            required: ["currentPassword", "newPassword"],
            properties: { currentPassword: { type: "string" }, newPassword: credentials.properties.password },
          },
        },
      },
      async (req, reply) => {
        if (!(await verifyPassword(req.body.currentPassword, req.user.passwordHash))) {
          return reply.code(400).send({ error: "Your current password is wrong." });
        }
        await db.user.update({ where: { id: req.user.id }, data: { passwordHash: await hashPassword(req.body.newPassword) } });
        // Sign out every other device; keep this one.
        const current = sha256(req.cookies[COOKIE]!);
        await db.session.deleteMany({ where: { userId: req.user.id, NOT: { tokenHash: current } } });
        return reply.code(204).send();
      },
    );

    // --- profiles ---

    app.get("/profiles", { preHandler: requireUser }, async (req) =>
      db.profile.findMany({ where: { userId: req.user.id }, orderBy: { createdAt: "asc" } }),
    );

    app.post<{ Body: { name: string; color: string } }>(
      "/profiles",
      {
        preHandler: requireUser,
        schema: { body: { type: "object", required: ["name", "color"], additionalProperties: false, properties: { name: profileName, color: profileColor } } },
      },
      async (req, reply) => {
        if ((await db.profile.count({ where: { userId: req.user.id } })) >= MAX_PROFILES) {
          return reply.code(400).send({ error: `You can have up to ${MAX_PROFILES} profiles.` });
        }
        try {
          const profile = await db.profile.create({ data: { userId: req.user.id, name: req.body.name.trim(), color: req.body.color } });
          return reply.code(201).send(profile);
        } catch {
          return reply.code(409).send({ error: "You already have a profile with that name." });
        }
      },
    );

    app.patch<{ Params: { id: string }; Body: { name?: string; color?: string } }>(
      "/profiles/:id",
      {
        preHandler: requireUser,
        schema: { params: idParam, body: { type: "object", additionalProperties: false, minProperties: 1, properties: { name: profileName, color: profileColor } } },
      },
      async (req, reply) => {
        const data = { ...req.body, ...(req.body.name && { name: req.body.name.trim() }) };
        try {
          const { count } = await db.profile.updateMany({ where: { id: req.params.id, userId: req.user.id }, data });
          if (!count) return reply.code(404).send({ error: "Profile not found." });
        } catch {
          return reply.code(409).send({ error: "You already have a profile with that name." });
        }
        return db.profile.findUnique({ where: { id: req.params.id } });
      },
    );

    app.delete<{ Params: { id: string } }>("/profiles/:id", { preHandler: requireUser, schema: { params: idParam } }, async (req, reply) => {
      if ((await db.profile.count({ where: { userId: req.user.id } })) <= 1) {
        return reply.code(400).send({ error: "You need at least one profile." });
      }
      const { count } = await db.profile.deleteMany({ where: { id: req.params.id, userId: req.user.id } });
      return count ? reply.code(204).send() : reply.code(404).send({ error: "Profile not found." });
    });

    // --- settings ---

    app.get("/settings", { preHandler: requireUser }, async (req) => publicUser(req.user).settings);

    app.put<{ Body: Partial<Settings> }>("/settings", { preHandler: requireUser, schema: { body: settingsBody } }, async (req) => {
      const settings = { ...publicUser(req.user).settings, ...req.body };
      await db.user.update({ where: { id: req.user.id }, data: { settings } });
      return settings;
    });
  });

  return app;
}
