import { HttpErrorResponse } from '@angular/common/http';
import { Component, inject, signal } from '@angular/core';
import { MatButtonModule } from '@angular/material/button';
import { MatDialog } from '@angular/material/dialog';
import { MatIconModule } from '@angular/material/icon';
import { MatProgressBarModule } from '@angular/material/progress-bar';
import { MatSnackBar } from '@angular/material/snack-bar';
import { Router, RouterLink } from '@angular/router';
import { filter, switchMap } from 'rxjs';
import { CurrentUser } from '../../../core/auth/current-user';
import { PlaylistSummary } from '../../../core/playlists/playlist';
import { PlaylistsApi } from '../../../core/playlists/playlists-api';
import { TextPromptDialog, TextPromptDialogData } from '../../../shared/dialogs/text-prompt-dialog';

/** Playlists made by people: for events and as background music. */
@Component({
  selector: 'app-playlists-section',
  imports: [MatButtonModule, MatIconModule, MatProgressBarModule, RouterLink],
  template: `
    @if (currentUser.canEditPlaylists()) {
      <div class="toolbar">
        <button mat-flat-button (click)="create()"><mat-icon>add</mat-icon> Nova playlist</button>
      </div>
    }
    @if (loading()) { <mat-progress-bar mode="indeterminate" /> }
    <ul class="list">
      @for (p of playlists(); track p.id) {
        <li>
          <a class="playlist" [routerLink]="['/biblioteca/playlists', p.id]">
            <mat-icon class="icon">{{ p.shuffle ? 'shuffle' : 'playlist_play' }}</mat-icon>
            <span class="main">
              <span class="name">{{ p.name }}</span>
              @if (p.description) { <span class="meta">{{ p.description }}</span> }
            </span>
            <span class="meta">{{ p.itemCount }} {{ p.itemCount === 1 ? 'item' : 'itens' }}</span>
          </a>
        </li>
      } @empty {
        @if (!loading()) {
          <li class="empty">Nenhuma playlist ainda. Playlists juntam músicas e áudios para eventos ou para tocar como fundo musical.</li>
        }
      }
    </ul>
  `,
  styles: `
    :host { display: flex; flex-direction: column; gap: 1rem; }
    .list { list-style: none; margin: 0; padding: 0; display: flex; flex-direction: column; gap: 0.35rem; }
    .playlist { display: flex; align-items: center; gap: 0.75rem; padding: 0.7rem 0.9rem; border-radius: var(--mat-sys-corner-medium);
      border: 1px solid var(--mat-sys-outline-variant); background: var(--mat-sys-surface-container-low); color: inherit; text-decoration: none; }
    .playlist:hover { background: var(--mat-sys-surface-container); }
    .icon { color: var(--mat-sys-primary); }
    .main { flex: 1; min-width: 0; display: flex; flex-direction: column; }
    .name { font-weight: 500; }
    .meta { font-size: 0.85rem; color: var(--mat-sys-on-surface-variant); }
    .empty { text-align: center; color: var(--mat-sys-on-surface-variant); padding: 1.5rem; }
  `,
})
export class PlaylistsSection {
  private readonly api = inject(PlaylistsApi);
  private readonly dialog = inject(MatDialog);
  private readonly router = inject(Router);
  private readonly snackBar = inject(MatSnackBar);
  protected readonly currentUser = inject(CurrentUser);

  protected readonly playlists = signal<PlaylistSummary[]>([]);
  protected readonly loading = signal(true);

  constructor() {
    this.api.list().subscribe({
      next: (p) => {
        this.playlists.set(p);
        this.loading.set(false);
      },
      error: () => this.loading.set(false),
    });
  }

  protected create(): void {
    this.dialog
      .open<TextPromptDialog, TextPromptDialogData, string>(TextPromptDialog, {
        data: { title: 'Nova playlist', label: 'Nome', value: '', maxLength: 200 },
      })
      .afterClosed()
      .pipe(
        filter((name): name is string => !!name?.trim()),
        switchMap((name) => this.api.create(name.trim())),
      )
      .subscribe({
        next: (p) => void this.router.navigate(['/biblioteca/playlists', p.id]),
        error: (e: HttpErrorResponse) => this.snackBar.open(`Não foi possível criar (${e.status}).`, 'OK', { duration: 6000 }),
      });
  }
}
