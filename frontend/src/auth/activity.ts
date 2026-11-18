const ACTIVE_WINDOW_MS = 5 * 60_00;
const SIGNS_OF_LIFE = ['mousemove', 'keydown', 'click', 'touchstart', 'scroll'] as const;

let lastActivity = Date.now();
let started = false;
const wakeListeners = new Set<() => void>();

function mark() {
  const idleFor = Date.now() - lastActivity;
  lastActivity = Date.now();
  if (idleFor >= ACTIVE_WINDOW_MS) wakeListeners.forEach((listener) => listener());
}

function start() {
  if (started) return;
  started = true;
  for (const name of SIGNS_OF_LIFE) window.addEventListener(name, mark, { passive: true });
  window.addEventListener('focus', mark);
}

export function recentlyActive(): boolean {
  start();
  return Date.now() - lastActivity < ACTIVE_WINDOW_MS;
}

export function onWake(listener: () => void): () => void {
  start();
  wakeListeners.add(listener);
  return () => {
    wakeListeners.delete(listener);
  };
}
