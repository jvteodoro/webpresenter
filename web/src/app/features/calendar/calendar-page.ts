import { HttpErrorResponse } from '@angular/common/http';
import { Component, computed, inject, signal } from '@angular/core';
import { MatButtonModule } from '@angular/material/button';
import { MatIconModule } from '@angular/material/icon';
import { MatProgressBarModule } from '@angular/material/progress-bar';
import { MatSnackBar } from '@angular/material/snack-bar';
import { MatTooltipModule } from '@angular/material/tooltip';
import { Router, RouterLink } from '@angular/router';
import { forkJoin } from 'rxjs';
import { CurrentUser } from '../../core/auth/current-user';
import { EventSummary } from '../../core/events/event';
import { EventsApi } from '../../core/events/events-api';
import { TemplateSummary, WEEKDAYS } from '../../core/liturgy/liturgy';
import { LiturgyApi } from '../../core/liturgy/liturgy-api';

interface Day {
  date: Date;
  key: string;
  inMonth: boolean;
  today: boolean;
  past: boolean;
  events: EventSummary[];
  /** Templates of this weekday whose event for this date does not exist yet. */
  expected: TemplateSummary[];
}

const MONTHS = ['janeiro', 'fevereiro', 'março', 'abril', 'maio', 'junho', 'julho', 'agosto', 'setembro', 'outubro', 'novembro', 'dezembro'];

/** "YYYY-MM-DD" of a local date. */
function dayKey(d: Date): string {
  return `${d.getFullYear()}-${String(d.getMonth() + 1).padStart(2, '0')}-${String(d.getDate()).padStart(2, '0')}`;
}

/** Month view of events, with the expected events of the liturgy templates. */
@Component({
  selector: 'app-calendar-page',
  imports: [MatButtonModule, MatIconModule, MatProgressBarModule, MatTooltipModule, RouterLink],
  template: `
    <header class="page-header">
      <h1>Calendário</h1>
      <div class="month-nav">
        <button mat-icon-button (click)="shift(-1)" aria-label="Mês anterior"><mat-icon>chevron_left</mat-icon></button>
        <span class="month" aria-live="polite">{{ monthLabel() }}</span>
        <button mat-icon-button (click)="shift(1)" aria-label="Próximo mês"><mat-icon>chevron_right</mat-icon></button>
        <button mat-stroked-button (click)="goToday()">Hoje</button>
      </div>
    </header>
    @if (loading()) { <mat-progress-bar mode="indeterminate" /> }
    <div class="grid" role="grid" aria-label="Dias do mês">
      @for (w of weekdayNames; track w) { <div class="weekday" role="columnheader">{{ w }}</div> }
      @for (d of days(); track d.key) {
        <div class="day" role="gridcell" [class.out]="!d.inMonth" [class.today]="d.today" [attr.aria-label]="d.key">
          <span class="number">{{ d.date.getDate() }}</span>
          @for (e of d.events; track e.id) {
            <a class="event" [routerLink]="['/eventos', e.id]" [class.pending]="e.pendingSlots > 0"
               [matTooltip]="e.pendingSlots ? e.pendingSlots + ' a escolher' : ''">
              @if (time(e); as t) { <span class="time">{{ t }}</span> }
              <span class="title">{{ e.title }}</span>
              @if (e.pendingSlots) { <mat-icon class="warn" aria-hidden="true">edit_calendar</mat-icon> }
            </a>
          }
          @for (t of d.expected; track t.id) {
            <div class="expected" [class.past]="d.past">
              <span class="title">{{ t.name }}</span>
              @if (!d.past && canCreate()) {
                <button mat-button class="create" (click)="create(t, d)" [disabled]="creating()" [attr.aria-label]="'Criar ' + t.name + ' em ' + d.key">Criar</button>
              }
            </div>
          }
        </div>
      }
    </div>
  `,
  styles: `
    :host { display: flex; flex-direction: column; gap: 1rem; }
    .page-header { display: flex; justify-content: space-between; align-items: center; gap: 1rem; flex-wrap: wrap; }
    h1 { margin: 0; font: var(--mat-sys-headline-small); }
    .month-nav { display: flex; align-items: center; gap: 0.25rem; }
    .month { min-width: 11rem; text-align: center; font: var(--mat-sys-title-medium); text-transform: capitalize; }
    .grid { display: grid; grid-template-columns: repeat(7, minmax(0, 1fr)); gap: 1px; background: var(--mat-sys-outline-variant);
      border: 1px solid var(--mat-sys-outline-variant); border-radius: var(--mat-sys-corner-medium); overflow: hidden; }
    .weekday { background: var(--mat-sys-surface-container); padding: 0.4rem; text-align: center; font-size: 0.8rem;
      color: var(--mat-sys-on-surface-variant); }
    .day { background: var(--mat-sys-surface); min-height: 6.5rem; padding: 0.3rem; display: flex; flex-direction: column; gap: 0.2rem; min-width: 0; }
    .day.out { background: var(--mat-sys-surface-container-low); }
    .day.out .number { color: var(--mat-sys-outline); }
    .day.today .number { background: var(--mat-sys-primary); color: var(--mat-sys-on-primary); border-radius: 999px; }
    .number { align-self: flex-start; font-size: 0.8rem; min-width: 1.5rem; text-align: center; padding: 0.05rem 0.3rem; }
    .event, .expected { display: flex; align-items: center; gap: 0.25rem; font-size: 0.78rem; border-radius: 6px; padding: 0.15rem 0.35rem; min-width: 0; }
    .event { background: var(--mat-sys-primary-container); color: var(--mat-sys-on-primary-container); text-decoration: none; }
    .event.pending { outline: 1px dashed var(--mat-sys-primary); }
    .expected { border: 1px dashed var(--mat-sys-outline); color: var(--mat-sys-on-surface-variant); }
    .expected.past { opacity: 0.5; }
    .title { flex: 1; min-width: 0; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
    .time { font-variant-numeric: tabular-nums; font-weight: 500; }
    .warn { font-size: 0.95rem; width: 0.95rem; height: 0.95rem; }
    .create { min-width: 0; height: 1.6rem; padding: 0 0.4rem; font-size: 0.75rem; }
    @media (max-width: 700px) { .day { min-height: 4.5rem; } .time { display: none; } }
  `,
})
export class CalendarPage {
  private readonly events = inject(EventsApi);
  private readonly liturgy = inject(LiturgyApi);
  private readonly router = inject(Router);
  private readonly snackBar = inject(MatSnackBar);
  private readonly currentUser = inject(CurrentUser);

  protected readonly weekdayNames = ['Seg', 'Ter', 'Qua', 'Qui', 'Sex', 'Sáb', 'Dom'];
  protected readonly month = signal(new Date(new Date().getFullYear(), new Date().getMonth(), 1));
  protected readonly loading = signal(true);
  protected readonly creating = signal(false);
  protected readonly canCreate = computed(() => this.currentUser.canEditEvents());
  private readonly eventList = signal<EventSummary[]>([]);
  private readonly templates = signal<TemplateSummary[]>([]);

  protected readonly monthLabel = computed(() => `${MONTHS[this.month().getMonth()]} de ${this.month().getFullYear()}`);

  protected readonly days = computed<Day[]>(() => {
    const first = this.month();
    const start = new Date(first);
    start.setDate(1 - ((first.getDay() + 6) % 7)); // back to Monday
    const todayKey = dayKey(new Date());
    const byDay = new Map<string, EventSummary[]>();
    for (const e of this.eventList()) {
      const key = e.templateDate ?? (e.startsAt ? dayKey(new Date(e.startsAt)) : null);
      if (key) {
        byDay.set(key, [...(byDay.get(key) ?? []), e]);
      }
    }
    const days: Day[] = [];
    for (let i = 0; i < 42; i++) {
      const date = new Date(start.getFullYear(), start.getMonth(), start.getDate() + i);
      const key = dayKey(date);
      const events = (byDay.get(key) ?? []).sort((a, b) => (a.startsAt ?? '').localeCompare(b.startsAt ?? ''));
      const weekday = WEEKDAYS[(date.getDay() + 6) % 7];
      const expected = this.templates().filter(
        (t) => t.weekdays.includes(weekday) && !events.some((e) => e.templateId === t.id),
      );
      days.push({ date, key, inMonth: date.getMonth() === first.getMonth(), today: key === todayKey, past: key < todayKey, events, expected });
    }
    return days;
  });

  constructor() {
    this.load();
  }

  protected shift(months: number): void {
    const m = this.month();
    this.month.set(new Date(m.getFullYear(), m.getMonth() + months, 1));
  }

  protected goToday(): void {
    this.month.set(new Date(new Date().getFullYear(), new Date().getMonth(), 1));
  }

  protected time(e: EventSummary): string | null {
    if (!e.startsAt) {
      return null;
    }
    const d = new Date(e.startsAt);
    return `${String(d.getHours()).padStart(2, '0')}:${String(d.getMinutes()).padStart(2, '0')}`;
  }

  /** Creates the template's event for that day (start time in local time) and opens it. */
  protected create(t: TemplateSummary, d: Day): void {
    let startsAt: string | null = null;
    if (t.startTime) {
      const [h, m] = t.startTime.split(':').map(Number);
      startsAt = new Date(d.date.getFullYear(), d.date.getMonth(), d.date.getDate(), h, m).toISOString();
    }
    this.creating.set(true);
    this.liturgy.createEvent(t.id, d.key, startsAt).subscribe({
      next: (event) => void this.router.navigate(['/eventos', event.id]),
      error: (e: HttpErrorResponse) => {
        this.creating.set(false);
        if (e.status === 409 && e.error?.eventId) {
          void this.router.navigate(['/eventos', e.error.eventId]);
        } else {
          this.snackBar.open(e.error?.detail ?? `Não foi possível criar (${e.status}).`, 'OK', { duration: 6000 });
        }
      },
    });
  }

  private load(): void {
    forkJoin({ events: this.events.list(), templates: this.liturgy.list() }).subscribe({
      next: ({ events, templates }) => {
        this.eventList.set(events);
        this.templates.set(templates);
        this.loading.set(false);
      },
      error: () => this.loading.set(false),
    });
  }
}
