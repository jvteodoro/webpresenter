import { DatePipe } from '@angular/common';
import { HttpErrorResponse } from '@angular/common/http';
import { Component, inject, signal } from '@angular/core';
import { FormsModule, NgForm } from '@angular/forms';
import { MatButtonModule } from '@angular/material/button';
import { MatDialogModule } from '@angular/material/dialog';
import { MatFormFieldModule } from '@angular/material/form-field';
import { MatIconModule } from '@angular/material/icon';
import { MatInputModule } from '@angular/material/input';
import { MatSlideToggleModule } from '@angular/material/slide-toggle';
import { MatSnackBar } from '@angular/material/snack-bar';
import { MatTooltipModule } from '@angular/material/tooltip';
import { ConfirmDialog, ConfirmDialogData } from '../../../shared/dialogs/confirm-dialog';
import { MatDialog } from '@angular/material/dialog';
import { filter, switchMap } from 'rxjs';
import { VideoSource, VideoSourcesApi, VideoSourceImport } from '../../../core/video-sources/video-sources';

const STATUS_LABELS: Record<VideoSourceImport['status'], string> = {
  QUEUED: 'na fila',
  DONE: 'baixado',
  FAILED: 'não encontrado ainda',
};

/**
 * Recurring videos: YouTube channels whose video of each event date is downloaded to the misc section
 * (searched daily from a week before each event, until published).
 */
@Component({
  selector: 'app-video-sources-dialog',
  imports: [DatePipe, FormsModule, MatButtonModule, MatDialogModule, MatFormFieldModule, MatIconModule, MatInputModule,
    MatSlideToggleModule, MatTooltipModule],
  template: `
    <h2 mat-dialog-title>Vídeos recorrentes</h2>
    <mat-dialog-content class="content">
      <p class="hint">
        Cole o link do canal do YouTube (ex.: https://www.youtube.com/@Canal). Para cada data de evento, o webpresenter
        baixa o vídeo que tem a data no título (ou enviado nesse dia) para os Diversos.
      </p>

      <form class="add" #form="ngForm" (ngSubmit)="add(form)">
        <mat-form-field appearance="outline" subscriptSizing="dynamic">
          <mat-label>Nome</mat-label>
          <input matInput name="name" [(ngModel)]="name" required maxlength="120" />
        </mat-form-field>
        <mat-form-field appearance="outline" subscriptSizing="dynamic" class="url">
          <mat-label>Link do canal</mat-label>
          <input matInput name="url" [(ngModel)]="url" required placeholder="https://www.youtube.com/@Canal" />
        </mat-form-field>
        <button mat-flat-button type="submit" [disabled]="busy() || !name.trim() || !url.trim()">
          <mat-icon>add</mat-icon> Adicionar
        </button>
      </form>

      <ul class="sources" aria-label="Canais">
        @for (s of sources(); track s.id) {
          <li class="source">
            <div class="head">
              <strong>{{ s.name }}</strong>
              <mat-slide-toggle [checked]="s.enabled" (change)="toggle(s, $event.checked)" matTooltip="Buscar os vídeos automaticamente" />
              <button mat-icon-button type="button" (click)="confirmDelete(s)" aria-label="Remover canal">
                <mat-icon>delete</mat-icon>
              </button>
            </div>
            <a class="link" [href]="s.channelUrl" target="_blank" rel="noopener">{{ s.channelUrl }}</a>

            <div class="manual">
              <input type="date" [value]="dayFor(s.id)" (input)="setDay(s.id, $any($event.target).value)" aria-label="Data do evento" />
              <button mat-stroked-button type="button" [disabled]="!dayFor(s.id) || busy()" (click)="downloadDay(s)">
                <mat-icon>download</mat-icon> Baixar esta data
              </button>
            </div>

            @if (s.imports.length) {
              <ul class="imports">
                @for (i of s.imports.slice(0, 8); track i.eventDate) {
                  <li>
                    {{ i.eventDate | date: 'dd/MM/yyyy' }} · {{ statusLabel(i.status) }}
                    @if (i.message && i.status !== 'DONE') { <span class="why">({{ i.message }})</span> }
                  </li>
                }
              </ul>
            }
          </li>
        } @empty {
          <li class="empty">Nenhum canal ainda.</li>
        }
      </ul>
    </mat-dialog-content>
    <mat-dialog-actions align="end">
      <button mat-button mat-dialog-close>Fechar</button>
    </mat-dialog-actions>
  `,
  styles: `
    .content { display: flex; flex-direction: column; gap: 0.75rem; }
    .hint { margin: 0; color: var(--mat-sys-on-surface-variant); font: var(--mat-sys-body-small); }
    .add { display: flex; gap: 0.5rem; align-items: center; flex-wrap: wrap; }
    .add .url { flex: 1 1 16rem; }
    .sources { list-style: none; margin: 0; padding: 0; display: flex; flex-direction: column; gap: 0.75rem; }
    .source { border: 1px solid var(--mat-sys-outline-variant); border-radius: 12px; padding: 0.75rem; }
    .head { display: flex; align-items: center; gap: 0.5rem; }
    .head strong { flex: 1; }
    .link { font: var(--mat-sys-body-small); word-break: break-all; }
    .manual { display: flex; gap: 0.5rem; align-items: center; margin-top: 0.5rem; flex-wrap: wrap; }
    .imports { margin: 0.5rem 0 0; padding-left: 1.2rem; font: var(--mat-sys-body-small); }
    .why { color: var(--mat-sys-on-surface-variant); }
    .empty { color: var(--mat-sys-on-surface-variant); }
  `,
})
export class VideoSourcesDialog {
  private readonly api = inject(VideoSourcesApi);
  private readonly dialog = inject(MatDialog);
  private readonly snackBar = inject(MatSnackBar);

  protected readonly sources = signal<VideoSource[]>([]);
  protected readonly busy = signal(false);
  protected name = '';
  protected url = '';
  private readonly days = new Map<string, string>();

  constructor() {
    this.reload();
  }

  protected dayFor(id: string): string {
    return this.days.get(id) ?? '';
  }

  protected setDay(id: string, value: string): void {
    this.days.set(id, value);
  }

  protected statusLabel(status: VideoSourceImport['status']): string {
    return STATUS_LABELS[status];
  }

  protected add(form: NgForm): void {
    this.busy.set(true);
    this.api.create({ name: this.name.trim(), channelUrl: this.url.trim(), enabled: true }).subscribe({
      next: () => {
        form.resetForm();
        this.name = '';
        this.url = '';
        this.busy.set(false);
        this.reload();
      },
      error: (e: HttpErrorResponse) => this.fail(e, 'Não foi possível adicionar o canal.'),
    });
  }

  protected toggle(source: VideoSource, enabled: boolean): void {
    this.api.update(source.id, { name: source.name, channelUrl: source.channelUrl, enabled }).subscribe({
      next: () => this.reload(),
      error: (e: HttpErrorResponse) => this.fail(e, 'Não foi possível alterar o canal.'),
    });
  }

  protected confirmDelete(source: VideoSource): void {
    this.dialog
      .open<ConfirmDialog, ConfirmDialogData, boolean>(ConfirmDialog, {
        data: {
          title: 'Remover canal',
          message: `O canal "${source.name}" deixa de ser buscado. Os vídeos já baixados permanecem nos Diversos.`,
          confirmLabel: 'Remover',
          destructive: true,
        },
      })
      .afterClosed()
      .pipe(filter(Boolean), switchMap(() => this.api.delete(source.id)))
      .subscribe({
        next: () => this.reload(),
        error: (e: HttpErrorResponse) => this.fail(e, 'Não foi possível remover o canal.'),
      });
  }

  protected downloadDay(source: VideoSource): void {
    const day = this.dayFor(source.id);
    this.busy.set(true);
    this.api.importDay(source.id, day).subscribe({
      next: () => {
        this.busy.set(false);
        this.snackBar.open('Download na fila. Acompanhe em Fila de processamento.', 'OK', { duration: 5000 });
        this.reload();
      },
      error: (e: HttpErrorResponse) => this.fail(e, 'Não foi possível baixar esta data.'),
    });
  }

  private reload(): void {
    this.api.list().subscribe({ next: (list) => this.sources.set(list), error: () => undefined });
  }

  private fail(e: HttpErrorResponse, fallback: string): void {
    this.busy.set(false);
    this.snackBar.open(e.error?.detail ?? fallback, 'OK', { duration: 6000 });
  }
}
