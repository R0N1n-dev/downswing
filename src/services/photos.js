import { Downswing } from './downswing'
import { parsePostMedia, mediaName } from '../lib/posts'

// Fetch a post page and hand back the media it publishes, named and ready to queue.
//
// The page has to be read natively (a WebView cannot fetch another origin), but nothing here
// downloads anything: each item is a direct URL that goes into the ordinary queue, so a photo is
// filed by aria2 into the Pictures folder and shows up in the library like any other file.
export async function photosFrom(url, { cookie = '', limit = 10 } = {}) {
  const res = await Downswing.fetchPage({ url, ...(cookie ? { cookie } : {}) })
  return parsePostMedia(res?.html, res?.url || url)
    .slice(0, limit)
    .map((m, i) => ({ ...m, name: mediaName(m, url, i) }))
}
