import { Component, computed, inject, signal } from '@angular/core';
import { toSignal } from '@angular/core/rxjs-interop';
import { FormsModule } from '@angular/forms';
import { MatButtonModule } from '@angular/material/button';
import { MatDialogModule, MatDialogRef } from '@angular/material/dialog';
import { MatFormFieldModule } from '@angular/material/form-field';
import { MatIconModule } from '@angular/material/icon';
import { MatInputModule } from '@angular/material/input';
import { catchError, of } from 'rxjs';
import { MediaAsset } from '../../core/media/media';
import { MediaApi } from '../../core/media/media-api';
import { normalizeText } from '../../shared/text';

/** Chooses a video of the library (misc or general media) whose timing gives a song's lyrics. */
@Component({
  selector: 'app-video-picker-dialog',
  imports: [FormsModule, MatButtonModule, MatDialogModule, MatFormFieldModule, MatIconModule, MatInputModule],
  template: `
    <h2 mat-dialog-title>Letra pelo vídeo</h2>
    <mat-dialog-content class="content">
      <p class="hint">
        O vídeo deve mostrar a letra no tempo da música. A gravação escolhida deve começar junto com o vídeo.
      </p>
      <mat-form-field appearance="outline" subscriptSizing="dynamic">
        <mat-icon matPrefix>search</mat-icon>
        <mat-label>Buscar vídeo</mat-label>
        <input matInput [ngModel]="search()" (ngModelChange)="search.set($event)" cdkFocusInitial />
      </mat-form-field>
      <ul class="videos" aria-label="Vídeos da biblioteca">
        @for (v of visible(); track v.id) {
          <li>
            <button type="button" class="video" (click)="choose(v)">
              <mat-icon>ondemand_video</mat-icon>
              <span>{{ v.title }}</span>
            </button>
          </li>
        } @empty {
          <li class="empty">{{ videos() === undefined ? 'Carregando…' : 'Nenhum vídeo na biblioteca. Baixe um em Biblioteca → Diversos.' }}</li>
        }
      </ul>
    </mat-dialog-content>
    <mat-dialog-actions align="end">
      <button mat-button mat-dialog-close>Cancelar</button>
    </mat-dialog-actions>
  `,
  styles: `
    .content { display: flex; flex-direction: column; gap: 0.75rem; }
    .hint { margin: 0; color: var(--mat-sys-on-surface-variant); font: var(--mat-sys-body-small); }
    .videos { list-style: none; margin: 0; padding: 0; max-height: 50vh; overflow: auto; }
    .video { display: flex; align-items: center; gap: 0.5rem; width: 100%; padding: 0.5rem; border: 0;
      background: none; text-align: left; cursor: pointer; border-radius: 8px; color: inherit; font: inherit; }
    .video:hover, .video:focus-visible { background: var(--mat-sys-surface-container-high); }
    .empty { padding: 0.5rem; color: var(--mat-sys-on-surface-variant); }
  `,
})
export class VideoPickerDialog {
  private readonly media = inject(MediaApi);
  private readonly ref = inject(MatDialogRef<VideoPickerDialog, MediaAsset>);

  protected readonly search = signal('');
  /** undefined while loading; the library's videos otherwise. */
  protected readonly videos = toSignal(
    this.media.list(['MISC', 'GENERAL']).pipe(catchError(() => of([] as MediaAsset[]))),
  );
  protected readonly visible = computed(() => {
    const term = normalizeText(this.search());
    return (this.videos() ?? [])
      .filter((a) => a.kind === 'VIDEO')
      .filter((a) => !term || normalizeText(a.title).includes(term) || normalizeText(a.originalFilename).includes(term));
  });

  protected choose(video: MediaAsset): void {
    this.ref.close(video);
  }
}
