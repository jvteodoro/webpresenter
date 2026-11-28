import { HttpErrorResponse } from '@angular/common/http';
import { Component, inject, signal } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { MatButtonModule } from '@angular/material/button';
import { MatButtonToggleModule } from '@angular/material/button-toggle';
import { MAT_DIALOG_DATA, MatDialogModule, MatDialogRef } from '@angular/material/dialog';
import { MatFormFieldModule } from '@angular/material/form-field';
import { MatIconModule } from '@angular/material/icon';
import { MatInputModule } from '@angular/material/input';
import { MatSelectModule } from '@angular/material/select';
import { IMPORT_TARGET_LABELS, ImportFormat, ImportTarget, Job } from '../../core/jobs/job';
import { JobsApi } from '../../core/jobs/jobs-api';

export interface ImportDialogData {
  target?: ImportTarget;
}

/** Queues a download from a YouTube (or other yt-dlp supported) link. Closes with the queued job. */
@Component({
  selector: 'app-import-dialog',
  imports: [FormsModule, MatButtonModule, MatButtonToggleModule, MatDialogModule, MatFormFieldModule, MatIconModule, MatInputModule, MatSelectModule],
  template: `
    <h2 mat-dialog-title>Importar do YouTube</h2>
    <mat-dialog-content class="content">
      <mat-form-field appearance="outline">
        <mat-label>Link do vídeo</mat-label>
        <input matInput [(ngModel)]="url" placeholder="https://www.youtube.com/watch?v=…" cdkFocusInitial required />
      </mat-form-field>
      <mat-form-field appearance="outline">
        <mat-label>Destino</mat-label>
        <mat-select [(ngModel)]="target" (ngModelChange)="onTarget($event)">
          @for (t of targets; track t) {
            <mat-option [value]="t">{{ targetLabels[t] }}</mat-option>
          }
        </mat-select>
      </mat-form-field>
      <div class="format">
        <span class="label">Baixar</span>
        <mat-button-toggle-group [(ngModel)]="format" [disabled]="target === 'SOUND_EFFECT'" hideSingleSelectionIndicator aria-label="Formato">
          <mat-button-toggle value="AUDIO"><mat-icon>music_note</mat-icon> Só o áudio</mat-button-toggle>
          <mat-button-toggle value="VIDEO"><mat-icon>movie</mat-icon> Vídeo</mat-button-toggle>
        </mat-button-toggle-group>
      </div>
      <mat-form-field appearance="outline">
        <mat-label>Título (opcional)</mat-label>
        <input matInput [(ngModel)]="title" maxlength="300" placeholder="Usa o título do vídeo se ficar vazio" />
      </mat-form-field>
      <p class="hint">O download entra na fila e aparece na biblioteca quando terminar. Baixe apenas conteúdo que a igreja pode usar.</p>
      @if (error()) { <p class="error" role="alert">{{ error() }}</p> }
    </mat-dialog-content>
    <mat-dialog-actions align="end">
      <button mat-button mat-dialog-close>Cancelar</button>
      <button mat-flat-button [disabled]="!url.trim() || sending()" (click)="submit()">Importar</button>
    </mat-dialog-actions>
  `,
  styles: `
    .content { display: flex; flex-direction: column; gap: 0.25rem; width: min(34rem, 80vw); padding-top: 0.5rem; }
    .format { display: flex; align-items: center; gap: 0.75rem; margin-bottom: 1rem; flex-wrap: wrap; }
    .format mat-icon { margin-right: 0.3rem; vertical-align: middle; }
    .label { color: var(--mat-sys-on-surface-variant); }
    .hint { margin: 0; font-size: 0.85rem; color: var(--mat-sys-on-surface-variant); }
    .error { color: var(--mat-sys-error); }
  `,
})
export class ImportDialog {
  private readonly api = inject(JobsApi);
  private readonly ref = inject(MatDialogRef<ImportDialog, Job>);
  private readonly data = inject<ImportDialogData | null>(MAT_DIALOG_DATA, { optional: true });

  protected readonly targets: ImportTarget[] = ['BACKGROUND_MUSIC', 'SOUND_EFFECT', 'MEDIA', 'MISC', 'MUSICAL_MESSAGE', 'SONG'];
  protected readonly targetLabels = IMPORT_TARGET_LABELS;
  protected url = '';
  protected target: ImportTarget = this.data?.target ?? 'BACKGROUND_MUSIC';
  protected format: ImportFormat = this.target === 'MEDIA' ? 'VIDEO' : 'AUDIO';
  protected title = '';
  protected readonly sending = signal(false);
  protected readonly error = signal<string | null>(null);

  protected onTarget(target: ImportTarget): void {
    this.format = target === 'MEDIA' ? 'VIDEO' : 'AUDIO';
  }

  protected submit(): void {
    this.sending.set(true);
    this.error.set(null);
    this.api.importFromUrl(this.url.trim(), this.target, this.format, this.title.trim() || null).subscribe({
      next: (job) => this.ref.close(job),
      error: (e: HttpErrorResponse) => {
        this.sending.set(false);
        this.error.set(e.status === 400 ? 'Link inválido: use um link http(s) de um vídeo.' : `Não foi possível importar (${e.status}).`);
      },
    });
  }
}
