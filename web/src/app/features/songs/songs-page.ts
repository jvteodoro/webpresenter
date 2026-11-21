import { Component, computed, inject, signal } from '@angular/core';
import { toObservable, toSignal } from '@angular/core/rxjs-interop';
import { FormsModule } from '@angular/forms';
import { MatButtonModule } from '@angular/material/button';
import { MatFormFieldModule } from '@angular/material/form-field';
import { MatIconModule } from '@angular/material/icon';
import { MatInputModule } from '@angular/material/input';
import { MatPaginatorModule, PageEvent } from '@angular/material/paginator';
import { MatProgressBarModule } from '@angular/material/progress-bar';
import { MatSelectModule } from '@angular/material/select';
import { RouterLink } from '@angular/router';
import { catchError, combineLatest, debounceTime, distinctUntilChanged, map, of, switchMap, tap } from 'rxjs';
import { RECORDING_LABELS, SongCollection, SongPage } from '../../core/songs/song';
import { SongsApi } from '../../core/songs/songs-api';

const PAGE_SIZE = 50;

/** Song catalogue: accent-insensitive search on titles and first lines, filter by collection. */
@Component({
  selector: 'app-songs-page',
  imports: [
    FormsModule,
    RouterLink,
    MatButtonModule,
    MatFormFieldModule,
    MatIconModule,
    MatInputModule,
    MatPaginatorModule,
    MatProgressBarModule,
    MatSelectModule,
  ],
  templateUrl: './songs-page.html',
  styleUrl: './songs-page.scss',
})
export class SongsPage {
  private readonly api = inject(SongsApi);

  protected readonly query = signal('');
  protected readonly collection = signal<string | null>(null);
  protected readonly offset = signal(0);
  protected readonly loading = signal(true);
  protected readonly pageSize = PAGE_SIZE;
  protected readonly recordingLabels = RECORDING_LABELS;

  protected readonly collections = toSignal(this.api.collections().pipe(catchError(() => of([] as SongCollection[]))), {
    initialValue: [] as SongCollection[],
  });
  /** Collections grouped for the filter ("Hinários", "CDs Oficiais", …). */
  protected readonly groups = computed(() => {
    const groups = new Map<string, SongCollection[]>();
    for (const c of this.collections()) {
      const key = c.group ?? 'Outras';
      groups.set(key, [...(groups.get(key) ?? []), c]);
    }
    return [...groups.entries()].map(([name, items]) => ({ name, items }));
  });

  protected readonly page = toSignal(
    combineLatest([
      toObservable(this.query).pipe(debounceTime(250), distinctUntilChanged()),
      toObservable(this.collection),
      toObservable(this.offset),
    ]).pipe(
      tap(() => this.loading.set(true)),
      switchMap(([q, collection, offset]) =>
        this.api.search(q, collection, offset, PAGE_SIZE).pipe(catchError(() => of(null))),
      ),
      tap(() => this.loading.set(false)),
      map((page) => page as SongPage | null),
    ),
    { initialValue: null },
  );

  protected setQuery(value: string): void {
    this.query.set(value);
    this.offset.set(0);
  }

  protected setCollection(value: string | null): void {
    this.collection.set(value);
    this.offset.set(0);
  }

  protected onPage(event: PageEvent): void {
    this.offset.set(event.pageIndex * event.pageSize);
  }
}
