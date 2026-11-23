import { Component, DestroyRef, computed, effect, inject, input, output, signal, untracked } from '@angular/core';
import { MatButtonModule } from '@angular/material/button';
import { MatIconModule } from '@angular/material/icon';
import { MatSliderModule } from '@angular/material/slider';
import { MatTooltipModule } from '@angular/material/tooltip';
import { forkJoin } from 'rxjs';
import { RECORDING_LABELS, Recording, STEM_LABELS, SongDetail, StemPart } from '../../../core/songs/song';
import { SongsApi } from '../../../core/songs/songs-api';

/** A recording with the tracks separated from it (same timeline). */
export interface TrackGroup {
  source: Recording;
  tracks: Recording[];
}

/** The recordings that have separated tracks, each with its tracks (instruments first, then voices). */
export function trackGroups(song: SongDetail): TrackGroup[] {
  const order: StemPart[] = ['VOCALS', 'DRUMS', 'BASS', 'OTHER', 'LEAD', 'SOPRANO', 'ALTO', 'TENOR', 'BASS_VOICE', 'VOCAL_PERCUSSION'];
  const byId = new Map(song.recordings.map((r) => [r.id, r]));
  // Voices are separated from the vocals track: they belong to the vocals' own source.
  const root = (r: Recording): Recording => {
    let current = r;
    for (let i = 0; i < 5 && current.derivedFrom && byId.has(current.derivedFrom); i++) {
      current = byId.get(current.derivedFrom)!;
    }
    return current;
  };
  const groups = new Map<string, TrackGroup>();
  for (const r of song.recordings) {
    if (r.kind !== 'STEM' && !(r.kind === 'PLAYBACK' && r.derivedFrom)) {
      continue;
    }
    const source = root(r);
    if (source === r) {
      continue;
    }
    const group = groups.get(source.id) ?? { source, tracks: [] };
    group.tracks.push(r);
    groups.set(source.id, group);
  }
  const rank = (r: Recording) => (r.kind === 'PLAYBACK' ? -1 : order.indexOf(r.part!));
  return [...groups.values()]
    .filter((g) => g.tracks.some((t) => t.kind === 'STEM'))
    .map((g) => ({ ...g, tracks: g.tracks.sort((a, b) => rank(a) - rank(b)) }));
}

interface Channel {
  recording: Recording;
  label: string;
  element: HTMLAudioElement;
  gain: GainNode | null;
}

/**
 * Plays a recording's separated tracks together, outside any event: each track can be muted,
 * soloed or set to its own level (to rehearse one voice, or sing over the others).
 */
@Component({
  selector: 'app-track-mixer',
  imports: [MatButtonModule, MatIconModule, MatSliderModule, MatTooltipModule],
  template: `
    <div class="transport">
      <button mat-icon-button (click)="toggle()" [disabled]="!ready()" [attr.aria-label]="playing() ? 'Pausar faixas' : 'Tocar faixas'">
        <mat-icon>{{ playing() ? 'pause' : 'play_arrow' }}</mat-icon>
      </button>
      <input type="range" class="seek" min="0" [max]="durationMs()" step="100" [value]="positionMs()"
             (input)="seek(+$any($event.target).value)" aria-label="Posição" [disabled]="!ready()" />
      <span class="time">{{ clock(positionMs()) }} / {{ clock(durationMs()) }}</span>
    </div>
    <div class="presets">
      <button mat-button (click)="preset('all')">Tudo</button>
      @if (hasPart('VOCALS')) {
        <button mat-button (click)="preset('instruments')">Sem voz</button>
        <button mat-button (click)="preset('vocals')">Só voz</button>
      }
      <button mat-button (click)="preset('original')">Original</button>
    </div>
    <ul class="channels" aria-label="Faixas">
      @for (c of channels(); track c.recording.id; let i = $index) {
        <li [class.silent]="!audible(i)">
          <span class="label">{{ c.label }}</span>
          <button mat-icon-button (click)="toggleMute(i)" [class.on]="muted()[i]"
                  [attr.aria-label]="(muted()[i] ? 'Ativar ' : 'Silenciar ') + c.label" [matTooltip]="muted()[i] ? 'Ativar' : 'Silenciar'">
            <mat-icon>{{ muted()[i] ? 'volume_off' : 'volume_up' }}</mat-icon>
          </button>
          <button mat-icon-button (click)="toggleSolo(i)" [class.on]="solo() === i"
                  [attr.aria-label]="'Solo ' + c.label" matTooltip="Ouvir só esta faixa">
            <mat-icon>{{ solo() === i ? 'headphones' : 'hearing' }}</mat-icon>
          </button>
          <mat-slider min="0" max="100" step="1" class="level">
            <input matSliderThumb [value]="levels()[i]" (valueChange)="setLevel(i, $event)" [attr.aria-label]="'Volume ' + c.label" />
          </mat-slider>
          @if (c.recording.part === 'VOCALS' && canSeparateVoices()) {
            <button mat-stroked-button (click)="separateVoices.emit(c.recording)" [disabled]="separatingVoices()">
              <mat-icon>groups</mat-icon> Separar naipes
            </button>
          }
        </li>
      }
    </ul>
  `,
  styles: `
    :host { display: block; }
    .transport { display: flex; align-items: center; gap: 8px; }
    .seek { flex: 1; min-width: 0; }
    .time { font-variant-numeric: tabular-nums; white-space: nowrap; }
    .presets { display: flex; flex-wrap: wrap; gap: 4px; margin: 4px 0; }
    .channels { list-style: none; margin: 0; padding: 0; }
    .channels li { display: flex; align-items: center; gap: 4px; flex-wrap: wrap; padding: 2px 0; }
    .channels li.silent .label { opacity: 0.5; }
    .label { width: 9.5rem; font-weight: 500; }
    .level { flex: 1; min-width: 120px; }
    button.on { color: var(--mat-sys-primary); }
  `,
})
export class TrackMixer {
  readonly group = input.required<TrackGroup>();
  readonly canSeparateVoices = input(false);
  readonly separatingVoices = input(false);
  /** Current position while playing (ms), or null when stopped: the page highlights the lyrics. */
  readonly position = output<number | null>();
  readonly separateVoices = output<Recording>();

  private readonly api = inject(SongsApi);

  protected readonly channels = signal<Channel[]>([]);
  protected readonly ready = signal(false);
  protected readonly playing = signal(false);
  protected readonly positionMs = signal(0);
  protected readonly durationMs = computed(() => this.group().source.media.durationMs ?? 0);
  protected readonly muted = signal<boolean[]>([]);
  protected readonly levels = signal<number[]>([]);
  protected readonly solo = signal<number | null>(null);

  private context: AudioContext | null = null;
  private syncTimer: ReturnType<typeof setInterval> | null = null;

  constructor() {
    effect(() => {
      const group = this.group();
      untracked(() => this.load(group));
    });
    inject(DestroyRef).onDestroy(() => this.dispose());
  }

  protected hasPart(part: StemPart): boolean {
    return this.channels().some((c) => c.recording.part === part);
  }

  protected audible(i: number): boolean {
    const solo = this.solo();
    return solo !== null ? solo === i : !this.muted()[i] && this.levels()[i] > 0;
  }

  protected toggle(): void {
    if (this.playing()) {
      this.pause();
    } else {
      this.play();
    }
  }

  protected seek(ms: number): void {
    for (const c of this.channels()) {
      c.element.currentTime = ms / 1000;
    }
    this.positionMs.set(ms);
    if (this.playing()) {
      this.position.emit(ms);
    }
  }

  protected toggleMute(i: number): void {
    this.muted.update((m) => m.map((v, j) => (j === i ? !v : v)));
    this.applyLevels();
  }

  protected toggleSolo(i: number): void {
    this.solo.update((s) => (s === i ? null : i));
    this.applyLevels();
  }

  protected setLevel(i: number, value: number): void {
    this.levels.update((l) => l.map((v, j) => (j === i ? value : v)));
    this.applyLevels();
  }

  /** Quick mixes: every separated track, only instruments, only the voice, or the original recording. */
  protected preset(name: 'all' | 'instruments' | 'vocals' | 'original'): void {
    const voices = new Set<StemPart>(['VOCALS', 'LEAD', 'SOPRANO', 'ALTO', 'TENOR', 'BASS_VOICE', 'VOCAL_PERCUSSION']);
    const individualVoices = this.channels().some((c) => c.recording.part && c.recording.part !== 'VOCALS' && voices.has(c.recording.part));
    this.solo.set(null);
    this.muted.set(this.channels().map((c, i) => {
      if (i === 0) {
        return name !== 'original'; // the original recording
      }
      if (name === 'original') {
        return true;
      }
      if (c.recording.kind === 'PLAYBACK') {
        return true; // the sum of the instruments: the stems already play them
      }
      const voice = !!c.recording.part && voices.has(c.recording.part);
      // With the voices separated, the full vocals track would double them.
      const doubled = c.recording.part === 'VOCALS' && individualVoices;
      return name === 'all' ? doubled : name === 'instruments' ? voice : !voice || doubled;
    }));
    this.applyLevels();
  }

  protected clock(ms: number): string {
    const s = Math.floor(ms / 1000);
    return `${Math.floor(s / 60)}:${String(s % 60).padStart(2, '0')}`;
  }

  private load(group: TrackGroup): void {
    this.dispose();
    const recordings = [group.source, ...group.tracks];
    forkJoin(recordings.map((r) => this.api.mediaUrl(r.media.id))).subscribe((links) => {
      const channels = recordings.map((recording, i) => {
        const element = new Audio();
        element.preload = 'auto';
        element.src = links[i].url;
        const label = i === 0 ? `Original (${RECORDING_LABELS[recording.kind].toLowerCase()})`
          : recording.kind === 'PLAYBACK' ? 'Playback' : STEM_LABELS[recording.part!];
        return { recording, label, element, gain: null } as Channel;
      });
      channels[0].element.addEventListener('timeupdate', () => {
        if (this.playing()) {
          const ms = Math.round(channels[0].element.currentTime * 1000);
          this.positionMs.set(ms);
          this.position.emit(ms);
        }
      });
      channels[0].element.addEventListener('ended', () => this.pause());
      this.channels.set(channels);
      this.levels.set(channels.map(() => 100));
      this.muted.set(channels.map(() => false));
      this.preset('all');
      this.ready.set(true);
    });
  }

  private play(): void {
    const channels = this.channels();
    this.connect(channels);
    void this.context?.resume();
    const start = channels[0].element.currentTime;
    for (const c of channels) {
      c.element.currentTime = start;
      void c.element.play();
    }
    this.playing.set(true);
    // Keeps the tracks together: a track that drifts from the original is moved back to it.
    this.syncTimer = setInterval(() => {
      const t = channels[0].element.currentTime;
      for (const c of channels.slice(1)) {
        if (Math.abs(c.element.currentTime - t) > 0.08) {
          c.element.currentTime = t;
        }
      }
    }, 500);
  }

  private pause(): void {
    for (const c of this.channels()) {
      c.element.pause();
    }
    this.playing.set(false);
    this.position.emit(null);
    if (this.syncTimer) {
      clearInterval(this.syncTimer);
      this.syncTimer = null;
    }
  }

  /**
   * Levels go through Web Audio (phones ignore an audio element's volume) when the media comes from
   * this site; otherwise through each element's volume.
   */
  private connect(channels: Channel[]): void {
    if (this.context || typeof AudioContext === 'undefined') {
      return;
    }
    const sameOrigin = channels.every((c) => new URL(c.element.src, location.href).origin === location.origin);
    if (!sameOrigin) {
      return;
    }
    this.context = new AudioContext();
    for (const c of channels) {
      c.gain = this.context.createGain();
      this.context.createMediaElementSource(c.element).connect(c.gain).connect(this.context.destination);
    }
    this.applyLevels();
  }

  private applyLevels(): void {
    this.channels().forEach((c, i) => {
      const level = this.audible(i) ? this.levels()[i] / 100 : 0;
      const value = level * level; // perceptual: like a fader on a desk
      if (c.gain) {
        c.gain.gain.value = value;
        c.element.volume = 1;
      } else {
        c.element.volume = value;
      }
    });
  }

  private dispose(): void {
    this.pause();
    for (const c of this.channels()) {
      c.element.removeAttribute('src');
      c.element.load();
    }
    this.channels.set([]);
    this.ready.set(false);
    void this.context?.close();
    this.context = null;
  }
}
