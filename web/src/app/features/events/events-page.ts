import { DatePipe } from '@angular/common';
import { HttpErrorResponse } from '@angular/common/http';
import { Component, inject, signal } from '@angular/core';
import { MatButtonModule } from '@angular/material/button';
import { MatDialog } from '@angular/material/dialog';
import { MatIconModule } from '@angular/material/icon';
import { MatProgressBarModule } from '@angular/material/progress-bar';
import { MatSnackBar } from '@angular/material/snack-bar';
import { Router, RouterLink } from '@angular/router';
import { filter, switchMap } from 'rxjs';
import { CurrentUser } from '../../core/auth/current-user';
import { EventInput, EventSummary } from '../../core/events/event';
import { EventsApi } from '../../core/events/events-api';
import { EventDialog, EventDialogData } from './dialogs/event-dialog';

/** List of events; the current one (prepared on the players) comes first. */
@Component({
  selector: 'app-events-page',
  imports: [DatePipe, RouterLink, MatButtonModule, MatIconModule, MatProgressBarModule],
  templateUrl: './events-page.html',
  styleUrl: './events-page.scss',
})
export class EventsPage {
  private readonly api = inject(EventsApi);
  private readonly dialog = inject(MatDialog);
  private readonly router = inject(Router);
  private readonly snackBar = inject(MatSnackBar);
  protected readonly currentUser = inject(CurrentUser);

  protected readonly events = signal<EventSummary[] | null>(null);
  protected readonly loadError = signal(false);

  constructor() {
    this.api.list().subscribe({
      next: (events) => this.events.set(events),
      error: () => this.loadError.set(true),
    });
  }

  protected create(): void {
    this.dialog
      .open<EventDialog, EventDialogData, EventInput>(EventDialog, { data: { heading: 'Novo evento', value: null } })
      .afterClosed()
      .pipe(
        filter((input): input is EventInput => !!input),
        switchMap((input) => this.api.create(input)),
      )
      .subscribe({
        next: (event) => void this.router.navigate(['/eventos', event.id]),
        error: (e: HttpErrorResponse) => this.snackBar.open(`Não foi possível criar (${e.status}).`, 'OK', { duration: 4000 }),
      });
  }
}
