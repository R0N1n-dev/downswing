export const QUALITIES = [240, 360, 480, 720, 1080]

// The root a download goes into. With category folders on, the app adds Downswing/<Category>
// underneath (see lib/categories.js), so Downloads becomes Downloads/Downswing/Video.
export const FOLDERS = [
  { value: '/storage/emulated/0/Download', label: 'Downloads' },
  { value: '', label: 'App folder (private)' },
  { value: '/storage/emulated/0/Movies', label: 'Movies' },
  { value: '/storage/emulated/0/Music', label: 'Music' },
]

// Where new installs put files: the shared Downloads folder, so a file manager can see them.
export const DEFAULT_FOLDER = '/storage/emulated/0/Download'

// Prefer mp4/m4a so the result plays everywhere, fall back to anything at or below the height.
export function formatFor(height, audioOnly) {
  if (audioOnly) return 'bestaudio/best'
  return `bv*[height<=${height}][ext=mp4]+ba[ext=m4a]/bv*[height<=${height}]+ba/b[height<=${height}]/b`
}
