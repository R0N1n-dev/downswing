import { fileKind } from './format'

// Every download the app saves lands in one tree inside the phone's Downloads:
//
//   Downloads/Downswing/Video
//   Downloads/Downswing/Audio
//   Downloads/Downswing/Pictures
//   Downloads/Downswing/Documents
//   Downloads/Downswing/Archives
//   Downloads/Downswing/Apps
//   Downloads/Downswing/Torrents
//
// The category is worked out from the file name and the source, so a YouTube video, an MP3
// and a ZIP never share a folder. The engines create the folders on the way in (see
// Engines.resolveOutputDir in android/), which also means no empty folders are ever made.
export const CATEGORY_ROOT = 'Downswing'

export const CATEGORIES = [
  { id: 'video', dir: 'Video', label: 'Video', kinds: ['mp4', 'mkv', 'webm'] },
  { id: 'audio', dir: 'Audio', label: 'Audio', kinds: ['mp3', 'm4a'] },
  { id: 'image', dir: 'Pictures', label: 'Pictures', kinds: ['jpg', 'png'] },
  { id: 'doc', dir: 'Documents', label: 'Documents', kinds: ['pdf', 'epub'] },
  { id: 'archive', dir: 'Archives', label: 'Archives', kinds: ['zip', '7z'] },
  { id: 'app', dir: 'Apps', label: 'Apps', kinds: ['apk', 'xapk'] },
  { id: 'torrent', dir: 'Torrents', label: 'Torrents', kinds: ['torrent'] },
]

// Extensions the library chips do not track but a folder still wants to sort correctly.
const EXTRA_EXT = {
  app: ['apk', 'xapk', 'apks', 'aab'],
  archive: ['iso', 'img', 'bin', 'dmg', 'pkg', 'xz', 'bz2', 'tgz', 'zst', 'deb', 'rpm', 'appimage', 'msi', 'exe'],
  image: ['svg', 'heic', 'avif', 'psd', 'bmp', 'tiff'],
  doc: ['pdf', 'doc', 'docx', 'xls', 'xlsx', 'ppt', 'pptx', 'odt', 'epub', 'mobi', 'txt', 'csv', 'json', 'xml', 'sql'],
  video: ['ts', 'm4v', 'wmv', '3gp', 'flv'],
  audio: ['opus', 'ogg', 'flac', 'wav', 'aac'],
}

const dirOf = (id) => (CATEGORIES.find((c) => c.id === id) || CATEGORIES[0]).dir

// Torrents keep their multi-file structure, so they get a folder of their own rather than
// being scattered by whatever the biggest file inside happens to be.
export function categoryFor(name = '', kind = 'video', audioOnly = false) {
  if (kind === 'torrent') return 'torrent'
  const ext = (String(name).split('.').pop() || '').toLowerCase()
  for (const [id, list] of Object.entries(EXTRA_EXT)) if (list.includes(ext)) return id
  const known = fileKind(String(name), audioOnly, kind)
  return CATEGORIES.some((c) => c.id === known) ? known : (audioOnly ? 'audio' : 'video')
}

// Relative path handed to the native engines: "Downswing/Video". Empty when the user turned
// category folders off — then the download goes straight into the chosen root.
export function subFolderFor({ name, kind, audioOnly, organise = true }) {
  if (!organise) return ''
  return `${CATEGORY_ROOT}/${dirOf(categoryFor(name, kind, audioOnly))}`
}

// The absolute folder a download will land in, for the UI. `root` is '' for the private app
// folder, where only the native side knows the real path.
export function targetPath(root, subDir) {
  const rel = subDir || ''
  return [root, rel].filter(Boolean).join('/').replace(/\/{2,}/g, '/')
}

// Short form for rows and Details: "Download/Downswing/Video", "App folder/Downswing/Audio".
export function prettyPath(path, { root = '' } = {}) {
  const p = path || targetPath(root, '')
  if (!p) return 'App folder'
  return p.replace(/^\/storage\/emulated\/0\//, '').replace(/^\/sdcard\//, '')
}
