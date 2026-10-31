// Android's back button, resolved in one place. Whatever sits on top of the screen gets to
// close first, then the router walks back to the queue, and only the queue itself can exit —
// either through the "exit?" confirm, or the "pause your downloads first?" confirm when work
// is still in flight. Pure and argument-driven so the order is easy to reason about and test.
//
// Actions:
//   'lock'         — the lock screen is up: back does nothing (relaunching locks again anyway)
//   'drawer'       — the navigation drawer slides shut
//   'sheet'        — the share-intake sheet is dismissed
//   'exit-sheet'   — the exit / pause confirm is dismissed
//   'history'      — a deep screen (/add, /download/:id) steps back, or falls back to the queue
//   'root'         — a section tab (/completed, /settings…) returns to the queue
//   'ask-pause'    — the queue has live work: ask before it is abandoned
//   'ask-exit'     — the queue is idle: ask to confirm leaving the app
export function nextBackAction({ locked, drawer, sheet, exitSheet, section, path, active }) {
  if (locked) return 'lock'
  if (drawer) return 'drawer'
  if (sheet) return 'sheet'
  if (exitSheet) return 'exit-sheet'
  if (!section) return 'history'
  if (path !== '/') return 'root'
  return active > 0 ? 'ask-pause' : 'ask-exit'
}
