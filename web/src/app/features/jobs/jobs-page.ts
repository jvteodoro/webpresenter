import { HttpErrorResponse } from '@angular/common/http';
import { DatePipe } from '@angular/common';
import { Component, OnDestroy, OnInit, inject } from '@angular/core';
import { MatButtonModule } from '@angular/material/button';
import { MatDialog } from '@angular/material/dialog';
import { MatIconModule } from '@angular/material/icon';
import { MatProgressBarModule } from '@angular/material/progress-bar';
import { MatSnackBar } from '@angular/material/snack-bar';
import { RouterLink } from '@angular/router';
import { CurrentUser } from '../../core/auth/current-user';
import { ImportTarget, JOB_STATUS_LABELS, JOB_TYPE_ICONS, Job, isActive } from '../../core/jobs/job';
import { JobsApi } from '../../core/jobs/jobs-api';
import { JobsStream } from '../../core/jobs/jobs-stream';
import { ImportDialog, ImportDialogData } from './import-dialog';

const SECTION_BY_TARGET: Record<ImportTarget, string> = {
  BACKGROUND_MUSIC: '/biblioteca/fundos',
  SOUND_EFFECT: '/biblioteca/efeitos',
  MEDIA: '/biblioteca/midia',
  SONG: '/musicas',
};

/** The processing queue, live: downloads and vocal separation with their progress. */
@Component({
  selector: 'app-jobs-page',
  imports: [DatePipe, MatButtonModule, MatIconModule, MatProgressBarModule, RouterLink],
  template: `
    <header class="page-header">
      <div>
        <h1>Fila de processamento</h1>
        <p class="lead">Downloads do YouTube e geração de playback. O progresso atualiza sozinho.</p>
      </div>
      @if (currentUser.canManageLibrary()) {
        <button mat-flat-button (click)="openImport()"><mat-icon>download</mat-icon> Importar do YouTube</button>
      }
    </header>
    @if (!stream.isConnected() && stream.jobs().length === 0) {
      <mat-progress-bar mode="indeterminate" />
    }
    <ul class="jobs" aria-label="Trabalhos">
      @for (job of stream.jobs(); track job.id) {
        <li class="job" [class.failed]="job.status === 'FAILED'" [attr.aria-label]="job.title">
          <mat-icon class="type">{{ icons[job.type] }}</mat-icon>
          <div class="main">
            <div class="top">
              <span class="title">{{ job.title }}</span>
              <span class="status" [attr.data-status]="job.status">{{ labels[job.status] }}</span>
            </div>
            @if (job.status === 'RUNNING') {
              <mat-progress-bar mode="determinate" [value]="job.progress" [attr.aria-label]="'Progresso ' + job.progress + '%'" />
            } @else if (job.status === 'QUEUED') {
              <mat-progress-bar mode="buffer" [value]="0" [bufferValue]="0" aria-label="Aguardando" />
            }
            <span class="meta">
              @if (job.status === 'QUEUED') {
                Aguardando um worker livre ·
              } @else if (job.message) {
                {{ job.message }} ·
              }
              {{ job.createdBy }} · {{ job.createdAt | date: 'dd/MM HH:mm' }}
              @if (job.attempt > 1) { · tentativa {{ job.attempt }} }
            </span>
          </div>
          <div class="actions">
            @if (job.status === 'DONE' && resultLink(job); as link) {
              <a mat-stroked-button [routerLink]="link">Abrir</a>
            }
            @if (currentUser.canManageLibrary()) {
              @if (active(job)) {
                <button mat-button (click)="cancel(job)">Cancelar</button>
              } @else {
                @if (job.status === 'FAILED' || job.status === 'CANCELLED') {
                  <button mat-button (click)="retry(job)">Tentar novamente</button>
                }
                <button mat-icon-button (click)="remove(job)" [attr.aria-label]="'Remover ' + job.title"><mat-icon>close</mat-icon></button>
              }
            }
          </div>
        </li>
      } @empty {
        @if (stream.isConnected()) {
          <li class="empty">Nada na fila. Importe um vídeo do YouTube ou gere o playback de uma música na página dela.</li>
        }
      }
    </ul>
  `,
  styles: `
    :host { display: flex; flex-direction: column; gap: 1rem; }
    .page-header { display: flex; justify-content: space-between; align-items: flex-start; gap: 1rem; flex-wrap: wrap; }
    h1 { margin: 0; font: var(--mat-sys-headline-small); }
    .lead { margin: 0.25rem 0 0; color: var(--mat-sys-on-surface-variant); }
    .jobs { list-style: none; margin: 0; padding: 0; display: flex; flex-direction: column; gap: 0.5rem; }
    .job { display: flex; align-items: center; gap: 0.9rem; padding: 0.8rem 1rem; border-radius: var(--mat-sys-corner-medium);
      border: 1px solid var(--mat-sys-outline-variant); background: var(--mat-sys-surface-container-low); }
    .job.failed { border-color: var(--mat-sys-error); }
    .type { color: var(--mat-sys-primary); flex: none; }
    .main { flex: 1; min-width: 0; display: flex; flex-direction: column; gap: 0.35rem; }
    .top { display: flex; align-items: center; gap: 0.6rem; min-width: 0; }
    .title { font-weight: 500; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
    .status { font-size: 0.75rem; padding: 0.1rem 0.55rem; border-radius: 999px; flex: none;
      background: var(--mat-sys-surface-container-highest); color: var(--mat-sys-on-surface-variant); }
    .status[data-status='RUNNING'] { background: var(--mat-sys-primary-container); color: var(--mat-sys-on-primary-container); }
    .status[data-status='DONE'] { background: var(--mat-sys-secondary-container); color: var(--mat-sys-on-secondary-container); }
    .status[data-status='FAILED'] { background: var(--mat-sys-error-container); color: var(--mat-sys-on-error-container); }
    .meta { font-size: 0.85rem; color: var(--mat-sys-on-surface-variant); }
    .actions { display: flex; align-items: center; gap: 0.25rem; flex: none; }
    .empty { text-align: center; color: var(--mat-sys-on-surface-variant); padding: 2rem 1rem; }
    @media (max-width: 600px) { .job { flex-wrap: wrap; } .actions { width: 100%; justify-content: flex-end; } }
  `,
})
export class JobsPage implements OnInit, OnDestroy {
  protected readonly stream = inject(JobsStream);
  private readonly api = inject(JobsApi);
  private readonly dialog = inject(MatDialog);
  private readonly snackBar = inject(MatSnackBar);
  protected readonly currentUser = inject(CurrentUser);
  protected readonly labels = JOB_STATUS_LABELS;
  protected readonly icons = JOB_TYPE_ICONS;
  protected readonly active = isActive;

  ngOnInit(): void {
    this.stream.attach();
  }

  ngOnDestroy(): void {
    this.stream.detach();
  }

  protected resultLink(job: Job): string[] | null {
    if (job.result?.songId) {
      return ['/musicas', job.result.songId];
    }
    const target = job.params['target'] as ImportTarget | undefined;
    return target ? [SECTION_BY_TARGET[target]] : null;
  }

  protected openImport(): void {
    this.dialog
      .open<ImportDialog, ImportDialogData, Job>(ImportDialog, { data: {} })
      .afterClosed()
      .subscribe((job) => job && this.stream.apply(job));
  }

  protected cancel(job: Job): void {
    this.api.cancel(job.id).subscribe({ next: (j) => this.stream.apply(j), error: (e) => this.fail(e) });
  }

  protected retry(job: Job): void {
    this.api.retry(job.id).subscribe({ next: (j) => this.stream.apply(j), error: (e) => this.fail(e) });
  }

  protected remove(job: Job): void {
    this.api.delete(job.id).subscribe({ next: () => this.stream.remove(job.id), error: (e) => this.fail(e) });
  }

  private fail(e: HttpErrorResponse): void {
    this.snackBar.open(e.error?.detail ?? `Não foi possível concluir (${e.status}).`, 'OK', { duration: 6000 });
  }
}
