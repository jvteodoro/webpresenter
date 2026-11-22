/** What a TIMER item counts down to (as the server sends it). */
export interface TimerInfo {
  /** Local time "HH:mm[:ss]" or null. */
  targetTime: string | null;
  /** From when the item started, or null. Neither: it only counts up. */
  durationMs: number | null;
  signalsBeforeEndMs: number[];
}

/** When the timer reaches zero, in local time (like the players); null when it only counts up. */
export function timerTarget(timer: TimerInfo, itemStartedAt: number): number | null {
  if (timer.targetTime) {
    const [h, m, s] = timer.targetTime.split(':').map(Number);
    const day = new Date(itemStartedAt);
    day.setHours(h, m, s || 0, 0);
    return day.getTime();
  }
  return timer.durationMs != null ? itemStartedAt + timer.durationMs : null;
}

/** "4:59", "1:02:03" before the target (rounded up); "+0:07" after it — as on the players. */
export function countdown(remainingMs: number): string {
  const over = remainingMs < 0;
  const seconds = over ? Math.floor(-remainingMs / 1000) : Math.ceil(remainingMs / 1000);
  const h = Math.floor(seconds / 3600);
  const m = Math.floor((seconds % 3600) / 60);
  const s = seconds % 60;
  const text = h > 0 ? `${h}:${String(m).padStart(2, '0')}:${String(s).padStart(2, '0')}` : `${m}:${String(s).padStart(2, '0')}`;
  return over ? `+${text}` : text;
}

export function clockText(now: number): string {
  const d = new Date(now);
  return `${String(d.getHours()).padStart(2, '0')}:${String(d.getMinutes()).padStart(2, '0')}`;
}
