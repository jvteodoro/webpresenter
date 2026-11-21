import { CdkDrag, CdkDragDrop, CdkDragHandle, CdkDropList, moveItemInArray } from '@angular/cdk/drag-drop';
import { DatePipe } from '@angular/common';
import { HttpErrorResponse } from '@angular/common/http';
import { Component, DestroyRef, computed, effect, inject, input, signal } from '@angular/core';
import { takeUntilDestroyed } from '@angular/core/rxjs-interop';
import { MatButtonModule } from '@angular/material/button';
import { MatDialog } from '@angular/material/dialog';
import { MatIconModule } from '@angular/material/icon';
import { MatMenuModule } from '@angular/material/menu';
import { MatProgressBarModule } from '@angular/material/progress-bar';
import { MatSnackBar } from '@angular/material/snack-bar';
import { MatTooltipModule } from '@angular/material/tooltip';
import { Router, RouterLink } from '@angular/router';
import { Observable, concatMap, filter, from, last, switchMap, timer } from 'rxjs';
import { CurrentUser } from '../../../core/auth/current-user';
import { EventDetail, EventInput, EventItem, Readiness, SlideInput } from '../../../core/events/event';
import { EventsApi } from '../../../core/events/events-api';
import { MEDIA_KIND_ICONS } from '../../../core/media/media';
import { ConfirmDialog, ConfirmDialogData } from '../../../shared/dialogs/confirm-dialog';
import { DurationPipe } from '../../../shared/pipes/duration.pipe';
import { FileSizePipe } from '../../../shared/pipes/file-size.pipe';
import { EventDialog, EventDialogData } from '../dialogs/event-dialog';
import { MediaPickerDialog } from '../dialogs/media-picker-dialog';
import { SlideDialog } from '../dialogs/slide-dialog';
import { SongChoice, SongPickerDialog } from '../dialogs/song-picker-dialog';

const READINESS_REFRESH_MS = 2000;

/** Builds the order of an event and prepares the players for it. */
@Component({
  selector: 'app-event-editor-page',
  imports: [
    CdkDropList,
    CdkDrag,
    CdkDragHandle,
    DatePipe,
    RouterLink,
    MatButtonModule,
    MatIconModule,
    MatMenuModule,
    MatProgressBarModule,
    MatTooltipModule,
    DurationPipe,
    FileSizePipe,
  ],
  templateUrl: './event-editor-page.html',
  styleUrl: './event-editor-page.scss',
})
export class EventEditorPage {
  /** Route parameter. */
  readonly id = input.required<string>();

  private readonly api = inject(EventsApi);
  private readonly dialog = inject(MatDialog);
  private readonly snackBar = inject(MatSnackBar);
  private readonly router = inject(Router);
  private readonly destroyRef = inject(DestroyRef);
  protected readonly currentUser = inject(CurrentUser);

  protected readonly event = signal<EventDetail | null>(null);
  protected readonly loadError = signal(false);
  protected readonly saving = signal(false);
  protected readonly readiness = signal<Readiness[] | null>(null);

  protected readonly canEdit = computed(() => this.currentUser.canEditEvents());
  protected readonly totalDurationMs = computed(() =>
    (this.event()?.items ?? []).reduce((sum, item) => sum + (item.media?.durationMs ?? item.song?.durationMs ?? 0), 0),
  );
  protected readonly recordingLabels: Record<string, string> = { SUNG: 'Cantado', PLAYBACK: 'Playback', OTHER: 'Outra gravação' };
  /** Players ready for this event, among the online ones. */
  protected readonly readyCount = computed(() => {
    const id = this.event()?.id;
    return (this.readiness() ?? []).filter((r) => r.online && isReady(r, id)).length;
  });
  protected readonly onlineCount = computed(() => (this.readiness() ?? []).filter((r) => r.online).length);

  constructor() {
    effect(() => this.load(this.id()));
    timer(0, READINESS_REFRESH_MS)
      .pipe(
        filter(() => this.canEdit() && !!this.event()?.current),
        switchMap(() => this.api.readiness()),
        takeUntilDestroyed(),
      )
      .subscribe({ next: (r) => this.readiness.set(r), error: () => this.readiness.set(null) });
  }

  // ------------------------------------------------------------------ display helpers

  protected icon(item: EventItem): string {
    if (item.type === 'SONG') {
      return 'queue_music';
    }
    return item.type === 'SLIDE' ? 'text_fields' : item.media?.kind ? MEDIA_KIND_ICONS[item.media.kind] : 'help';
  }

  /** "Cantado · letra sincronizada", "Sem áudio · avanço manual". */
  protected songMode(item: EventItem): string {
    const song = item.song;
    if (!song) {
      return '';
    }
    if (!song.recordingKind) {
      return 'Sem áudio · avanço manual';
    }
    return `${this.recordingLabels[song.recordingKind]} · ${song.timed ? 'letra sincronizada' : 'avanço manual'}`;
  }

  /** One-line preview of a slide's text. */
  protected preview(body: string): string {
    return body.split(/\r?\n/).map((l) => l.trim()).filter(Boolean).join(' · ');
  }

  protected isReady(r: Readiness): boolean {
    return isReady(r, this.event()?.id);
  }

  protected percent(r: Readiness): number {
    const c = r.cacheStatus;
    return c && c.bytesTotal > 0 ? Math.round((100 * c.bytesReady) / c.bytesTotal) : 0;
  }

  protected forThisEvent(r: Readiness): boolean {
    return r.cacheStatus?.eventId === this.event()?.id;
  }

  // ------------------------------------------------------------------ actions

  protected drop(drop: CdkDragDrop<EventItem[]>): void {
    const current = this.event();
    if (!current || drop.previousIndex === drop.currentIndex) {
      return;
    }
    const items = [...current.items];
    moveItemInArray(items, drop.previousIndex, drop.currentIndex);
    this.event.set({ ...current, items }); // optimistic; the server answer replaces it
    this.run(this.api.reorder(current.id, items.map((i) => i.id)), 'reordenar');
  }

  protected addMedia(): void {
    const id = this.id();
    this.dialog
      .open<MediaPickerDialog, void, string[]>(MediaPickerDialog)
      .afterClosed()
      .pipe(filter((ids): ids is string[] => !!ids && ids.length > 0))
      .subscribe((ids) =>
        this.run(from(ids).pipe(concatMap((mediaId) => this.api.addMedia(id, mediaId)), last()), 'adicionar mídia'),
      );
  }

  protected addSong(): void {
    const id = this.id();
    this.dialog
      .open<SongPickerDialog, void, SongChoice>(SongPickerDialog, { maxWidth: '95vw' })
      .afterClosed()
      .pipe(filter((choice): choice is SongChoice => !!choice))
      .subscribe((choice) => this.run(this.api.addSong(id, choice.songId, choice.recordingId), 'adicionar música'));
  }

  protected chooseRecording(item: EventItem, recordingId: string | null): void {
    this.run(this.api.chooseRecording(this.id(), item.id, recordingId), 'trocar a gravação');
  }

  protected addSlide(): void {
    this.dialog
      .open<SlideDialog, SlideInput | null, SlideInput>(SlideDialog, { data: null })
      .afterClosed()
      .pipe(filter((slide): slide is SlideInput => !!slide))
      .subscribe((slide) => this.run(this.api.addSlide(this.id(), slide), 'adicionar slide'));
  }

  protected editSlide(item: EventItem): void {
    this.dialog
      .open<SlideDialog, SlideInput | null, SlideInput>(SlideDialog, {
        data: { slideTitle: item.slide?.title ?? '', slideBody: item.slide?.body ?? '' },
      })
      .afterClosed()
      .pipe(filter((slide): slide is SlideInput => !!slide))
      .subscribe((slide) => this.run(this.api.editSlide(this.id(), item.id, slide), 'editar slide'));
  }

  protected removeItem(item: EventItem): void {
    this.run(this.api.removeItem(this.id(), item.id), 'remover item');
  }

  protected editDetails(): void {
    const current = this.event();
    if (!current) {
      return;
    }
    this.dialog
      .open<EventDialog, EventDialogData, EventInput>(EventDialog, {
        data: { heading: 'Editar evento', value: { title: current.title, startsAt: current.startsAt } },
      })
      .afterClosed()
      .pipe(filter((input): input is EventInput => !!input))
      .subscribe((input) => this.run(this.api.update(current.id, input), 'salvar'));
  }

  protected prepare(): void {
    this.run(this.api.makeCurrent(this.id()), 'preparar os players', () =>
      this.snackBar.open('Os players estão baixando as mídias deste evento.', 'OK', { duration: 4000 }),
    );
  }

  protected deleteEvent(): void {
    const current = this.event();
    if (!current) {
      return;
    }
    this.dialog
      .open<ConfirmDialog, ConfirmDialogData, boolean>(ConfirmDialog, {
        data: {
          title: 'Excluir evento',
          message: `"${current.title}" e seus ${current.items.length} itens serão excluídos.`,
          confirmLabel: 'Excluir',
          destructive: true,
        },
      })
      .afterClosed()
      .pipe(
        filter(Boolean),
        switchMap(() => this.api.delete(current.id)),
      )
      .subscribe({
        next: () => void this.router.navigate(['/eventos']),
        error: (e: HttpErrorResponse) => this.fail('excluir', e),
      });
  }

  // ------------------------------------------------------------------ plumbing

  private load(id: string): void {
    this.api
      .get(id)
      .pipe(takeUntilDestroyed(this.destroyRef))
      .subscribe({
        next: (e) => {
          this.event.set(e);
          this.loadError.set(false);
        },
        error: () => this.loadError.set(true),
      });
  }

  /** Runs a change; the server's answer (the whole event) becomes the new state. */
  private run(request: Observable<EventDetail>, what: string, onDone?: () => void): void {
    this.saving.set(true);
    request.subscribe({
      next: (e) => {
        this.event.set(e);
        this.saving.set(false);
        onDone?.();
      },
      error: (e: HttpErrorResponse) => {
        this.saving.set(false);
        this.fail(what, e);
        this.load(this.id()); // resynchronize after a failed (possibly optimistic) change
      },
    });
  }

  private fail(what: string, e: HttpErrorResponse): void {
    const message =
      e.status === 409
        ? 'Outra pessoa alterou este evento ao mesmo tempo. A versão mais recente foi carregada; tente de novo.'
        : `Não foi possível ${what}${e.error?.detail ? ': ' + e.error.detail : ` (${e.status})`}.`;
    this.snackBar.open(message, 'OK', { duration: 6000 });
  }
}

function isReady(r: Readiness, eventId: string | undefined): boolean {
  const c = r.cacheStatus;
  return !!c && !!eventId && c.eventId === eventId && c.itemsReady === c.itemsTotal && c.itemsFailed === 0;
}
