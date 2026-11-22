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
import { TemplateSummary, WEEKDAY_LABELS } from '../../core/liturgy/liturgy';
import { LiturgyApi } from '../../core/liturgy/liturgy-api';
import { TextPromptDialog, TextPromptDialogData } from '../../shared/dialogs/text-prompt-dialog';

/** Liturgy templates: the usual order of the events of each weekday. */
@Component({
  selector: 'app-liturgy-page',
  imports: [MatButtonModule, MatIconModule, MatProgressBarModule, RouterLink],
  template: `
    <header class="page-header">
      <div>
        <h1>Liturgia</h1>
        <p class="lead">Modelos de evento por dia da semana. No calendário, cada dia de um modelo mostra o evento esperado,
          pronto para criar; os espaços variáveis são escolhidos a cada semana.</p>
      </div>
      @if (currentUser.canEditEvents()) {
        <button mat-flat-button (click)="create()"><mat-icon>add</mat-icon> Novo modelo</button>
      }
    </header>
    @if (loading()) { <mat-progress-bar mode="indeterminate" /> }
    <ul class="list">
      @for (t of templates(); track t.id) {
        <li>
          <a class="template" [routerLink]="['/liturgia', t.id]">
            <mat-icon class="icon">event_repeat</mat-icon>
            <span class="main">
              <span class="name">{{ t.name }}</span>
              <span class="meta">{{ days(t) }}@if (t.startTime) { · {{ t.startTime.slice(0, 5) }} }</span>
            </span>
            <span class="meta">{{ t.slotCount }} {{ t.slotCount === 1 ? 'item' : 'itens' }}
              @if (t.variableSlots) { · {{ t.variableSlots }} a escolher }</span>
          </a>
        </li>
      } @empty {
        @if (!loading()) { <li class="empty">Nenhum modelo ainda. Crie, por exemplo, o do sábado.</li> }
      }
    </ul>
  `,
  styles: `
    :host { display: flex; flex-direction: column; gap: 1rem; }
    .page-header { display: flex; justify-content: space-between; align-items: flex-start; gap: 1rem; flex-wrap: wrap; }
    h1 { margin: 0; font: var(--mat-sys-headline-small); }
    .lead { margin: 0.25rem 0 0; color: var(--mat-sys-on-surface-variant); max-width: 48rem; }
    .list { list-style: none; margin: 0; padding: 0; display: flex; flex-direction: column; gap: 0.4rem; }
    .template { display: flex; align-items: center; gap: 0.75rem; padding: 0.75rem 1rem; border-radius: var(--mat-sys-corner-medium);
      border: 1px solid var(--mat-sys-outline-variant); background: var(--mat-sys-surface-container-low); color: inherit; text-decoration: none; }
    .template:hover { background: var(--mat-sys-surface-container); }
    .icon { color: var(--mat-sys-primary); }
    .main { flex: 1; display: flex; flex-direction: column; }
    .name { font-weight: 500; }
    .meta { font-size: 0.85rem; color: var(--mat-sys-on-surface-variant); }
    .empty { text-align: center; color: var(--mat-sys-on-surface-variant); padding: 2rem; }
  `,
})
export class LiturgyPage {
  private readonly api = inject(LiturgyApi);
  private readonly dialog = inject(MatDialog);
  private readonly router = inject(Router);
  private readonly snackBar = inject(MatSnackBar);
  protected readonly currentUser = inject(CurrentUser);
  protected readonly templates = signal<TemplateSummary[]>([]);
  protected readonly loading = signal(true);

  constructor() {
    this.api.list().subscribe({
      next: (t) => {
        this.templates.set(t);
        this.loading.set(false);
      },
      error: () => this.loading.set(false),
    });
  }

  protected days(t: TemplateSummary): string {
    return t.weekdays.length ? t.weekdays.map((d) => WEEKDAY_LABELS[d]).join(', ') : 'Sem dia da semana';
  }

  protected create(): void {
    this.dialog
      .open<TextPromptDialog, TextPromptDialogData, string>(TextPromptDialog, {
        data: { title: 'Novo modelo de liturgia', label: 'Nome', value: '', maxLength: 200 },
      })
      .afterClosed()
      .pipe(
        filter((name): name is string => !!name?.trim()),
        switchMap((name) => this.api.create(name.trim())),
      )
      .subscribe({
        next: (t) => void this.router.navigate(['/liturgia', t.id]),
        error: (e: HttpErrorResponse) => this.snackBar.open(`Não foi possível criar (${e.status}).`, 'OK', { duration: 6000 }),
      });
  }
}
