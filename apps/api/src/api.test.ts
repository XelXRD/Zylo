// Runs against a real, empty Postgres: TEST_DATABASE_URL (migrated) is wiped first.
import { test } from "node:test";
import assert from "node:assert/strict";
import pg from "pg";
import { buildApp, hashPassword, verifyPassword } from "./app.js";

const url = process.env.TEST_DATABASE_URL;
if (!url) throw new Error("Set TEST_DATABASE_URL to an empty, migrated database");

test("password hashing", async () => {
  const h = await hashPassword("correct horse");
  assert.equal(await verifyPassword("correct horse", h), true);
  assert.equal(await verifyPassword("wrong horse", h), false);
  assert.equal(await verifyPassword("x", "garbage"), false);
});

test("phase 1 flow: setup, sign in, profiles, settings", async () => {
  const client = new pg.Client({ connectionString: url });
  await client.connect();
  await client.query('TRUNCATE "User" CASCADE');
  await client.end();

  const app = buildApp({ databaseUrl: url });
  const call = async (method: string, path: string, body?: object, cookie?: string) => {
    const res = await app.inject({ method: method as "GET", url: path, payload: body, headers: cookie ? { cookie } : {} });
    return { status: res.statusCode, json: res.body ? JSON.parse(res.body) : null, setCookie: res.headers["set-cookie"] };
  };

  assert.deepEqual((await call("GET", "/setup")).json, { needsSetup: true });
  assert.equal((await call("GET", "/profiles")).status, 401);
  assert.equal((await call("POST", "/setup", { email: "a@b.co", password: "short", name: "Alex" })).status, 400);

  const setup = await call("POST", "/setup", { email: "Alex@Example.com", password: "password123", name: "Alex" });
  assert.equal(setup.status, 201);
  assert.equal(setup.json.isAdmin, true);
  assert.equal(setup.json.email, "alex@example.com");
  assert.equal((await call("POST", "/setup", { email: "x@y.co", password: "password123", name: "X" })).status, 409);

  assert.equal((await call("POST", "/auth/login", { email: "alex@example.com", password: "nope-nope" })).status, 401);
  const login = await call("POST", "/auth/login", { email: "alex@example.com", password: "password123" });
  assert.equal(login.status, 200);
  const cookie = String(login.setCookie).split(";")[0];
  assert.match(String(login.setCookie), /HttpOnly/);

  let profiles = (await call("GET", "/profiles", undefined, cookie)).json;
  assert.equal(profiles.length, 1);
  assert.equal(profiles[0].name, "Alex");

  const lily = await call("POST", "/profiles", { name: "Kids", color: "amber" }, cookie);
  assert.equal(lily.status, 201);
  assert.equal((await call("POST", "/profiles", { name: "Kids", color: "rose" }, cookie)).status, 409);
  assert.equal((await call("POST", "/profiles", { name: "Bad", color: "#ff0000" }, cookie)).status, 400);
  assert.equal((await call("PATCH", `/profiles/${lily.json.id}`, { name: "Family" }, cookie)).json.name, "Family");

  assert.equal((await call("DELETE", `/profiles/${lily.json.id}`, undefined, cookie)).status, 204);
  profiles = (await call("GET", "/profiles", undefined, cookie)).json;
  assert.equal((await call("DELETE", `/profiles/${profiles[0].id}`, undefined, cookie)).status, 400, "last profile is kept");

  assert.equal((await call("GET", "/settings", undefined, cookie)).json.theme, "system");
  assert.equal((await call("PUT", "/settings", { theme: "dark" }, cookie)).json.theme, "dark");
  assert.equal((await call("PUT", "/settings", { theme: "neon" }, cookie)).status, 400);
  assert.equal((await call("GET", "/auth/me", undefined, cookie)).json.settings.theme, "dark");

  assert.equal((await call("PUT", "/auth/password", { currentPassword: "wrong", newPassword: "newpassword1" }, cookie)).status, 400);
  assert.equal((await call("PUT", "/auth/password", { currentPassword: "password123", newPassword: "newpassword1" }, cookie)).status, 204);
  assert.equal((await call("POST", "/auth/login", { email: "alex@example.com", password: "newpassword1" })).status, 200);

  assert.equal((await call("POST", "/auth/logout", undefined, cookie)).status, 204);
  assert.equal((await call("GET", "/auth/me", undefined, cookie)).status, 401, "session is gone after sign-out");

  assert.equal((await call("GET", "/openapi.json")).json.openapi.startsWith("3"), true);
  await app.close();
});
