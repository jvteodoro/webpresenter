import { HttpErrorResponse } from '@angular/common/http';
import { Component, computed, inject, signal } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { MatButtonModule } from '@angular/material/button';
import { MatDialog } from '@angular/material/dialog';
import { MatFormFieldModule } from '@angular/material/form-field';
import { MatIconModule } from '@angular/material/icon';
import { MatInputModule } from '@angular/material/input';
import { MatProgressBarModule } from '@angular/material/progress-bar';
import { MatSnackBar } from '@angular/material/snack-bar';
import { Router, RouterLink } from '@angular/router';
import { filter, switchMap } from 'rxjs';
import { CurrentUser } from '../../../core/auth/current-user';
import { Album, AlbumInput } from '../../../core/library/album';
import { AlbumsApi } from '../../../core/library/albums-api';
import { normalizeText } from '../../../shared/text';
import { AlbumDialog } from './album-dialog';

interface AlbumGroup {
  name: string;
  albums: Album[];
}

/** Albums as a cover grid, grouped (LouvorJA categories or the group given by people). */
@Component({
  selector: 'app-albums-section',
  imports: [FormsModule, MatButtonModule, MatFormFieldModule, MatIconModule, MatInputModule, MatProgressBarModule, RouterLink],
  template: `
    <div class="toolbar">
      <mat-form-field appearance="outline" subscriptSizing="dynamic" class="search">
        <mat-icon matPrefix>search</mat-icon>
        <mat-label>Buscar álbum</mat-label>
        <input matInput [ngModel]="search()" (ngModelChange)="search.set($event)" />
      </mat-form-field>
      @if (currentUser.canManageLibrary()) {
        <button mat-flat-button (click)="create()"><mat-icon>add</mat-icon> Novo álbum</button>
      }
    </div>
    @if (loading()) { <mat-progress-bar mode="indeterminate" /> }
    @if (loadError()) {
      <p class="empty error">Não foi possível carregar os álbuns.</p>
    }
    @for (g of groups(); track g.name) {
      <section class="group">
        <h2>{{ g.name }} <span class="count">{{ g.albums.length }}</span></h2>
        <ul class="grid">
          @for (a of g.albums; track a.id) {
            <li>
              <a class="album" [routerLink]="['/biblioteca/albuns', a.id]" [attr.aria-label]="a.name">
                <span class="cover">
                  @if (a.coverUrl) {
                    <img [src]="a.coverUrl" alt="" loading="lazy" />
                  } @else {
                    <mat-icon>album</mat-icon>
                  }
                </span>
                <span class="name">{{ a.name }}</span>
                <span class="meta">{{ a.trackCount }} {{ a.trackCount === 1 ? 'música' : 'músicas' }}</span>
              </a>
            </li>
          }
        </ul>
      </section>
    } @empty {
      @if (!loading() && !loadError()) {
        <p class="empty">{{ albums().length ? 'Nenhum álbum encontrado.' : 'Nenhum álbum ainda.' }}</p>
      }
    }
  `,
  styles: `
    :host { display: flex; flex-direction: column; gap: 1rem; }
    .toolbar { display: flex; flex-wrap: wrap; gap: 0.75rem; align-items: center; }
    .search { flex: 1; min-width: 14rem; }
    .group h2 { font: var(--mat-sys-title-medium); margin: 0.25rem 0 0.75rem; }
    .count { color: var(--mat-sys-on-surface-variant); font-weight: 400; margin-left: 0.25rem; }
    .grid { list-style: none; margin: 0; padding: 0; display: grid; gap: 1rem;
      grid-template-columns: repeat(auto-fill, minmax(9.5rem, 1fr)); }
    .album { display: flex; flex-direction: column; gap: 0.3rem; color: inherit; text-decoration: none; border-radius: 10px; padding: 0.4rem; }
    .album:hover { background: var(--mat-sys-surface-container); }
    .cover { aspect-ratio: 1; border-radius: 8px; overflow: hidden; display: grid; place-items: center;
      background: var(--mat-sys-surface-container-high); color: var(--mat-sys-on-surface-variant);
      box-shadow: 0 1px 3px rgb(0 0 0 / 0.25); }
    .cover img { width: 100%; height: 100%; object-fit: cover; }
    .cover mat-icon { font-size: 3rem; width: 3rem; height: 3rem; }
    .name { font-weight: 500; line-height: 1.25; display: -webkit-box; -webkit-line-clamp: 2; -webkit-box-orient: vertical; overflow: hidden; }
    .meta { font-size: 0.8rem; color: var(--mat-sys-on-surface-variant); }
    .empty { text-align: center; color: var(--mat-sys-on-surface-variant); margin: 1.5rem 0; }
    .error { color: var(--mat-sys-error); }
    @media (max-width: 600px) { .grid { grid-template-columns: repeat(auto-fill, minmax(7.5rem, 1fr)); gap: 0.5rem; } }
  `,
})
export class AlbumsSection {
  private readonly api = inject(AlbumsApi);
  private readonly dialog = inject(MatDialog);
  private readonly router = inject(Router);
  private readonly snackBar = inject(MatSnackBar);
  protected readonly currentUser = inject(CurrentUser);

  protected readonly albums = signal<Album[]>([]);
  protected readonly loading = signal(true);
  protected readonly loadError = signal(false);
  protected readonly search = signal('');

  protected readonly groups = computed<AlbumGroup[]>(() => {
    const term = normalizeText(this.search());
    const byGroup = new Map<string, Album[]>();
    for (const a of this.albums()) {
      if (term && !normalizeText(a.name).includes(term)) {
        continue;
      }
      const group = a.group || (a.source === 'MANUAL' ? 'Meus álbuns' : 'Outros');
      byGroup.set(group, [...(byGroup.get(group) ?? []), a]);
    }
    return [...byGroup.entries()].map(([name, albums]) => ({ name, albums }));
  });

  constructor() {
    this.api.list().subscribe({
      next: (albums) => {
        this.albums.set(albums);
        this.loading.set(false);
      },
      error: () => {
        this.loadError.set(true);
        this.loading.set(false);
      },
    });
  }

  protected create(): void {
    this.dialog
      .open<AlbumDialog, null, AlbumInput>(AlbumDialog, { data: null })
      .afterClosed()
      .pipe(
        filter((input): input is AlbumInput => !!input),
        switchMap((input) => this.api.create(input)),
      )
      .subscribe({
        next: (album) => void this.router.navigate(['/biblioteca/albuns', album.id]),
        error: (e: HttpErrorResponse) => this.snackBar.open(`Não foi possível criar o álbum (${e.status}).`, 'OK', { duration: 6000 }),
      });
  }
}
