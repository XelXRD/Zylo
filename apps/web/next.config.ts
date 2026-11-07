import type { NextConfig } from "next";

// The browser only ever talks to the web app; /api/* is proxied to zylo-core,
// so the session cookie stays same-origin.
const config: NextConfig = {
  output: "standalone",
  async rewrites() {
    return [{ source: "/api/:path*", destination: `${process.env.API_URL ?? "http://localhost:4000"}/:path*` }];
  },
};
export default config;
