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
import { EventDetail, EventInput, EventItem, Readiness, SceneInput, SlideInput } from '../../../core/events/event';
import { BackgroundView } from '../../../core/audio/background';
import { BackgroundChoice, BackgroundPickerData, BackgroundPickerDialog } from '../../audio/background-picker-dialog';
import { SceneDialog } from '../dialogs/scene-dialog';
import { ItemSpecPicker } from '../../liturgy/item-spec-picker';
import { ItemSpec } from '../../../core/liturgy/liturgy';
import { TimerDialog, TimerDialogData, TimerDialogResult } from '../dialogs/timer-dialog';
import { TextPromptDialog, TextPromptDialogData } from '../../../shared/dialogs/text-prompt-dialog';
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
  private readonly picker = inject(ItemSpecPicker);
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

  /** Position among presented items (scenes are not numbered). */
  protected readonly positions = computed(() => {
    let n = 0;
    // Slots are numbered: they hold the place of the item that will be chosen.
    return new Map((this.event()?.items ?? []).map((i) => [i.id, i.type === 'SCENE' ? 0 : ++n]));
  });
  protected readonly presentedCount = computed(
    () => (this.event()?.items ?? []).filter((i) => i.type !== 'SCENE' && i.type !== 'SLOT').length,
  );

  /** "9:30–10:10" or "a partir de 9:30". */
  protected sceneTimes(item: EventItem): string {
    const s = item.scene?.starts?.slice(0, 5);
    const e = item.scene?.ends?.slice(0, 5);
    return s && e ? `${s}–${e}` : s ? `a partir de ${s}` : e ? `até ${e}` : '';
  }

  /** "Até o fim da cena · 2 sinais", "Até 10:10", "5:00 a partir do início". */
  protected timerText(item: EventItem): string {
    const t = item.timer;
    if (!t) {
      return '';
    }
    const target =
      t.mode === 'SCENE_END' ? 'Até o fim da cena'
        : t.mode === 'TIME' ? `Até ${t.time?.slice(0, 5)}`
          : `${Math.floor((t.durationSeconds ?? 0) / 60)}:${String((t.durationSeconds ?? 0) % 60).padStart(2, '0')} a partir do início`;
    const n = t.signals.length;
    return n ? `${target} · ${n} ${n === 1 ? 'sinal' : 'sinais'}` : target;
  }

  protected icon(item: EventItem): string {
    if (item.type === 'SCENE') {
      return 'segment';
    }
    if (item.type === 'CLOCK') {
      return 'schedule';
    }
    if (item.type === 'SLOT') {
      return 'edit_calendar';
    }
    if (item.type === 'TIMER') {
      return 'timer';
    }
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

  /** Fills a variable slot from the liturgy template with a song, media or slide (as the slot expects). */
  protected fillSlot(item: EventItem, kind: 'SONG' | 'MEDIA' | 'SLIDE'): void {
    this.picker.fill(kind).subscribe((spec: ItemSpec) => this.run(this.api.fillSlot(this.id(), item.id, spec), 'preencher'));
  }

  protected slotHint(item: EventItem): string {
    return { SONG: 'Escolha uma música', MEDIA: 'Escolha uma mídia', SLIDE: 'Escreva o slide', ANY: 'Escolha uma música, mídia ou slide' }[
      item.slotKind ?? 'ANY'
    ];
  }

  protected addClock(): void {
    this.run(this.api.addClock(this.id(), null), 'adicionar relógio');
  }

  protected addTimer(): void {
    this.openTimer(null, (r) => this.api.addTimer(this.id(), r.label, r.timer), 'adicionar cronômetro');
  }

  protected editClock(item: EventItem): void {
    if (item.type === 'TIMER') {
      this.openTimer(item, (r) => this.api.editClock(this.id(), item.id, r.label, r.timer), 'editar cronômetro');
      return;
    }
    this.dialog
      .open<TextPromptDialog, TextPromptDialogData, string>(TextPromptDialog, {
        data: { title: 'Texto acima do relógio', label: 'Texto', value: item.label ?? 'Relógio', maxLength: 200 },
      })
      .afterClosed()
      .pipe(filter((label): label is string => !!label))
      .subscribe((label) => this.run(this.api.editClock(this.id(), item.id, label, null), 'editar relógio'));
  }

  private openTimer(item: EventItem | null, save: (r: TimerDialogResult) => Observable<EventDetail>, what: string): void {
    this.dialog
      .open<TimerDialog, TimerDialogData, TimerDialogResult>(TimerDialog, {
        data: { label: item?.label ?? null, timer: item?.timer ?? null },
      })
      .afterClosed()
      .pipe(filter((r): r is TimerDialogResult => !!r))
      .subscribe((r) => this.run(save(r), what));
  }

  protected addScene(): void {
    this.dialog
      .open<SceneDialog, SceneInput | null, SceneInput>(SceneDialog, { data: null })
      .afterClosed()
      .pipe(filter((scene): scene is SceneInput => !!scene))
      .subscribe((scene) => this.run(this.api.addScene(this.id(), scene), 'adicionar cena'));
  }

  protected editScene(item: EventItem): void {
    this.dialog
      .open<SceneDialog, SceneInput | null, SceneInput>(SceneDialog, {
        data: { title: item.scene?.title ?? item.title, starts: item.scene?.starts ?? null, ends: item.scene?.ends ?? null },
      })
      .afterClosed()
      .pipe(filter((scene): scene is SceneInput => !!scene))
      .subscribe((scene) => this.run(this.api.editScene(this.id(), item.id, scene), 'editar cena'));
  }

  /** Background music of an item or scene; leaving it empty inherits (scene → event → platform). */
  protected chooseItemBackground(item: EventItem): void {
    this.pickBackground(
      item.type === 'SCENE' ? `Fundo musical da cena "${item.title}"` : `Fundo musical de "${item.title}"`,
      item.background,
      item.type === 'SCENE' ? 'Usar o do evento' : 'Usar o da cena (ou do evento)',
      (source) => this.api.setItemBackground(this.id(), item.id, source),
    );
  }

  protected chooseEventBackground(): void {
    const current = this.event();
    if (current) {
      this.pickBackground('Fundo musical do evento', current.background, 'Usar o padrão da plataforma', (source) =>
        this.api.setEventBackground(current.id, source),
      );
    }
  }

  private pickBackground(
    title: string,
    current: BackgroundView | null,
    inheritLabel: string,
    save: (source: BackgroundChoice['source']) => Observable<EventDetail>,
  ): void {
    this.dialog
      .open<BackgroundPickerDialog, BackgroundPickerData, BackgroundChoice>(BackgroundPickerDialog, {
        data: { title, current: current?.source ?? null, inheritLabel },
      })
      .afterClosed()
      .pipe(filter((choice): choice is BackgroundChoice => !!choice))
      .subscribe((choice) => this.run(save(choice.source), 'trocar o fundo musical'));
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
