import { HttpErrorResponse } from '@angular/common/http';
import { Component, computed, effect, inject, input, signal, untracked } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { MatButtonModule } from '@angular/material/button';
import { MatDialog } from '@angular/material/dialog';
import { MatIconModule } from '@angular/material/icon';
import { MatProgressBarModule } from '@angular/material/progress-bar';
import { MatSlideToggleModule } from '@angular/material/slide-toggle';
import { MatSnackBar } from '@angular/material/snack-bar';
import { MatTooltipModule } from '@angular/material/tooltip';
import { Router, RouterLink } from '@angular/router';
import { filter, switchMap } from 'rxjs';
import { CurrentUser } from '../../../core/auth/current-user';
import { MEDIA_KIND_ICONS } from '../../../core/media/media';
import { PlaylistDetail, PlaylistItem, PlaylistItemSpec, specOf } from '../../../core/playlists/playlist';
import { PlaylistsApi } from '../../../core/playlists/playlists-api';
import { RECORDING_LABELS } from '../../../core/songs/song';
import { ConfirmDialog, ConfirmDialogData } from '../../../shared/dialogs/confirm-dialog';
import { TextPromptDialog, TextPromptDialogData } from '../../../shared/dialogs/text-prompt-dialog';
import { DurationPipe } from '../../../shared/pipes/duration.pipe';
import { MediaPickerData, MediaPickerDialog } from '../../events/dialogs/media-picker-dialog';
import { SongChoice, SongPickerData, SongPickerDialog } from '../../events/dialogs/song-picker-dialog';

/** A playlist: ordered songs (with the recording to play) and library audio/video. */
@Component({
  selector: 'app-playlist-page',
  imports: [FormsModule, MatButtonModule, MatIconModule, MatProgressBarModule, MatSlideToggleModule, MatTooltipModule, RouterLink, DurationPipe],
  template: `
    <a mat-button routerLink="/biblioteca/playlists" class="back"><mat-icon>arrow_back</mat-icon> Playlists</a>
    @if (playlist(); as p) {
      <header class="header">
        <div class="info">
          <span class="kind">Playlist</span>
          <h1>{{ p.name }}</h1>
          <span class="meta">{{ p.items.length }} {{ p.items.length === 1 ? 'item' : 'itens' }} · {{ totalMs() | duration }}</span>
        </div>
        @if (canEdit()) {
          <div class="actions">
            <mat-slide-toggle [ngModel]="p.shuffle" (ngModelChange)="setShuffle($event)">Ordem aleatória no fundo musical</mat-slide-toggle>
            <button mat-stroked-button (click)="addSong()"><mat-icon>queue_music</mat-icon> Adicionar música</button>
            <button mat-stroked-button (click)="addMedia()"><mat-icon>library_add</mat-icon> Adicionar áudio ou vídeo</button>
            <button mat-button (click)="rename()"><mat-icon>edit</mat-icon> Renomear</button>
            <button mat-button class="danger" (click)="remove()"><mat-icon>delete</mat-icon> Excluir</button>
          </div>
        }
      </header>
      @if (saving()) { <mat-progress-bar mode="indeterminate" /> }
      <ol class="items" aria-label="Itens da playlist">
        @for (item of p.items; track item.id; let i = $index, last = $last) {
          <li class="item">
            <span class="position">{{ i + 1 }}</span>
            <mat-icon class="icon">{{ icon(item) }}</mat-icon>
            <span class="main">
              @if (item.songId) {
                <a class="title" [routerLink]="['/musicas', item.songId]">{{ item.title }}</a>
              } @else {
                <span class="title">{{ item.title }}</span>
              }
              <span class="meta">{{ describe(item) }}</span>
            </span>
            <span class="duration">{{ item.durationMs | duration }}</span>
            @if (canEdit()) {
              <span class="item-actions">
                <button mat-icon-button [disabled]="i === 0" (click)="move(i, -1)" matTooltip="Subir" aria-label="Subir"><mat-icon>arrow_upward</mat-icon></button>
                <button mat-icon-button [disabled]="last" (click)="move(i, 1)" matTooltip="Descer" aria-label="Descer"><mat-icon>arrow_downward</mat-icon></button>
                <button mat-icon-button (click)="removeItem(i)" matTooltip="Remover" [attr.aria-label]="'Remover ' + item.title"><mat-icon>close</mat-icon></button>
              </span>
            }
          </li>
        } @empty {
          <li class="empty">Playlist vazia. Adicione músicas ou áudios.</li>
        }
      </ol>
    } @else if (loadError()) {
      <p class="empty">Playlist não encontrada.</p>
    } @else {
      <mat-progress-bar mode="indeterminate" />
    }
  `,
  styles: `
    :host { display: flex; flex-direction: column; gap: 1rem; }
    .back { align-self: flex-start; }
    .header { display: flex; flex-direction: column; gap: 0.75rem; }
    .info { display: flex; flex-direction: column; gap: 0.25rem; }
    .kind, .meta { color: var(--mat-sys-on-surface-variant); font-size: 0.875rem; }
    h1 { margin: 0; font: var(--mat-sys-headline-medium); }
    .actions { display: flex; flex-wrap: wrap; gap: 0.5rem; align-items: center; }
    .danger { color: var(--mat-sys-error); }
    .items { list-style: none; margin: 0; padding: 0; }
    .item { display: flex; align-items: center; gap: 0.75rem; padding: 0.35rem 0.75rem; min-height: 3rem; border-bottom: 1px solid var(--mat-sys-outline-variant); }
    .position { width: 1.75rem; text-align: right; color: var(--mat-sys-on-surface-variant); font-variant-numeric: tabular-nums; }
    .icon { color: var(--mat-sys-primary); }
    .main { flex: 1; min-width: 0; display: flex; flex-direction: column; }
    .title { font-weight: 500; color: inherit; text-decoration: none; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
    a.title:hover { text-decoration: underline; }
    .duration { color: var(--mat-sys-on-surface-variant); font-variant-numeric: tabular-nums; }
    .item-actions { display: flex; }
    .empty { color: var(--mat-sys-on-surface-variant); padding: 1rem; text-align: center; }
  `,
})
export class PlaylistPage {
  readonly id = input.required<string>();

  private readonly api = inject(PlaylistsApi);
  private readonly dialog = inject(MatDialog);
  private readonly router = inject(Router);
  private readonly snackBar = inject(MatSnackBar);
  protected readonly currentUser = inject(CurrentUser);

  protected readonly playlist = signal<PlaylistDetail | null>(null);
  protected readonly loadError = signal(false);
  protected readonly saving = signal(false);
  protected readonly canEdit = computed(() => this.currentUser.canEditPlaylists());
  protected readonly totalMs = computed(() => (this.playlist()?.items ?? []).reduce((sum, i) => sum + (i.durationMs ?? 0), 0));

  constructor() {
    effect(() => {
      const id = this.id();
      untracked(() =>
        this.api.get(id).subscribe({
          next: (p) => this.playlist.set(p),
          error: () => this.loadError.set(true),
        }),
      );
    });
  }

  protected icon(item: PlaylistItem): string {
    return item.type === 'SONG' ? 'music_note' : MEDIA_KIND_ICONS[item.kind ?? 'AUDIO'];
  }

  protected describe(item: PlaylistItem): string {
    if (item.type === 'SONG') {
      return item.recordingKind ? `Música · ${RECORDING_LABELS[item.recordingKind]}` : 'Música · sem áudio';
    }
    return item.kind === 'VIDEO' ? 'Vídeo' : 'Áudio';
  }

  protected addSong(): void {
    this.dialog
      .open<SongPickerDialog, SongPickerData, SongChoice>(SongPickerDialog, { data: { mode: 'playlist' } })
      .afterClosed()
      .pipe(filter((c): c is SongChoice => !!c))
      .subscribe((c) => this.save([...this.specs(), { type: 'SONG', songId: c.songId, recordingId: c.recordingId }]));
  }

  protected addMedia(): void {
    this.dialog
      .open<MediaPickerDialog, MediaPickerData, string[]>(MediaPickerDialog, {
        data: {
          title: 'Adicionar áudio ou vídeo',
          purposes: ['BACKGROUND_MUSIC', 'GENERAL', 'SOUND_EFFECT'],
          kinds: ['AUDIO', 'VIDEO'],
        },
      })
      .afterClosed()
      .pipe(filter((ids): ids is string[] => !!ids?.length))
      .subscribe((ids) => this.save([...this.specs(), ...ids.map((mediaId): PlaylistItemSpec => ({ type: 'MEDIA', mediaId }))]));
  }

  protected move(index: number, delta: number): void {
    const specs = this.specs();
    const [spec] = specs.splice(index, 1);
    specs.splice(index + delta, 0, spec);
    this.save(specs);
  }

  protected removeItem(index: number): void {
    const specs = this.specs();
    specs.splice(index, 1);
    this.save(specs);
  }

  protected setShuffle(shuffle: boolean): void {
    const p = this.playlist()!;
    this.api.update(p.id, p.name, p.description, shuffle).subscribe({
      next: (updated) => this.playlist.set(updated),
      error: (e: HttpErrorResponse) => this.notify(`Não foi possível salvar (${e.status}).`),
    });
  }

  protected rename(): void {
    const p = this.playlist()!;
    this.dialog
      .open<TextPromptDialog, TextPromptDialogData, string>(TextPromptDialog, {
        data: { title: 'Renomear playlist', label: 'Nome', value: p.name, maxLength: 200 },
      })
      .afterClosed()
      .pipe(
        filter((name): name is string => !!name?.trim() && name.trim() !== p.name),
        switchMap((name) => this.api.update(p.id, name.trim(), p.description, p.shuffle)),
      )
      .subscribe({
        next: (updated) => this.playlist.set(updated),
        error: (e: HttpErrorResponse) => this.notify(`Não foi possível renomear (${e.status}).`),
      });
  }

  protected remove(): void {
    const p = this.playlist()!;
    this.dialog
      .open<ConfirmDialog, ConfirmDialogData, boolean>(ConfirmDialog, {
        data: { title: 'Excluir playlist', message: `A playlist "${p.name}" será excluída.`, confirmLabel: 'Excluir', destructive: true },
      })
      .afterClosed()
      .pipe(
        filter(Boolean),
        switchMap(() => this.api.delete(p.id)),
      )
      .subscribe({
        next: () => void this.router.navigate(['/biblioteca/playlists']),
        error: (e: HttpErrorResponse) => this.notify(`Não foi possível excluir (${e.status}).`),
      });
  }

  private specs(): PlaylistItemSpec[] {
    return (this.playlist()?.items ?? []).map(specOf);
  }

  private save(items: PlaylistItemSpec[]): void {
    this.saving.set(true);
    this.api.setItems(this.id(), items).subscribe({
      next: (p) => {
        this.playlist.set(p);
        this.saving.set(false);
      },
      error: (e: HttpErrorResponse) => {
        this.saving.set(false);
        this.notify(e.error?.detail ? `Não foi possível salvar: ${e.error.detail}` : `Não foi possível salvar (${e.status}).`);
      },
    });
  }

  private notify(message: string): void {
    this.snackBar.open(message, 'OK', { duration: 6000 });
  }
}
