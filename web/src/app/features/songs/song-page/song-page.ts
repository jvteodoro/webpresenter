import { HttpErrorResponse } from '@angular/common/http';
import { Component, DestroyRef, ElementRef, computed, effect, inject, input, signal, viewChild } from '@angular/core';
import { takeUntilDestroyed } from '@angular/core/rxjs-interop';
import { MatButtonModule } from '@angular/material/button';
import { MatButtonToggleModule } from '@angular/material/button-toggle';
import { MatDialog } from '@angular/material/dialog';
import { MatIconModule } from '@angular/material/icon';
import { MatProgressBarModule } from '@angular/material/progress-bar';
import { MatSnackBar } from '@angular/material/snack-bar';
import { MatTooltipModule } from '@angular/material/tooltip';
import { RouterLink } from '@angular/router';
import { filter, switchMap } from 'rxjs';
import { CurrentUser } from '../../../core/auth/current-user';
import { Lyrics, RECORDING_LABELS, Recording, SongDetail, inputOf } from '../../../core/songs/song';
import { SongsApi } from '../../../core/songs/songs-api';
import { DurationPipe } from '../../../shared/pipes/duration.pipe';
import { LyricsEditorData, LyricsEditorDialog } from '../lyrics-editor-dialog/lyrics-editor-dialog';

/**
 * A song: collections, recordings and lyrics. Playing a recording highlights the slide its timing
 * says is on screen, which is how imported or generated timing is reviewed.
 */
@Component({
  selector: 'app-song-page',
  imports: [
    RouterLink,
    MatButtonModule,
    MatButtonToggleModule,
    MatIconModule,
    MatProgressBarModule,
    MatTooltipModule,
    DurationPipe,
  ],
  templateUrl: './song-page.html',
  styleUrl: './song-page.scss',
})
export class SongPage {
  readonly id = input.required<string>();

  private readonly api = inject(SongsApi);
  private readonly dialog = inject(MatDialog);
  private readonly snackBar = inject(MatSnackBar);
  private readonly destroyRef = inject(DestroyRef);
  protected readonly currentUser = inject(CurrentUser);
  protected readonly recordingLabels = RECORDING_LABELS;

  private readonly audio = viewChild<ElementRef<HTMLAudioElement>>('audio');

  protected readonly song = signal<SongDetail | null>(null);
  protected readonly loadError = signal(false);
  protected readonly selectedRecordingId = signal<string | null>(null);
  protected readonly audioUrl = signal<string | null>(null);
  protected readonly positionMs = signal(0);
  protected readonly backgroundUrl = signal<string | null>(null);

  protected readonly recording = computed<Recording | null>(() => {
    const s = this.song();
    return s?.recordings.find((r) => r.id === this.selectedRecordingId()) ?? null;
  });
  /** Start time of each slide in the selected recording. */
  protected readonly cueBySlide = computed(() => new Map((this.recording()?.timing?.cues ?? []).map((c) => [c.slide, c.startMs])));
  /** Slide on screen at the current audio position, according to the timing. */
  protected readonly activeSlide = computed(() => {
    const cues = this.recording()?.timing?.cues ?? [];
    let active = -1;
    for (const c of cues) {
      if (c.startMs <= this.positionMs()) {
        active = c.slide;
      }
    }
    return active;
  });

  constructor() {
    effect(() => this.load(this.id()));
    // Keep the highlighted slide in view while the recording plays.
    effect(() => {
      const index = this.activeSlide();
      if (index >= 0) {
        document.getElementById(`slide-${index}`)?.scrollIntoView({ block: 'nearest', behavior: 'smooth' });
      }
    });
  }

  protected selectRecording(id: string): void {
    const recording = this.song()?.recordings.find((r) => r.id === id);
    if (!recording) {
      return;
    }
    this.selectedRecordingId.set(id);
    this.positionMs.set(0);
    this.audioUrl.set(null);
    this.api.mediaUrl(recording.media.id).subscribe((link) => this.audioUrl.set(link.url));
  }

  protected onTime(): void {
    const el = this.audio()?.nativeElement;
    if (el) {
      this.positionMs.set(Math.round(el.currentTime * 1000));
    }
  }

  /** Clicking a timed slide seeks the recording there. */
  protected seekTo(slide: number): void {
    const start = this.cueBySlide().get(slide);
    const el = this.audio()?.nativeElement;
    if (start !== undefined && el) {
      el.currentTime = start / 1000;
      void el.play();
    }
  }

  protected editLyrics(): void {
    const s = this.song();
    if (!s) {
      return;
    }
    this.dialog
      .open<LyricsEditorDialog, LyricsEditorData, Lyrics>(LyricsEditorDialog, {
        data: { title: s.title, lyrics: s.lyrics, hasTiming: s.recordings.some((r) => !!r.timing) },
        maxWidth: '95vw',
      })
      .afterClosed()
      .pipe(
        filter((lyrics): lyrics is Lyrics => !!lyrics),
        switchMap((lyrics) => this.api.update(s.id, inputOf(s, { lyrics }))),
      )
      .subscribe({
        next: (updated) => {
          this.song.set(updated);
          this.snackBar.open('Letra salva.', 'OK', { duration: 3000 });
        },
        error: (e: HttpErrorResponse) =>
          this.snackBar.open(e.error?.detail ?? `Não foi possível salvar (${e.status}).`, 'OK', { duration: 5000 }),
      });
  }

  private load(id: string): void {
    this.api
      .get(id)
      .pipe(takeUntilDestroyed(this.destroyRef))
      .subscribe({
        next: (song) => {
          this.song.set(song);
          this.loadError.set(false);
          const first = song.recordings.find((r) => r.kind === 'SUNG') ?? song.recordings[0];
          if (first && !this.selectedRecordingId()) {
            this.selectRecording(first.id);
          }
          if (song.background) {
            this.api.mediaUrl(song.background.id).subscribe((link) => this.backgroundUrl.set(link.url));
          }
        },
        error: () => this.loadError.set(true),
      });
  }
}
