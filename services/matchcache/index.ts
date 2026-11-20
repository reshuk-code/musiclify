/**
 * Musiclify match cache: a Neon Function.
 *
 * Shares only facts about songs: "Deezer track X is YouTube video Y". No audio, no lyrics,
 * no user data. IPs are only held in memory for rate limiting and never stored.
 *
 *   GET  /v1/match/:deezerId                                -> best known YouTube match, or 404
 *   POST /v1/match  {deezer_id, youtube_id, duration_sec}   -> vote for a match
 */

// Zero-dependency Postgres access via Neon's SQL-over-HTTP endpoint (keeps the bundle tiny).
const DATABASE_URL = process.env.DATABASE_URL ?? "";
const SQL_ENDPOINT = `https://${new URL(DATABASE_URL).hostname}/sql`;

const pool = {
  async query(query: string, params: unknown[]): Promise<{ rows: any[] }> {
    const res = await fetch(SQL_ENDPOINT, {
      method: "POST",
      headers: { "content-type": "application/json", "neon-connection-string": DATABASE_URL },
      body: JSON.stringify({ query, params: params.map(String) }),
    });
    if (!res.ok) throw new Error(`SQL HTTP ${res.status}: ${await res.text()}`);
    return (await res.json()) as { rows: any[] };
  },
};

const YOUTUBE_ID = /^[A-Za-z0-9_-]{11}$/;
const MAX_BODY = 512;

// ---- in-memory rate limiting (per runtime instance; good enough to stop casual abuse) ----
const hits = new Map<string, { count: number; resetAt: number }>();
const voted = new Map<string, number>(); // `${ip}:${deezerId}` -> expiry

function limited(key: string, max: number, windowMs: number): boolean {
  const now = Date.now();
  const entry = hits.get(key);
  if (!entry || entry.resetAt < now) {
    hits.set(key, { count: 1, resetAt: now + windowMs });
    return false;
  }
  entry.count++;
  return entry.count > max;
}

setInterval(() => {
  const now = Date.now();
  for (const [k, v] of hits) if (v.resetAt < now) hits.delete(k);
  for (const [k, exp] of voted) if (exp < now) voted.delete(k);
}, 60_000).unref();

const json = (body: unknown, status = 200) =>
  new Response(JSON.stringify(body), {
    status,
    headers: { "content-type": "application/json", "cache-control": status === 200 ? "public, max-age=300" : "no-store" },
  });

function clientIp(req: Request): string {
  return req.headers.get("x-forwarded-for")?.split(",")[0].trim() || "unknown";
}

async function getMatch(deezerId: string): Promise<Response> {
  const { rows } = await pool.query(
    `SELECT youtube_id, duration_sec, votes FROM song_matches
     WHERE deezer_id = $1 ORDER BY votes DESC, last_seen DESC LIMIT 1`,
    [deezerId],
  );
  if (rows.length === 0) return json({ error: "not_found" }, 404);
  const r = rows[0];
  return json({ deezer_id: Number(deezerId), youtube_id: r.youtube_id, duration_sec: r.duration_sec, votes: r.votes });
}

async function postMatch(req: Request, ip: string): Promise<Response> {
  const text = await req.text();
  if (text.length > MAX_BODY) return json({ error: "too_large" }, 413);
  let body: any;
  try {
    body = JSON.parse(text);
  } catch {
    return json({ error: "bad_json" }, 400);
  }
  const deezerId = Number(body?.deezer_id);
  const youtubeId = String(body?.youtube_id ?? "");
  const duration = Number(body?.duration_sec);
  if (!Number.isSafeInteger(deezerId) || deezerId <= 0 || !YOUTUBE_ID.test(youtubeId) ||
      !Number.isInteger(duration) || duration < 1 || duration > 7200) {
    return json({ error: "invalid" }, 400);
  }

  // One vote per device/IP per song per day.
  const voteKey = `${ip}:${deezerId}`;
  if (voted.has(voteKey)) return json({ ok: true, counted: false });
  voted.set(voteKey, Date.now() + 24 * 3600_000);

  await pool.query(
    `INSERT INTO song_matches (deezer_id, youtube_id, duration_sec) VALUES ($1, $2, $3)
     ON CONFLICT (deezer_id, youtube_id) DO UPDATE SET votes = song_matches.votes + 1, last_seen = now()`,
    [deezerId, youtubeId, duration],
  );
  return json({ ok: true, counted: true });
}

export default {
  async fetch(req: Request): Promise<Response> {
    const url = new URL(req.url);
    const ip = clientIp(req);
    try {
      if (url.pathname === "/" || url.pathname === "/health") return json({ ok: true, service: "musiclify-matchcache" });

      const m = url.pathname.match(/^\/v1\/match\/(\d{1,15})$/);
      if (req.method === "GET" && m) {
        if (limited(`g:${ip}`, 300, 60_000)) return json({ error: "rate_limited" }, 429);
        return await getMatch(m[1]);
      }
      if (req.method === "POST" && url.pathname === "/v1/match") {
        if (limited(`p:${ip}`, 120, 3600_000)) return json({ error: "rate_limited" }, 429);
        return await postMatch(req, ip);
      }
      return json({ error: "not_found" }, 404);
    } catch (e) {
      console.error("request failed", e);
      return json({ error: "server_error" }, 500);
    }
  },
};
