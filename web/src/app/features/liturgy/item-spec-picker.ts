import { Injectable, inject } from '@angular/core';
import { MatDialog } from '@angular/material/dialog';
import { Observable, filter, map } from 'rxjs';
import { SceneInput, SlideInput } from '../../core/events/event';
import { ItemSpec, SlotKind } from '../../core/liturgy/liturgy';
import { MediaPickerData, MediaPickerDialog } from '../events/dialogs/media-picker-dialog';
import { SceneDialog } from '../events/dialogs/scene-dialog';
import { SlideDialog } from '../events/dialogs/slide-dialog';
import { SongChoice, SongPickerData, SongPickerDialog } from '../events/dialogs/song-picker-dialog';
import { TimerDialog, TimerDialogData, TimerDialogResult } from '../events/dialogs/timer-dialog';
import { SlotDialog, SlotDialogResult } from './slot-dialog';

/** Opens the item dialogs and returns what was chosen as item descriptions (nothing when cancelled). */
@Injectable({ providedIn: 'root' })
export class ItemSpecPicker {
  private readonly dialog = inject(MatDialog);

  song(): Observable<ItemSpec> {
    return this.dialog
      .open<SongPickerDialog, SongPickerData, SongChoice>(SongPickerDialog, { data: { mode: 'event' } })
      .afterClosed()
      .pipe(
        filter((c): c is SongChoice => !!c),
        map((c) => ({ type: 'SONG', songId: c.songId, recordingId: c.recordingId })),
      );
  }

  /** One or more library files, in the order chosen. */
  media(title = 'Adicionar mídias'): Observable<ItemSpec[]> {
    return this.dialog
      .open<MediaPickerDialog, MediaPickerData, string[]>(MediaPickerDialog, { data: { title } })
      .afterClosed()
      .pipe(
        filter((ids): ids is string[] => !!ids?.length),
        map((ids) => ids.map((mediaId): ItemSpec => ({ type: 'MEDIA', mediaId }))),
      );
  }

  slide(): Observable<ItemSpec> {
    return this.dialog
      .open<SlideDialog, SlideInput | null, SlideInput>(SlideDialog, { data: null })
      .afterClosed()
      .pipe(
        filter((s): s is SlideInput => !!s),
        map((s) => ({ type: 'SLIDE', slideTitle: s.slideTitle, slideBody: s.slideBody })),
      );
  }

  scene(): Observable<ItemSpec> {
    return this.dialog
      .open<SceneDialog, SceneInput | null, SceneInput>(SceneDialog, { data: null })
      .afterClosed()
      .pipe(
        filter((s): s is SceneInput => !!s),
        map((s) => ({ type: 'SCENE', sceneTitle: s.title, sceneStarts: s.starts, sceneEnds: s.ends })),
      );
  }

  timer(): Observable<ItemSpec> {
    return this.dialog
      .open<TimerDialog, TimerDialogData, TimerDialogResult>(TimerDialog, { data: { label: null, timer: null } })
      .afterClosed()
      .pipe(
        filter((r): r is TimerDialogResult => !!r),
        map((r) => ({ type: 'TIMER', label: r.label, timer: r.timer })),
      );
  }

  /** A variable slot: a name and the kind of item to choose for each event. */
  slot(): Observable<ItemSpec> {
    return this.dialog
      .open<SlotDialog, null, SlotDialogResult>(SlotDialog, { data: null })
      .afterClosed()
      .pipe(
        filter((r): r is SlotDialogResult => !!r),
        map((r) => ({ type: 'SLOT', label: r.label, slotKind: r.kind })),
      );
  }

  /** What fills a variable slot of this kind (ANY asks the caller to choose the kind first). */
  fill(kind: Exclude<SlotKind, 'ANY'>): Observable<ItemSpec> {
    switch (kind) {
      case 'SONG':
        return this.song();
      case 'MEDIA':
        return this.media('Escolher mídia').pipe(map((specs) => specs[0]));
      case 'SLIDE':
        return this.slide();
    }
  }
}
