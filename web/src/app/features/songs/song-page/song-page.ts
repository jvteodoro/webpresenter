import { HttpErrorResponse } from '@angular/common/http';
import { Component, DestroyRef, ElementRef, computed, effect, inject, input, signal, untracked, viewChild, viewChildren } from '@angular/core';
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
import { ConfirmDialog, ConfirmDialogData } from '../../../shared/dialogs/confirm-dialog';
import { SlideStyle } from '../../../core/events/event';
import { SlideStyleDialog, SlideStyleDialogData } from '../../slides/slide-style-dialog';
import { Job, isActive } from '../../../core/jobs/job';
import { JobsApi } from '../../../core/jobs/jobs-api';
import { JobsStream } from '../../../core/jobs/jobs-stream';
import { Lyrics, RECORDING_LABELS, Recording, SongDetail, inputOf } from '../../../core/songs/song';
import { SongsApi } from '../../../core/songs/songs-api';
import { DurationPipe } from '../../../shared/pipes/duration.pipe';
import { LyricsEditorData, LyricsEditorDialog } from '../lyrics-editor-dialog/lyrics-editor-dialog';
import { VideoPickerDialog } from '../video-picker-dialog';
import { MediaAsset } from '../../../core/media/media';
import { TrackMixer, trackGroups } from '../track-mixer/track-mixer';

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
    TrackMixer,
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
  private readonly jobsApi = inject(JobsApi);
  private readonly jobs = inject(JobsStream);

  /** The latest job of each kind for this song (playback generation, lyrics), newest first. */
  protected readonly songJobs = computed<Job[]>(() => {
    const latest = new Map<string, Job>();
    for (const j of this.jobs.jobs()) {
      if ((j.type === 'SEPARATE_PLAYBACK' || j.type === 'TRANSCRIBE_LYRICS' || j.type === 'SEPARATE_VOICES' || j.type === 'VIDEO_LYRICS')
          && j.params['songId'] === this.id()
          && !latest.has(j.type)) {
        latest.set(j.type, j);
      }
    }
    return [...latest.values()];
  });
  protected readonly generating = computed(() => this.songJobs().some((j) => j.type === 'SEPARATE_PLAYBACK' && isActive(j)));
  protected readonly separatingVoices = computed(() => this.songJobs().some((j) => j.type === 'SEPARATE_VOICES' && isActive(j)));
  /** Recordings with separated tracks, played together by the track mixer. */
  protected readonly trackGroups = computed(() => {
    const s = this.song();
    return s ? trackGroups(s) : [];
  });
  /** While the track mixer plays: the recording its tracks came from and the position. */
  protected readonly mixer = signal<{ sourceId: string; positionMs: number } | null>(null);
  /** Lyrics are being made, from the recording or from a video. */
  protected readonly transcribing = computed(() =>
    this.songJobs().some((j) => (j.type === 'TRANSCRIBE_LYRICS' || j.type === 'VIDEO_LYRICS') && isActive(j)));
  /** The song has real lyrics (not only the instrumental placeholder of a download). */
  protected readonly hasLyrics = computed(() => (this.song()?.slideSequence ?? []).some((s) => s.lines.length > 0));
  /** Generations seen in progress on this page; when one of them is done the song is reloaded. */
  private readonly followed = new Set<string>();

  private readonly audio = viewChild<ElementRef<HTMLAudioElement>>('audio');
  private readonly mixers = viewChildren(TrackMixer);

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
  /** Slide on screen at the current audio position (of the recording, or of the track mixer), according to the timing. */
  protected readonly activeSlide = computed(() => {
    const mixer = this.mixer();
    const timed = mixer ? this.song()?.recordings.find((r) => r.id === mixer.sourceId) : this.recording();
    const position = mixer ? mixer.positionMs : this.positionMs();
    const cues = timed?.timing?.cues ?? [];
    let active = -1;
    for (const c of cues) {
      if (c.startMs <= position) {
        active = c.slide;
      }
    }
    return active;
  });

  constructor() {
    this.jobs.attach();
    this.destroyRef.onDestroy(() => this.jobs.detach());
    // When a generation finishes (playback, lyrics), the song is reloaded to show its result.
    effect(() => {
      let finished = false;
      for (const job of this.songJobs()) {
        if (isActive(job)) {
          this.followed.add(job.id);
        } else if (this.followed.delete(job.id) && job.status === 'DONE') {
          finished = true;
        }
      }
      if (finished) {
        untracked(() => this.load(this.id()));
      }
    });
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

  protected generatePlayback(recording: Recording): void {
    this.jobsApi.separatePlayback(this.id(), recording.id).subscribe({
      next: (job) => {
        this.jobs.apply(job);
        this.snackBar.open('Geração do playback na fila. Isso pode levar alguns minutos.', 'OK', { duration: 5000 });
      },
      error: (e: HttpErrorResponse) =>
        this.snackBar.open(e.error?.detail ?? `Não foi possível gerar (${e.status}).`, 'OK', { duration: 5000 }),
    });
  }

  /** The song's own slide look: its background (as imported or chosen), text colour and size. */
  protected editStyle(): void {
    const song = this.song();
    if (!song) {
      return;
    }
    this.dialog
      .open<SlideStyleDialog, SlideStyleDialogData, SlideStyle>(SlideStyleDialog, {
        data: {
          title: 'Aparência dos slides desta música',
          current: { backgroundMediaId: song.background?.id ?? null, textColor: song.textColor, textScale: song.textScale },
          currentBackgroundUrl: this.backgroundUrl(),
        },
      })
      .afterClosed()
      .pipe(
        filter((style): style is SlideStyle => !!style),
        switchMap((style) => this.api.update(song.id, inputOf(song, {
          backgroundMediaId: style.backgroundMediaId,
          textColor: style.textColor,
          textScale: style.textScale,
        }))),
      )
      .subscribe({
        next: () => {
          this.load(song.id);
          this.snackBar.open('Aparência salva. Vale onde o evento ou o item não definirem outra.', 'OK', { duration: 4000 });
        },
        error: (e: HttpErrorResponse) =>
          this.snackBar.open(e.error?.detail ?? `Não foi possível salvar (${e.status}).`, 'OK', { duration: 5000 }),
      });
  }

  /** Transcribes again from the recording, replacing the current (draft) lyrics, after confirmation. */
  protected transcribeAgain(recording: Recording): void {
    this.dialog
      .open<ConfirmDialog, ConfirmDialogData, boolean>(ConfirmDialog, {
        data: {
          title: 'Gerar a letra de novo',
          message: 'A letra atual será substituída pela transcrição da gravação, e os tempos refeitos. Correções feitas à mão serão perdidas.',
          confirmLabel: 'Gerar de novo',
          destructive: true,
        },
      })
      .afterClosed()
      .pipe(filter(Boolean))
      .subscribe(() => this.transcribe(recording, true));
  }

  protected transcribe(recording: Recording, fresh = false): void {
    this.jobsApi.transcribeLyrics(this.id(), recording.id, fresh).subscribe({
      next: (job) => {
        this.jobs.apply(job);
        this.snackBar.open(
          this.hasLyrics() && !fresh ? 'Sincronização da letra na fila.' : 'Geração da letra e dos tempos na fila. Isso leva alguns minutos.',
          'OK', { duration: 5000 });
      },
      error: (e: HttpErrorResponse) =>
        this.snackBar.open(e.error?.detail ?? `Não foi possível gerar (${e.status}).`, 'OK', { duration: 5000 }),
    });
  }

  /** Reads the lyrics and slide times from the on-screen text of a video chosen from the library. */
  protected extractFromVideo(recording: Recording): void {
    this.dialog
      .open<VideoPickerDialog, void, MediaAsset>(VideoPickerDialog, { width: '34rem', maxWidth: '94vw' })
      .afterClosed()
      .pipe(filter((video): video is MediaAsset => !!video))
      .subscribe((video) => {
        this.jobsApi.extractLyricsFromVideo(this.id(), recording.id, video.id, false).subscribe({
          next: (job) => {
            this.jobs.apply(job);
            this.snackBar.open('Leitura da letra do vídeo na fila. Isso leva alguns minutos.', 'OK', { duration: 5000 });
          },
          error: (e: HttpErrorResponse) =>
            this.snackBar.open(e.error?.detail ?? `Não foi possível ler o vídeo (${e.status}).`, 'OK', { duration: 5000 }),
        });
      });
  }

  protected onMixerPosition(sourceId: string, positionMs: number | null): void {
    this.mixer.set(positionMs === null ? null : { sourceId, positionMs });
    if (positionMs !== null) {
      this.audio()?.nativeElement.pause();
    }
  }

  /** The compact bar in the (sticky) player pauses the separated tracks wherever the page is scrolled. */
  protected stopMixer(): void {
    this.mixers().forEach((m) => m.stop());
  }

  protected separateVoices(recording: Recording): void {
    this.jobsApi.separateVoices(this.id(), recording.id).subscribe({
      next: (job) => {
        this.jobs.apply(job);
        this.snackBar.open('Separação dos naipes na fila.', 'OK', { duration: 5000 });
      },
      error: (e: HttpErrorResponse) =>
        this.snackBar.open(e.error?.detail ?? `Não foi possível separar (${e.status}).`, 'OK', { duration: 5000 }),
    });
  }

  protected jobLabel(job: Job): string {
    return job.type === 'SEPARATE_PLAYBACK' ? 'faixas separadas'
      : job.type === 'SEPARATE_VOICES' ? 'naipes'
      : job.type === 'VIDEO_LYRICS' ? 'letra pelo vídeo' : 'letra e tempos';
  }

  private load(id: string): void {
    this.api
      .get(id)
      .pipe(takeUntilDestroyed(this.destroyRef))
      .subscribe({
        next: (song) => {
          this.song.set(song);
          this.loadError.set(false);
          const first = song.recordings.find((r) => r.kind === 'SUNG') ?? song.recordings.find((r) => r.kind !== 'STEM');
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
