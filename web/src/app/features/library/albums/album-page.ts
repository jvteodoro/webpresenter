import { HttpErrorResponse } from '@angular/common/http';
import { Component, computed, effect, inject, input, signal, untracked } from '@angular/core';
import { MatButtonModule } from '@angular/material/button';
import { MatDialog } from '@angular/material/dialog';
import { MatIconModule } from '@angular/material/icon';
import { MatProgressBarModule } from '@angular/material/progress-bar';
import { MatSnackBar } from '@angular/material/snack-bar';
import { MatTooltipModule } from '@angular/material/tooltip';
import { Router, RouterLink } from '@angular/router';
import { filter, switchMap } from 'rxjs';
import { CurrentUser } from '../../../core/auth/current-user';
import { AlbumDetail, AlbumInput } from '../../../core/library/album';
import { AlbumsApi } from '../../../core/library/albums-api';
import { RECORDING_LABELS } from '../../../core/songs/song';
import { ConfirmDialog, ConfirmDialogData } from '../../../shared/dialogs/confirm-dialog';
import { SongChoice, SongPickerDialog, SongPickerData } from '../../events/dialogs/song-picker-dialog';
import { AlbumDialog } from './album-dialog';

/** An album: cover, details and tracks. Albums made by people have editable tracks; imported ones keep theirs. */
@Component({
  selector: 'app-album-page',
  imports: [MatButtonModule, MatIconModule, MatProgressBarModule, MatTooltipModule, RouterLink],
  template: `
    <a mat-button routerLink="/biblioteca/albuns" class="back"><mat-icon>arrow_back</mat-icon> Álbuns</a>
    @if (detail(); as d) {
      <header class="header">
        <div class="cover">
          @if (d.album.coverUrl) { <img [src]="d.album.coverUrl" alt="Capa do álbum" /> } @else { <mat-icon>album</mat-icon> }
        </div>
        <div class="info">
          <span class="kind">Álbum{{ d.album.source === 'IMPORT' ? ' importado' : '' }}{{ d.album.group ? ' · ' + d.album.group : '' }}</span>
          <h1>{{ d.album.name }}</h1>
          @if (d.album.description) { <p class="description">{{ d.album.description }}</p> }
          <span class="meta">{{ d.tracks.length }} {{ d.tracks.length === 1 ? 'música' : 'músicas' }}</span>
          @if (currentUser.canManageLibrary()) {
            <div class="actions">
              <button mat-stroked-button (click)="edit()"><mat-icon>edit</mat-icon> Editar</button>
              @if (editableTracks()) {
                <button mat-stroked-button (click)="addTrack()"><mat-icon>add</mat-icon> Adicionar música</button>
                <button mat-button class="danger" (click)="remove()"><mat-icon>delete</mat-icon> Excluir álbum</button>
              }
            </div>
          }
        </div>
      </header>
      @if (saving()) { <mat-progress-bar mode="indeterminate" /> }
      <ol class="tracks" aria-label="Músicas do álbum">
        @for (t of d.tracks; track t.songId; let i = $index, last = $last) {
          <li class="track">
            <span class="number">{{ t.number ?? '–' }}</span>
            <a class="title" [routerLink]="['/musicas', t.songId]">{{ t.title }}</a>
            <span class="badges">
              @for (r of t.recordings; track r) { <span class="badge">{{ recordingLabels[r] }}</span> }
            </span>
            @if (editableTracks() && currentUser.canManageLibrary()) {
              <span class="track-actions">
                <button mat-icon-button [disabled]="i === 0" (click)="move(i, -1)" matTooltip="Subir" aria-label="Subir"><mat-icon>arrow_upward</mat-icon></button>
                <button mat-icon-button [disabled]="last" (click)="move(i, 1)" matTooltip="Descer" aria-label="Descer"><mat-icon>arrow_downward</mat-icon></button>
                <button mat-icon-button (click)="removeTrack(i)" matTooltip="Remover do álbum" [attr.aria-label]="'Remover ' + t.title"><mat-icon>close</mat-icon></button>
              </span>
            }
          </li>
        } @empty {
          <li class="empty">Nenhuma música neste álbum ainda.</li>
        }
      </ol>
    } @else if (loadError()) {
      <p class="empty">Álbum não encontrado.</p>
    } @else {
      <mat-progress-bar mode="indeterminate" />
    }
  `,
  styles: `
    :host { display: flex; flex-direction: column; gap: 1rem; }
    .back { align-self: flex-start; }
    .header { display: flex; gap: 1.5rem; align-items: flex-end; flex-wrap: wrap; }
    .cover { width: 12rem; height: 12rem; border-radius: 10px; overflow: hidden; display: grid; place-items: center; flex: none;
      background: var(--mat-sys-surface-container-high); color: var(--mat-sys-on-surface-variant); box-shadow: 0 2px 8px rgb(0 0 0 / 0.3); }
    .cover img { width: 100%; height: 100%; object-fit: cover; }
    .cover mat-icon { font-size: 4rem; width: 4rem; height: 4rem; }
    .info { display: flex; flex-direction: column; gap: 0.35rem; min-width: 0; flex: 1; }
    .kind, .meta { color: var(--mat-sys-on-surface-variant); font-size: 0.875rem; }
    h1 { margin: 0; font: var(--mat-sys-headline-medium); }
    .description { margin: 0; }
    .actions { display: flex; flex-wrap: wrap; gap: 0.5rem; margin-top: 0.5rem; }
    .danger { color: var(--mat-sys-error); }
    .tracks { list-style: none; margin: 0; padding: 0; display: flex; flex-direction: column; }
    .track { display: flex; align-items: center; gap: 0.75rem; padding: 0.35rem 0.75rem; min-height: 2.75rem;
      border-bottom: 1px solid var(--mat-sys-outline-variant); }
    .number { width: 2.5rem; text-align: right; color: var(--mat-sys-on-surface-variant); font-variant-numeric: tabular-nums; flex: none; }
    .title { flex: 1; min-width: 0; color: inherit; text-decoration: none; font-weight: 500; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
    .title:hover { text-decoration: underline; }
    .badges { display: flex; gap: 0.3rem; }
    .badge { font-size: 0.75rem; padding: 0.1rem 0.5rem; border-radius: 999px; background: var(--mat-sys-secondary-container); color: var(--mat-sys-on-secondary-container); }
    .track-actions { display: flex; }
    .empty { color: var(--mat-sys-on-surface-variant); padding: 1rem; text-align: center; }
    @media (max-width: 600px) {
      .cover { width: 8rem; height: 8rem; }
      .badges { display: none; }
    }
  `,
})
export class AlbumPage {
  readonly id = input.required<string>();

  private readonly api = inject(AlbumsApi);
  private readonly dialog = inject(MatDialog);
  private readonly router = inject(Router);
  private readonly snackBar = inject(MatSnackBar);
  protected readonly currentUser = inject(CurrentUser);
  protected readonly recordingLabels = RECORDING_LABELS;

  protected readonly detail = signal<AlbumDetail | null>(null);
  protected readonly loadError = signal(false);
  protected readonly saving = signal(false);
  /** Imported albums keep their numbering (hymnal numbers); only albums made here are reordered. */
  protected readonly editableTracks = computed(() => this.detail()?.album.source === 'MANUAL');

  constructor() {
    effect(() => {
      const id = this.id();
      untracked(() =>
        this.api.get(id).subscribe({
          next: (d) => this.detail.set(d),
          error: () => this.loadError.set(true),
        }),
      );
    });
  }

  protected edit(): void {
    const album = this.detail()!.album;
    this.dialog
      .open<AlbumDialog, typeof album, AlbumInput>(AlbumDialog, { data: album })
      .afterClosed()
      .pipe(
        filter((input): input is AlbumInput => !!input),
        switchMap((input) => this.api.update(album.id, input)),
      )
      .subscribe({
        next: (updated) => this.detail.update((d) => d && { ...d, album: updated }),
        error: (e: HttpErrorResponse) => this.notify(`Não foi possível salvar (${e.status}).`),
      });
  }

  protected addTrack(): void {
    this.dialog
      .open<SongPickerDialog, SongPickerData, SongChoice>(SongPickerDialog, { data: { mode: 'album' } })
      .afterClosed()
      .pipe(filter((choice): choice is SongChoice => !!choice))
      .subscribe((choice) => {
        const ids = this.songIds();
        if (ids.includes(choice.songId)) {
          this.notify('Essa música já está no álbum.');
          return;
        }
        this.saveTracks([...ids, choice.songId]);
      });
  }

  protected move(index: number, delta: number): void {
    const ids = this.songIds();
    const [id] = ids.splice(index, 1);
    ids.splice(index + delta, 0, id);
    this.saveTracks(ids);
  }

  protected removeTrack(index: number): void {
    const ids = this.songIds();
    ids.splice(index, 1);
    this.saveTracks(ids);
  }

  protected remove(): void {
    const album = this.detail()!.album;
    this.dialog
      .open<ConfirmDialog, ConfirmDialogData, boolean>(ConfirmDialog, {
        data: {
          title: 'Excluir álbum',
          message: `O álbum "${album.name}" será excluído. As músicas continuam na biblioteca.`,
          confirmLabel: 'Excluir',
          destructive: true,
        },
      })
      .afterClosed()
      .pipe(
        filter(Boolean),
        switchMap(() => this.api.delete(album.id)),
      )
      .subscribe({
        next: () => void this.router.navigate(['/biblioteca/albuns']),
        error: (e: HttpErrorResponse) => this.notify(`Não foi possível excluir (${e.status}).`),
      });
  }

  private songIds(): string[] {
    return (this.detail()?.tracks ?? []).map((t) => t.songId);
  }

  private saveTracks(songIds: string[]): void {
    this.saving.set(true);
    this.api.setTracks(this.id(), songIds).subscribe({
      next: (d) => {
        this.detail.set(d);
        this.saving.set(false);
      },
      error: (e: HttpErrorResponse) => {
        this.saving.set(false);
        this.notify(`Não foi possível salvar as músicas (${e.status}).`);
      },
    });
  }

  private notify(message: string): void {
    this.snackBar.open(message, 'OK', { duration: 6000 });
  }
}
