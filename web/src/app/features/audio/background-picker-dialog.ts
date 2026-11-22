import { Component, computed, inject, signal } from '@angular/core';
import { toSignal } from '@angular/core/rxjs-interop';
import { FormsModule } from '@angular/forms';
import { MatButtonModule } from '@angular/material/button';
import { MAT_DIALOG_DATA, MatDialogModule, MatDialogRef } from '@angular/material/dialog';
import { MatFormFieldModule } from '@angular/material/form-field';
import { MatIconModule } from '@angular/material/icon';
import { MatInputModule } from '@angular/material/input';
import { MatRadioModule } from '@angular/material/radio';
import { MatSelectModule } from '@angular/material/select';
import { catchError, of } from 'rxjs';
import { BackgroundSource } from '../../core/audio/background';
import { MediaApi } from '../../core/media/media-api';
import { PlaylistsApi } from '../../core/playlists/playlists-api';
import { normalizeText } from '../../shared/text';

export interface BackgroundPickerData {
  title: string;
  current: BackgroundSource | null;
  /** Label of the "inherit" option (e.g. "Usar o da cena"); absent: no inherit option. */
  inheritLabel?: string;
}

/** Result: the chosen source, or null to inherit. Closing without a result means cancel. */
export interface BackgroundChoice {
  source: BackgroundSource | null;
}

type Mode = 'INHERIT' | 'SILENCE' | 'PLAYLIST' | 'MEDIA';

/** Chooses background music: inherit, silence, a playlist or one track. */
@Component({
  selector: 'app-background-picker-dialog',
  imports: [FormsModule, MatButtonModule, MatDialogModule, MatFormFieldModule, MatIconModule, MatInputModule, MatRadioModule, MatSelectModule],
  template: `
    <h2 mat-dialog-title>{{ data.title }}</h2>
    <mat-dialog-content class="content">
      <mat-radio-group class="modes" [ngModel]="mode()" (ngModelChange)="mode.set($event)" aria-label="Fundo musical">
        @if (data.inheritLabel) {
          <mat-radio-button value="INHERIT">{{ data.inheritLabel }}</mat-radio-button>
        }
        <mat-radio-button value="PLAYLIST">Uma playlist</mat-radio-button>
        <mat-radio-button value="MEDIA">Uma faixa da biblioteca</mat-radio-button>
        <mat-radio-button value="SILENCE">Silêncio</mat-radio-button>
      </mat-radio-group>

      @if (mode() === 'PLAYLIST') {
        <mat-form-field appearance="outline" subscriptSizing="dynamic">
          <mat-label>Playlist</mat-label>
          <mat-select [ngModel]="playlistId()" (ngModelChange)="playlistId.set($event)">
            @for (p of playlists(); track p.id) {
              <mat-option [value]="p.id">{{ p.name }} · {{ p.itemCount }} {{ p.itemCount === 1 ? 'item' : 'itens' }}{{ p.shuffle ? ' · aleatória' : '' }}</mat-option>
            } @empty {
              <mat-option disabled>Nenhuma playlist. Crie uma em Biblioteca → Playlists.</mat-option>
            }
          </mat-select>
        </mat-form-field>
      }
      @if (mode() === 'MEDIA') {
        <mat-form-field appearance="outline" subscriptSizing="dynamic">
          <mat-icon matPrefix>search</mat-icon>
          <mat-label>Buscar faixa</mat-label>
          <input matInput [ngModel]="search()" (ngModelChange)="search.set($event)" />
        </mat-form-field>
        <ul class="tracks" aria-label="Faixas">
          @for (t of tracks(); track t.id) {
            <li>
              <button type="button" class="track" [class.selected]="t.id === mediaId()" (click)="mediaId.set(t.id)">
                <mat-icon>{{ t.id === mediaId() ? 'radio_button_checked' : 'radio_button_unchecked' }}</mat-icon>
                <span>{{ t.title }}</span>
                @if (t.purpose === 'BACKGROUND_MUSIC') { <span class="tag">Fundo musical</span> }
              </button>
            </li>
          } @empty {
            <li class="empty">Nenhuma faixa. Envie ou importe em Biblioteca → Fundos musicais.</li>
          }
        </ul>
      }
    </mat-dialog-content>
    <mat-dialog-actions align="end">
      <button mat-button mat-dialog-close>Cancelar</button>
      <button mat-flat-button [disabled]="!valid()" (click)="confirm()">Aplicar</button>
    </mat-dialog-actions>
  `,
  styles: `
    .content { display: flex; flex-direction: column; gap: 1rem; width: min(32rem, 80vw); }
    .modes { display: flex; flex-direction: column; gap: 0.25rem; }
    .tracks { list-style: none; margin: 0; padding: 0; max-height: 40vh; overflow: auto; }
    .track { display: flex; align-items: center; gap: 0.5rem; width: 100%; padding: 0.45rem 0.5rem; border: 0; border-radius: 8px;
      background: transparent; color: inherit; font: inherit; text-align: left; cursor: pointer; }
    .track:hover, .track.selected { background: var(--mat-sys-surface-container-high); }
    .track mat-icon { color: var(--mat-sys-primary); }
    .tag { margin-left: auto; font-size: 0.75rem; color: var(--mat-sys-on-surface-variant); }
    .empty { color: var(--mat-sys-on-surface-variant); padding: 0.5rem; }
  `,
})
export class BackgroundPickerDialog {
  protected readonly data = inject<BackgroundPickerData>(MAT_DIALOG_DATA);
  private readonly ref = inject(MatDialogRef<BackgroundPickerDialog, BackgroundChoice>);

  protected readonly mode = signal<Mode>(
    this.data.current?.kind ?? (this.data.inheritLabel ? 'INHERIT' : 'SILENCE'),
  );
  protected readonly playlistId = signal<string | null>(this.data.current?.kind === 'PLAYLIST' ? (this.data.current.ref ?? null) : null);
  protected readonly mediaId = signal<string | null>(this.data.current?.kind === 'MEDIA' ? (this.data.current.ref ?? null) : null);
  protected readonly search = signal('');

  protected readonly playlists = toSignal(inject(PlaylistsApi).list().pipe(catchError(() => of([]))), { initialValue: [] });
  private readonly media = toSignal(
    inject(MediaApi).list(['BACKGROUND_MUSIC', 'GENERAL']).pipe(catchError(() => of([]))),
    { initialValue: [] },
  );
  /** Audio (and video, whose sound plays) — background music first. */
  protected readonly tracks = computed(() => {
    const term = normalizeText(this.search());
    return this.media()
      .filter((m) => m.kind !== 'IMAGE' && (!term || normalizeText(m.title).includes(term)))
      .sort((a, b) => Number(b.purpose === 'BACKGROUND_MUSIC') - Number(a.purpose === 'BACKGROUND_MUSIC'));
  });

  protected readonly valid = computed(() => {
    switch (this.mode()) {
      case 'PLAYLIST':
        return !!this.playlistId();
      case 'MEDIA':
        return !!this.mediaId();
      default:
        return true;
    }
  });

  protected confirm(): void {
    const mode = this.mode();
    const source: BackgroundSource | null =
      mode === 'INHERIT' ? null
        : mode === 'SILENCE' ? { kind: 'SILENCE' }
          : mode === 'PLAYLIST' ? { kind: 'PLAYLIST', ref: this.playlistId() }
            : { kind: 'MEDIA', ref: this.mediaId() };
    this.ref.close({ source });
  }
}
