// Stremio addon protocol client. Spec: https://github.com/Stremio/stremio-addon-sdk/blob/master/docs/protocol.md
// Addons are untrusted remote services: everything goes through safeFetchJson and is validated here.
import { safeFetchJson, UnsafeUrl, type SafeFetchOptions } from "./safe-fetch.js";

type Resource = string | { name: string; types?: string[]; idPrefixes?: string[] };
export type Catalog = { type: string; id: string; name?: string; extra?: { name: string; isRequired?: boolean }[]; extraRequired?: string[] };
export type Manifest = {
  id: string;
  name: string;
  version: string;
  description?: string;
  logo?: string;
  resources: Resource[];
  types: string[];
  idPrefixes?: string[];
  catalogs: Catalog[];
  behaviorHints: { adult?: boolean; p2p?: boolean; configurable?: boolean; configurationRequired?: boolean };
};

const str = (v: unknown, max: number) => (typeof v === "string" && v.length > 0 && v.length <= max ? v : undefined);
const strArr = (v: unknown) => (Array.isArray(v) ? v.filter((x): x is string => typeof x === "string" && x.length <= 100).slice(0, 50) : undefined);
const httpsUrl = (v: unknown) => {
  const s = str(v, 2000);
  return s && /^https:\/\//i.test(s) ? s : undefined;
};

/** Keep only the fields Zylo uses, with sane types and sizes. Throws with a plain reason if it isn't a manifest. */
export function validateManifest(raw: unknown): Manifest {
  if (!raw || typeof raw !== "object") throw new UnsafeUrl("That address didn't return an addon manifest.");
  const m = raw as Record<string, unknown>;
  const id = str(m.id, 200);
  const name = str(m.name, 100);
  const version = str(m.version, 50);
  const resources = Array.isArray(m.resources)
    ? m.resources
        .map((r): Resource | null => {
          if (typeof r === "string") return r.length <= 50 ? r : null;
          if (r && typeof r === "object" && typeof (r as { name?: unknown }).name === "string") {
            const o = r as Record<string, unknown>;
            return { name: o.name as string, types: strArr(o.types), idPrefixes: strArr(o.idPrefixes) };
          }
          return null;
        })
        .filter((r): r is Resource => r !== null)
    : [];
  const types = strArr(m.types);
  if (!id || !name || !version || !resources.length || !types) {
    throw new UnsafeUrl(
      "That link works, but it isn't a Stremio addon manifest (it needs an id, name, version, resources and types). Syncler vendor and package links aren't supported yet: they arrive in a later update.",
    );
  }
  const hints = (m.behaviorHints && typeof m.behaviorHints === "object" ? m.behaviorHints : {}) as Record<string, unknown>;
  const catalogs = Array.isArray(m.catalogs)
    ? m.catalogs
        .filter((c): c is Record<string, unknown> => !!c && typeof c === "object" && typeof c.type === "string" && typeof c.id === "string")
        .slice(0, 50)
        .map((c) => ({
          type: c.type as string,
          id: c.id as string,
          name: str(c.name, 100),
          extra: Array.isArray(c.extra)
            ? c.extra.filter((e): e is { name: string; isRequired?: boolean } => !!e && typeof e.name === "string").map((e) => ({ name: e.name, isRequired: e.isRequired === true }))
            : undefined,
          extraRequired: strArr(c.extraRequired),
        }))
    : [];
  return {
    id,
    name,
    version,
    description: str(m.description, 2000),
    logo: httpsUrl(m.logo),
    resources,
    types,
    idPrefixes: strArr(m.idPrefixes),
    catalogs,
    behaviorHints: {
      adult: hints.adult === true || m.adult === true,
      p2p: hints.p2p === true || m.p2p === true,
      configurable: hints.configurable === true,
      configurationRequired: hints.configurationRequired === true,
    },
  };
}

export const resourceNames = (m: Manifest) => m.resources.map((r) => (typeof r === "string" ? r : r.name));

/** Does this addon answer `resource` requests for this type and id? (idPrefixes don't apply to catalogs.) */
export function supports(m: Manifest, resource: string, type: string, id: string) {
  const entry = m.resources.find((r) => (typeof r === "string" ? r : r.name) === resource);
  if (!entry) return false;
  const types = (typeof entry === "object" && entry.types) || m.types;
  const prefixes = (typeof entry === "object" && entry.idPrefixes) || m.idPrefixes;
  return types.includes(type) && (!prefixes?.length || prefixes.some((p) => id.startsWith(p)));
}

/** The addon's base URL: the transport URL minus /manifest.json (configured addons keep their config path). */
/**
 * The addon's base URL: the manifest link minus its file name (configured addons keep their config path).
 * Usually that's /manifest.json, but any file name works: https://x.example/cfg/my-list.json → https://x.example/cfg
 */
export function baseOf(transportUrl: string) {
  const u = new URL(transportUrl);
  u.pathname = u.pathname.replace(/\/[^/]*$/, "");
  u.search = "";
  u.hash = "";
  return u.toString().replace(/\/$/, "");
}

export function resourceUrl(base: string, resource: string, type: string, id: string, extra?: Record<string, string>) {
  const extraPart = extra && Object.keys(extra).length ? `/${new URLSearchParams(extra).toString()}` : "";
  return `${base}/${resource}/${encodeURIComponent(type)}/${encodeURIComponent(id)}${extraPart}.json`;
}

export const fetchManifest = async (url: string, opts?: SafeFetchOptions) => validateManifest(await safeFetchJson(url, opts));

// --- Streams → Zylo's normalised playback options ----------------------------

type RawStream = Record<string, unknown> & { behaviorHints?: Record<string, unknown> };

export type PlaybackOption = {
  id: string;
  source: { kind: "addon"; id: string; name: string };
  label: string;
  description: string | null;
  kind: "url" | "youtube" | "torrent" | "external" | "usenet" | "archive";
  url?: string;
  ytId?: string;
  infoHash?: string;
  fileIdx?: number;
  externalUrl?: string;
  quality: string | null; // 4K, 1080p, 720p, 480p
  hdr: string[]; // HDR, DV
  size: string | null;
  filename: string | null;
  subtitles: { id: string; url: string; lang: string; label?: string }[];
  notWebReady: boolean;
  proxyHeaders?: Record<string, Record<string, string>>;
  bingeGroup: string | null;
  debrid?: { service: "torbox" | "realdebrid" | "premiumize"; cached: boolean }; // torrent a debrid service will stream
  playable: boolean; // something Zylo can hand to a player today
  note: string | null; // why not, in plain English
};

const QUALITY: [RegExp, string][] = [
  [/\b(2160p|4k|uhd)\b/i, "4K"],
  [/\b1080p\b/i, "1080p"],
  [/\b720p\b/i, "720p"],
  [/\b(480p|576p|sd)\b/i, "480p"],
];

export function normaliseStream(raw: RawStream, source: PlaybackOption["source"], index: number): PlaybackOption | null {
  const hints = (raw.behaviorHints && typeof raw.behaviorHints === "object" ? raw.behaviorHints : {}) as Record<string, unknown>;
  const label = str(raw.name, 300) ?? source.name;
  const description = str(raw.description, 2000) ?? str(raw.title, 2000) ?? null;
  const filename = str(hints.filename, 500) ?? null;
  const text = [label, description, filename].filter(Boolean).join(" ");

  const base = {
    id: `${source.id}:${index}`,
    source,
    label,
    description,
    quality: QUALITY.find(([re]) => re.test(text))?.[1] ?? null,
    hdr: [/\bHDR10\+?|\bHDR\b/i.test(text) && "HDR", /\bDV\b|dolby.?vision/i.test(text) && "DV"].filter(Boolean) as string[],
    size: text.match(/(\d+(?:[.,]\d+)?)\s?(GB|MB|GiB|MiB)\b/i)?.slice(1, 3).join(" ") ?? null,
    filename,
    subtitles: Array.isArray(raw.subtitles)
      ? raw.subtitles
          .filter((s): s is Record<string, string> => !!s && typeof s === "object" && typeof s.url === "string" && typeof s.lang === "string")
          .filter((s) => /^https?:\/\//i.test(s.url))
          .slice(0, 50)
          .map((s, i) => ({ id: String(s.id ?? i), url: s.url, lang: s.lang, ...(typeof s.label === "string" && { label: s.label }) }))
      : [],
    notWebReady: hints.notWebReady === true,
    proxyHeaders: hints.proxyHeaders && typeof hints.proxyHeaders === "object" ? (hints.proxyHeaders as PlaybackOption["proxyHeaders"]) : undefined,
    bingeGroup: str(hints.bingeGroup, 200) ?? null,
  };

  const url = str(raw.url, 4000);
  if (url && /^https?:\/\//i.test(url)) return { ...base, kind: "url", url, playable: true, note: base.proxyHeaders ? "Needs custom headers; plays through Zylo's player once it arrives." : null };
  const ytId = str(raw.ytId, 50);
  if (ytId && /^[\w-]{6,20}$/.test(ytId)) return { ...base, kind: "youtube", ytId, playable: true, note: null };
  const infoHash = str(raw.infoHash, 64);
  if (infoHash && /^[a-f0-9]{40}$/i.test(infoHash)) {
    return { ...base, kind: "torrent", infoHash: infoHash.toLowerCase(), fileIdx: typeof raw.fileIdx === "number" ? raw.fileIdx : undefined, playable: false, note: "Torrent source: connect a debrid service (Settings > Debrid) to play it." };
  }
  const externalUrl = str(raw.externalUrl, 4000);
  if (externalUrl && /^https?:\/\//i.test(externalUrl)) return { ...base, kind: "external", externalUrl, playable: true, note: "Opens on the provider's website." };
  if (str(raw.nzbUrl, 4000)) return { ...base, kind: "usenet", playable: false, note: "Usenet source: not supported." };
  if (["rarUrls", "zipUrls", "7zipUrls", "tgzUrls", "tarUrls"].some((k) => Array.isArray(raw[k]))) return { ...base, kind: "archive", playable: false, note: "Archive source: not supported." };
  return null; // nothing Zylo recognises
}
