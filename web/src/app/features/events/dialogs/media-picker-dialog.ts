import { Component, computed, inject, signal } from '@angular/core';
import { toSignal } from '@angular/core/rxjs-interop';
import { FormsModule } from '@angular/forms';
import { MatButtonModule } from '@angular/material/button';
import { MatCheckboxModule } from '@angular/material/checkbox';
import { MAT_DIALOG_DATA, MatDialogModule, MatDialogRef } from '@angular/material/dialog';
import { MatFormFieldModule } from '@angular/material/form-field';
import { MatIconModule } from '@angular/material/icon';
import { MatInputModule } from '@angular/material/input';
import { MEDIA_KIND_ICONS, MediaAsset, MediaKind, MediaPurpose } from '../../../core/media/media';
import { MediaApi } from '../../../core/media/media-api';
import { DurationPipe } from '../../../shared/pipes/duration.pipe';
import { normalizeText } from '../../../shared/text';

/** Which files to offer: library purposes (general media by default), kinds (all by default), dialog title. */
export interface MediaPickerData {
  purposes?: MediaPurpose[];
  kinds?: MediaKind[];
  title?: string;
}

/** Picks one or more library files, in the order they were checked. Closes with their ids. */
@Component({
  selector: 'app-media-picker-dialog',
  imports: [FormsModule, MatDialogModule, MatButtonModule, MatCheckboxModule, MatFormFieldModule, MatIconModule, MatInputModule, DurationPipe],
  template: `
    <h2 mat-dialog-title>{{ data?.title ?? 'Adicionar mídias' }}</h2>
    <mat-dialog-content>
      <mat-form-field appearance="outline" subscriptSizing="dynamic" class="search">
        <mat-icon matPrefix>search</mat-icon>
        <mat-label>Buscar na biblioteca</mat-label>
        <input matInput [ngModel]="search()" (ngModelChange)="search.set($event)" cdkFocusInitial />
      </mat-form-field>
      <ul class="list">
        @for (m of visible(); track m.id) {
          <li>
            <mat-checkbox [checked]="selected().includes(m.id)" (change)="toggle(m.id)">
              <span class="row">
                <mat-icon class="kind">{{ icon(m) }}</mat-icon>
                <span class="title">{{ m.title }}</span>
                <span class="meta">{{ m.durationMs | duration }}</span>
              </span>
            </mat-checkbox>
          </li>
        } @empty {
          <li class="empty">Nenhuma mídia encontrada.</li>
        }
      </ul>
    </mat-dialog-content>
    <mat-dialog-actions align="end">
      <button mat-button mat-dialog-close>Cancelar</button>
      <button mat-flat-button [disabled]="selected().length === 0" (click)="confirm()">
        Adicionar {{ selected().length || '' }}
      </button>
    </mat-dialog-actions>
  `,
  styles: `
    .search {
      width: min(34rem, 75vw);
      margin-top: 0.5rem;
    }
    .list {
      list-style: none;
      margin: 0.5rem 0 0;
      padding: 0;
      max-height: 50vh;
      overflow: auto;
    }
    .row {
      display: inline-flex;
      align-items: center;
      gap: 0.5rem;
    }
    .kind {
      color: var(--mat-sys-primary);
    }
    .meta,
    .empty {
      color: var(--mat-sys-on-surface-variant);
      font-size: 0.875rem;
    }
  `,
})
export class MediaPickerDialog {
  private readonly ref = inject(MatDialogRef<MediaPickerDialog, string[]>);
  protected readonly data = inject<MediaPickerData | null>(MAT_DIALOG_DATA, { optional: true });
  private readonly media = toSignal(inject(MediaApi).list(this.data?.purposes ?? ['GENERAL']), { initialValue: [] as MediaAsset[] });

  protected readonly search = signal('');
  protected readonly selected = signal<string[]>([]);
  protected readonly visible = computed(() => {
    const term = normalizeText(this.search());
    const kinds = this.data?.kinds;
    return this.media().filter(
      (m) => (!kinds || kinds.includes(m.kind)) && (!term || normalizeText(m.title).includes(term)),
    );
  });

  protected icon(m: MediaAsset): string {
    return MEDIA_KIND_ICONS[m.kind];
  }

  protected toggle(id: string): void {
    this.selected.update((ids) => (ids.includes(id) ? ids.filter((x) => x !== id) : [...ids, id]));
  }

  protected confirm(): void {
    this.ref.close(this.selected());
  }
}
