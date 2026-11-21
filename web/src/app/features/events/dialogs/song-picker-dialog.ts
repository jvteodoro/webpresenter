import { Component, computed, inject, signal } from '@angular/core';
import { toObservable, toSignal } from '@angular/core/rxjs-interop';
import { FormsModule } from '@angular/forms';
import { MatButtonModule } from '@angular/material/button';
import { MatDialogModule, MatDialogRef } from '@angular/material/dialog';
import { MatFormFieldModule } from '@angular/material/form-field';
import { MatIconModule } from '@angular/material/icon';
import { MatInputModule } from '@angular/material/input';
import { MatProgressBarModule } from '@angular/material/progress-bar';
import { MatRadioModule } from '@angular/material/radio';
import { catchError, debounceTime, distinctUntilChanged, of, switchMap } from 'rxjs';
import { RECORDING_LABELS, SongDetail, SongPage } from '../../../core/songs/song';
import { SongsApi } from '../../../core/songs/songs-api';
import { DurationPipe } from '../../../shared/pipes/duration.pipe';

export interface SongChoice {
  songId: string;
  recordingId: string | null;
}

/** Search a song, then choose how to present it: with one of its recordings or lyrics only. */
@Component({
  selector: 'app-song-picker-dialog',
  imports: [FormsModule, MatDialogModule, MatButtonModule, MatFormFieldModule, MatIconModule, MatInputModule, MatProgressBarModule, MatRadioModule, DurationPipe],
  template: `
    <h2 mat-dialog-title>Adicionar música</h2>
    <mat-dialog-content class="content">
      @if (!selected()) {
        <mat-form-field appearance="outline" subscriptSizing="dynamic" class="search">
          <mat-icon matPrefix>search</mat-icon>
          <mat-label>Buscar por título ou letra</mat-label>
          <input matInput [ngModel]="query()" (ngModelChange)="query.set($event)" cdkFocusInitial />
        </mat-form-field>
        <ul class="list">
          @for (s of results()?.songs ?? []; track s.id) {
            <li>
              <button type="button" class="song" (click)="select(s.id)">
                <mat-icon>music_note</mat-icon>
                <span class="main">
                  <span class="title">{{ s.title }}</span>
                  <span class="meta">
                    @for (c of s.collections; track c.slug) { {{ c.name }}@if (c.number) { {{ c.number }} } · }
                    {{ s.firstLine }}
                  </span>
                </span>
              </button>
            </li>
          } @empty {
            @if (query().length > 1) { <li class="empty">Nenhuma música encontrada.</li> }
          }
        </ul>
      } @else if (selected(); as song) {
        <p class="chosen"><mat-icon>music_note</mat-icon> <strong>{{ song.title }}</strong> · {{ song.slideSequence.length }} slides</p>
        <p class="question">Como apresentar?</p>
        <mat-radio-group class="options" [ngModel]="recordingId()" (ngModelChange)="recordingId.set($event)">
          @for (r of song.recordings; track r.id) {
            <mat-radio-button [value]="r.id">
              {{ labels[r.kind] }} · {{ r.media.durationMs | duration }}
              @if (r.timing && r.timingCurrent) { · a letra avança sozinha } @else { · sem tempos (avanço manual) }
            </mat-radio-button>
          }
          <mat-radio-button [value]="null">Sem áudio · a banda toca, a letra avança pelo controle</mat-radio-button>
        </mat-radio-group>
      } @else {
        <mat-progress-bar mode="indeterminate" />
      }
    </mat-dialog-content>
    <mat-dialog-actions align="end">
      @if (selected()) {
        <button mat-button type="button" (click)="selected.set(null)">Voltar</button>
      }
      <button mat-button mat-dialog-close>Cancelar</button>
      <button mat-flat-button [disabled]="!selected()" (click)="confirm()">Adicionar</button>
    </mat-dialog-actions>
  `,
  styles: `
    .content { width: min(38rem, 80vw); }
    .search { width: 100%; margin-top: 0.5rem; }
    .list { list-style: none; margin: 0.5rem 0 0; padding: 0; max-height: 50vh; overflow: auto; }
    .song { display: flex; align-items: center; gap: 0.6rem; width: 100%; padding: 0.5rem; border: 0; border-radius: 8px;
      background: transparent; color: inherit; font: inherit; text-align: left; cursor: pointer; }
    .song:hover { background: var(--mat-sys-surface-container-high); }
    .song mat-icon, .chosen mat-icon { color: var(--mat-sys-primary); }
    .main { display: flex; flex-direction: column; min-width: 0; }
    .title { font-weight: 500; }
    .meta { font-size: 0.8rem; color: var(--mat-sys-on-surface-variant); white-space: nowrap; overflow: hidden; text-overflow: ellipsis; }
    .empty { color: var(--mat-sys-on-surface-variant); }
    .chosen { display: flex; align-items: center; gap: 0.4rem; }
    .options { display: flex; flex-direction: column; gap: 0.5rem; }
  `,
})
export class SongPickerDialog {
  private readonly api = inject(SongsApi);
  private readonly ref = inject(MatDialogRef<SongPickerDialog, SongChoice>);
  protected readonly labels = RECORDING_LABELS;

  protected readonly query = signal('');
  protected readonly selected = signal<SongDetail | null>(null);
  protected readonly recordingId = signal<string | null>(null);
  protected readonly results = toSignal(
    toObservable(this.query).pipe(
      debounceTime(200),
      distinctUntilChanged(),
      switchMap((q) => (q.trim().length < 2 ? of(null) : this.api.search(q, null, 0, 30).pipe(catchError(() => of(null))))),
    ),
    { initialValue: null as SongPage | null },
  );

  protected select(id: string): void {
    this.api.get(id).subscribe((song) => {
      // Default: the sung recording with timing, else any recording, else lyrics only.
      const best = song.recordings.find((r) => r.kind === 'SUNG' && r.timingCurrent) ?? song.recordings[0];
      this.recordingId.set(best?.id ?? null);
      this.selected.set(song);
    });
  }

  protected confirm(): void {
    const song = this.selected();
    if (song) {
      this.ref.close({ songId: song.id, recordingId: this.recordingId() });
    }
  }
}
