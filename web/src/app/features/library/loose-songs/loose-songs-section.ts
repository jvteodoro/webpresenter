import { Component, inject, signal } from '@angular/core';
import { toObservable, toSignal } from '@angular/core/rxjs-interop';
import { FormsModule } from '@angular/forms';
import { MatFormFieldModule } from '@angular/material/form-field';
import { MatIconModule } from '@angular/material/icon';
import { MatInputModule } from '@angular/material/input';
import { MatPaginatorModule, PageEvent } from '@angular/material/paginator';
import { MatProgressBarModule } from '@angular/material/progress-bar';
import { RouterLink } from '@angular/router';
import { catchError, combineLatest, debounceTime, of, switchMap, tap } from 'rxjs';
import { RECORDING_LABELS, SongPage } from '../../../core/songs/song';
import { SongsApi } from '../../../core/songs/songs-api';

/** Songs that are in no album. */
@Component({
  selector: 'app-loose-songs-section',
  imports: [FormsModule, MatFormFieldModule, MatIconModule, MatInputModule, MatPaginatorModule, MatProgressBarModule, RouterLink],
  template: `
    <mat-form-field appearance="outline" subscriptSizing="dynamic" class="search">
      <mat-icon matPrefix>search</mat-icon>
      <mat-label>Buscar por título ou letra</mat-label>
      <input matInput [ngModel]="query()" (ngModelChange)="query.set($event); offset.set(0)" />
    </mat-form-field>
    @if (loading()) { <mat-progress-bar mode="indeterminate" /> }
    @if (page(); as p) {
      <ul class="list">
        @for (s of p.songs; track s.id) {
          <li>
            <a class="song" [routerLink]="['/musicas', s.id]">
              <mat-icon class="icon">music_note</mat-icon>
              <span class="main">
                <span class="title">{{ s.title }}</span>
                @if (s.firstLine && s.firstLine !== s.title) { <span class="meta">{{ s.firstLine }}</span> }
              </span>
              @for (r of s.recordings; track r) { <span class="badge">{{ labels[r] }}</span> }
            </a>
          </li>
        } @empty {
          <li class="empty">Nenhuma música avulsa. Músicas que não estão em nenhum álbum aparecem aqui.</li>
        }
      </ul>
      @if (p.total > pageSize) {
        <mat-paginator [length]="p.total" [pageSize]="pageSize" [pageIndex]="offset() / pageSize" [hidePageSize]="true"
                       (page)="onPage($event)" aria-label="Páginas de músicas avulsas" />
      }
    }
  `,
  styles: `
    :host { display: flex; flex-direction: column; gap: 1rem; }
    .search { width: 100%; }
    .list { list-style: none; margin: 0; padding: 0; display: flex; flex-direction: column; gap: 0.35rem; }
    .song { display: flex; align-items: center; gap: 0.75rem; padding: 0.6rem 0.9rem; border-radius: var(--mat-sys-corner-medium);
      border: 1px solid var(--mat-sys-outline-variant); background: var(--mat-sys-surface-container-low); color: inherit; text-decoration: none; }
    .song:hover { background: var(--mat-sys-surface-container); }
    .icon { color: var(--mat-sys-primary); }
    .main { flex: 1; min-width: 0; display: flex; flex-direction: column; }
    .title { font-weight: 500; }
    .meta { font-size: 0.85rem; color: var(--mat-sys-on-surface-variant); white-space: nowrap; overflow: hidden; text-overflow: ellipsis; }
    .badge { font-size: 0.75rem; padding: 0.1rem 0.5rem; border-radius: 999px; background: var(--mat-sys-secondary-container); color: var(--mat-sys-on-secondary-container); }
    .empty { text-align: center; color: var(--mat-sys-on-surface-variant); padding: 1.5rem; }
  `,
})
export class LooseSongsSection {
  private readonly api = inject(SongsApi);
  protected readonly labels = RECORDING_LABELS;
  protected readonly pageSize = 50;

  protected readonly query = signal('');
  protected readonly offset = signal(0);
  protected readonly loading = signal(true);
  protected readonly page = toSignal(
    combineLatest([toObservable(this.query).pipe(debounceTime(200)), toObservable(this.offset)]).pipe(
      tap(() => this.loading.set(true)),
      switchMap(([q, offset]) => this.api.search(q, null, offset, this.pageSize, true).pipe(catchError(() => of(null)))),
      tap(() => this.loading.set(false)),
    ),
    { initialValue: null as SongPage | null },
  );

  protected onPage(event: PageEvent): void {
    this.offset.set(event.pageIndex * this.pageSize);
  }
}
