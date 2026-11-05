// Photo (and video) extraction from a public post page.
//
// The whole trick is that a photo is not a special kind of download: once the page's own media URLs
// are known, a picture is an ordinary direct link that aria2 fetches in one block, with an
// extension that puts it in the Pictures folder. So this module only has to answer "which media
// does this page publish?" — the queue, the folders and the library already handle the rest.
//
// Sources, in the order a page usually offers them:
//   1. <script type="application/ld+json"> — image[] / video[].contentUrl / contentUrl
//   2. og:image, og:image:secure_url, og:video, twitter:image — one media each
//   3. Instagram's server-rendered JSON — "display_url" / "video_url" pairs
// Login-gated posts are out of scope: they publish nothing without a session cookie.

const IMAGE_EXT = /\.(jpe?g|png|webp|gif|bmp|heic|avif)$/i
const VIDEO_EXT = /\.(mp4|mov|m4v|webm)$/i
const SKIP = /(^data:|^blob:|sprite|placeholder|blank\.(gif|png))/i

// A URL's own suffix is the only reliable type hint: CDN links carry no meaningful MIME.
function kindOf(url, fallback = 'image') {
  let path = url
  try { path = new URL(url, 'https://x/').pathname } catch { /* keep the raw string */ }
  if (IMAGE_EXT.test(path)) return 'image'
  if (VIDEO_EXT.test(path)) return 'video'
  return fallback
}

function clean(url) {
  if (typeof url !== 'string') return ''
  let v = url.trim().replace(/^"|"$/g, '').replace(/\\u0026/gi, '&').replace(/\\\//g, '/')
  if (!v || SKIP.test(v)) return ''
  if (!/^https?:\/\//i.test(v)) return ''
  return v
}

// Pull image/video URLs out of any JSON-LD shape: SocialMediaPosting, ImageObject, VideoObject,
// arrays of them, or a nested graph.
function fromJsonLd(node, out, depth = 0) {
  if (!node || depth > 6) return
  if (Array.isArray(node)) { node.forEach((n) => fromJsonLd(n, out, depth + 1)); return }
  if (typeof node !== 'object') return
  const push = (u, kind) => { const v = clean(u); if (v) out.push({ url: v, kind: kindOf(v, kind) }) }

  const img = node.image ?? node.thumbnailUrl
  if (typeof img === 'string') push(img, 'image')
  else if (Array.isArray(img)) img.forEach((i) => (typeof i === 'string' ? push(i, 'image') : push(i?.url ?? i?.contentUrl, 'image')))
  else if (img && typeof img === 'object') push(img.url ?? img.contentUrl ?? img['@id'], 'image')

  const vid = node.video ?? node.contentUrl
  if (typeof vid === 'string') push(vid, 'video')
  else if (Array.isArray(vid)) vid.forEach((v) => push(typeof v === 'string' ? v : v?.contentUrl ?? v?.url, 'video'))
  else if (vid && typeof vid === 'object') push(vid.contentUrl ?? vid.url, 'video')

  // Do not walk into unrelated nodes twice; only the document body objects matter here.
  if (node['@graph']) fromJsonLd(node['@graph'], out, depth + 1)
  if (node.mainEntity) fromJsonLd(node.mainEntity, out, depth + 1)
}

// Instagram keeps a JSON blob in the page; display_url/video_url are the same fields its own
// React tree renders from. Last resort, but it is what actually catches carousels.
function fromInstagramJson(html, out) {
  const re = /"(display_url|video_url)"\s*:\s*"([^"]+)"/g
  let m
  while ((m = re.exec(html))) {
    const v = clean(m[2])
    if (v) out.push({ url: v, kind: m[1] === 'video_url' ? 'video' : 'image' })
  }
}

// Media a page publishes, de-duplicated, in page order. Images keep their order so a carousel
// becomes 1, 2, 3 — the same numbering the queue will show.
export function parsePostMedia(html, pageUrl = '') {
  if (!html || typeof html !== 'string') return []
  const doc = new DOMParser().parseFromString(html, 'text/html')
  const found = []

  for (const script of doc.querySelectorAll('script[type="application/ld+json"]')) {
    try { fromJsonLd(JSON.parse(script.textContent || ''), found) } catch { /* not JSON, skip */ }
  }

  // og:/twitter: tags are what most sites fall back to, and what a plain post has when the JSON-LD
  // is a WebPage rather than a SocialMediaPosting.
  const meta = (selector) => [...doc.querySelectorAll(selector)].map((el) => clean(el.getAttribute('content')))
  if (!found.length) {
    meta('meta[property="og:image:secure_url"], meta[property="og:image"], meta[name="twitter:image"]')
      .forEach((u) => found.push({ url: u, kind: 'image' }))
    meta('meta[property="og:video:secure_url"], meta[property="og:video"]')
      .forEach((u) => found.push({ url: u, kind: 'video' }))
  }

  if (!found.length && /instagram\.com/i.test(pageUrl)) fromInstagramJson(html, found)

  // Same URL twice (og:image + JSON-LD) is one download, not two.
  const seen = new Set()
  return found.filter((m) => {
    if (!m.url || seen.has(m.url)) return false
    seen.add(m.url)
    return true
  })
}

// Instagram short codes make the nicest file names: instagram-CxYz12-1.jpg
export function shortcodeOf(pageUrl = '') {
  const m = /(?:p|reel|reels|tv)\/([A-Za-z0-9_-]{5,})/.exec(pageUrl)
  return m ? m[1] : ''
}

// A file name with the right extension, numbered so a carousel cannot collide in one folder.
export function mediaName(media, pageUrl = '', index = 0) {
  const code = shortcodeOf(pageUrl)
  const n = index + 1
  let ext = ''
  try {
    const path = new URL(media.url).pathname
    const m = /\.(jpe?g|png|webp|gif|bmp|heic|avif|mp4|mov|m4v|webm)$/i.exec(path)
    if (m) ext = m[1].toLowerCase()
  } catch { /* fall through to the kind default */ }
  if (!ext) ext = media.kind === 'video' ? 'mp4' : 'jpg'
  const base = code ? `instagram-${code}-${n}` : `${media.kind === 'video' ? 'video' : 'photo'}-${n}`
  return `${base}.${ext}`
}
