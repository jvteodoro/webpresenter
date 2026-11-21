import { HttpErrorResponse } from '@angular/common/http';
import { Component, OnDestroy, OnInit, computed, inject, signal } from '@angular/core';
import { MatButtonModule } from '@angular/material/button';
import { MatIconModule } from '@angular/material/icon';
import { MatProgressBarModule } from '@angular/material/progress-bar';
import { MatSnackBar } from '@angular/material/snack-bar';
import { MatTooltipModule } from '@angular/material/tooltip';
import { RouterLink } from '@angular/router';
import { CurrentUser } from '../../core/auth/current-user';
import { LiveAction, LiveItem, LivePlayer, isTimeBased } from '../../core/live/live';
import { LiveApi } from '../../core/live/live-api';
import { LiveStream } from '../../core/live/live-stream';
import { MEDIA_KIND_ICONS } from '../../core/media/media';
import { DurationPipe } from '../../shared/pipes/duration.pipe';

const STATUS_LABELS = {
  REST: 'Tela de descanso',
  PLAYING: 'Tocando',
  PAUSED: 'Pausado',
  SHOWING: 'No telão',
} as const;

const PLAYBACK_LABELS: Record<string, string> = {
  LOADING: 'Carregando…',
  PLAYING: 'Tocando',
  PAUSED: 'Pausado',
  SHOWING: 'Exibindo',
  ENDED: 'Terminou',
  REST: 'Tela de descanso',
  FAILED: 'Falhou',
};

/**
 * Live presentation: what is on the outputs, what comes next, and the controls. Everyone can
 * follow it (musicians see what is next); leaders, AV technicians and admins control it, also
 * with the keyboard or a presentation clicker (PageDown/PageUp).
 */
@Component({
  selector: 'app-live-page',
  imports: [RouterLink, MatButtonModule, MatIconModule, MatProgressBarModule, MatTooltipModule, DurationPipe],
  templateUrl: './live-page.html',
  styleUrl: './live-page.scss',
  host: { '(document:keydown)': 'onKey($event)' },
})
export class LivePage implements OnInit, OnDestroy {
  private readonly stream = inject(LiveStream);
  private readonly api = inject(LiveApi);
  private readonly snackBar = inject(MatSnackBar);
  protected readonly currentUser = inject(CurrentUser);

  protected readonly state = this.stream.state;
  protected readonly streamStatus = this.stream.status;
  protected readonly busy = signal(false);
  /** Ticks while media plays, to move the progress bar between server updates. */
  private readonly now = signal(Date.now());
  private ticker: ReturnType<typeof setInterval> | null = null;

  protected readonly canControl = computed(() => this.currentUser.canControlLive());
  protected readonly current = computed<LiveItem | null>(() => {
    const s = this.state();
    return s && s.currentIndex >= 0 && s.currentIndex < s.items.length ? s.items[s.currentIndex] : null;
  });
  protected readonly onScreen = computed(() => (this.state()?.status === 'REST' ? null : this.current()));
  protected readonly next = computed<LiveItem | null>(() => {
    const s = this.state();
    return s ? (s.items[s.currentIndex + 1] ?? null) : null;
  });
  protected readonly statusLabel = computed(() => STATUS_LABELS[this.state()?.status ?? 'REST']);
  protected readonly canPause = computed(() => isTimeBased(this.onScreen()));

  /** Position of the media on screen: the most advanced player playing it, moved forward locally. */
  protected readonly progress = computed(() => {
    const s = this.state();
    const item = this.onScreen();
    if (!s || !item || !isTimeBased(item)) {
      return null;
    }
    const reports = s.players.filter((p) => p.online && p.itemId === item.id && p.durationMs > 0);
    const best = reports.sort((a, b) => b.positionMs - a.positionMs)[0];
    const duration = best?.durationMs || item.durationMs || 0;
    if (!duration) {
      return null;
    }
    const elapsed = s.status === 'PLAYING' ? this.now() - this.stream.receivedAt() : 0;
    const position = Math.min(duration, (best?.positionMs ?? 0) + Math.max(0, elapsed));
    return { position, duration, percent: (100 * position) / duration };
  });

  ngOnInit(): void {
    this.stream.attach();
    this.ticker = setInterval(() => this.now.set(Date.now()), 250);
  }

  ngOnDestroy(): void {
    this.stream.detach();
    if (this.ticker) {
      clearInterval(this.ticker);
    }
  }

  protected icon(item: LiveItem | null): string {
    if (!item) {
      return 'wallpaper';
    }
    return item.type === 'SLIDE' ? 'text_fields' : item.kind ? MEDIA_KIND_ICONS[item.kind] : 'help';
  }

  protected playbackLabel(p: LivePlayer): string {
    if (!p.online) {
      return 'Offline';
    }
    return p.playback ? PLAYBACK_LABELS[p.playback] : 'Conectado';
  }

  protected send(action: LiveAction, itemId?: string): void {
    if (!this.canControl() || this.busy()) {
      return;
    }
    this.busy.set(true);
    this.api.command(action, itemId).subscribe({
      next: (state) => {
        this.stream.apply(state);
        this.busy.set(false);
      },
      error: (e: HttpErrorResponse) => {
        this.busy.set(false);
        this.snackBar.open(e.error?.detail ?? `Comando não executado (${e.status}).`, 'OK', { duration: 4000 });
      },
    });
  }

  protected togglePause(): void {
    this.send(this.state()?.status === 'PAUSED' ? 'RESUME' : 'PAUSE');
  }

  /** Keyboard and presentation clickers. */
  protected onKey(event: KeyboardEvent): void {
    if (!this.canControl() || !this.state()?.eventId || isTyping(event)) {
      return;
    }
    const action: Record<string, () => void> = {
      ArrowRight: () => this.send('NEXT'),
      PageDown: () => this.send('NEXT'),
      ' ': () => this.send('NEXT'),
      ArrowLeft: () => this.send('PREVIOUS'),
      PageUp: () => this.send('PREVIOUS'),
      Escape: () => this.send('REST'),
      b: () => this.send('REST'),
      p: () => this.canPause() && this.togglePause(),
      k: () => this.canPause() && this.togglePause(),
    };
    const run = action[event.key];
    if (run) {
      event.preventDefault();
      run();
    }
  }
}

function isTyping(event: KeyboardEvent): boolean {
  const target = event.target as HTMLElement | null;
  return !!target && (target.isContentEditable || ['INPUT', 'TEXTAREA', 'SELECT'].includes(target.tagName));
}
