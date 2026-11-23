/**
 * Deezer metadata relay for the Musiclify apps.
 *
 * Deezer hides some catalogues (search, top songs, album lists) depending on the country the request
 * comes from: in Nepal, for example, Ed Sheeran's own albums don't show up at all. The apps fetch
 * those lists through here (this function runs in the US), so everyone sees the full catalogue.
 *
 * Only public, read-only Deezer endpoints are relayed; nothing about the user is sent or kept.
 * Responses are cached at the edge for an hour, so repeated searches don't reach Deezer.
 */

const ALLOWED: RegExp[] = [
  /^search(\/(track|artist|album))?$/,
  /^chart\/0\/(tracks|artists)$/,
  /^artist\/\d+(\/(top|albums|related|radio))?$/,
  /^album\/\d+(\/tracks)?$/,
  /^track\/\d+$/,
];
const PARAMS = new Set(["q", "limit", "index", "strict", "order"]);

export async function GET(request: Request, { params }: { params: Promise<{ path: string[] }> }) {
  const path = (await params).path.join("/");
  if (!ALLOWED.some((re) => re.test(path))) {
    return Response.json({ error: { message: "Not allowed" } }, { status: 404 });
  }
  const incoming = new URL(request.url).searchParams;
  const query = new URLSearchParams();
  for (const [k, v] of incoming) if (PARAMS.has(k) && v.length <= 200) query.set(k, v);

  const upstream = await fetch(`https://api.deezer.com/${path}${query.size ? `?${query}` : ""}`, {
    headers: { Accept: "application/json" },
  });
  const body = await upstream.text();
  return new Response(body, {
    status: upstream.ok ? 200 : upstream.status,
    headers: {
      "Content-Type": "application/json; charset=utf-8",
      "Cache-Control": "public, s-maxage=3600, stale-while-revalidate=86400",
      "Access-Control-Allow-Origin": "*",
    },
  });
}
