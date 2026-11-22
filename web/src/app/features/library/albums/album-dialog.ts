import { HttpErrorResponse } from '@angular/common/http';
import { Component, inject, signal } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { MatButtonModule } from '@angular/material/button';
import { MAT_DIALOG_DATA, MatDialogModule, MatDialogRef } from '@angular/material/dialog';
import { MatFormFieldModule } from '@angular/material/form-field';
import { MatIconModule } from '@angular/material/icon';
import { MatInputModule } from '@angular/material/input';
import { MatProgressBarModule } from '@angular/material/progress-bar';
import { Album, AlbumInput } from '../../../core/library/album';
import { MediaApi } from '../../../core/media/media-api';

/** Create or edit an album: name, group, description and cover (uploaded here). Closes with the input. */
@Component({
  selector: 'app-album-dialog',
  imports: [FormsModule, MatButtonModule, MatDialogModule, MatFormFieldModule, MatIconModule, MatInputModule, MatProgressBarModule],
  template: `
    <h2 mat-dialog-title>{{ data ? 'Editar álbum' : 'Novo álbum' }}</h2>
    <mat-dialog-content class="content">
      <div class="cover-row">
        <div class="cover">
          @if (coverPreview()) {
            <img [src]="coverPreview()" alt="Capa do álbum" />
          } @else {
            <mat-icon>album</mat-icon>
          }
        </div>
        <div class="cover-actions">
          <button mat-stroked-button type="button" (click)="picker.click()" [disabled]="uploading()">
            <mat-icon>image</mat-icon> {{ coverMediaId() ? 'Trocar capa' : 'Escolher capa' }}
          </button>
          @if (coverMediaId()) {
            <button mat-button type="button" (click)="removeCover()">Remover capa</button>
          }
          <input #picker type="file" hidden accept="image/*" (change)="uploadCover(picker)" />
          @if (uploading()) { <mat-progress-bar mode="indeterminate" /> }
          @if (error()) { <span class="error">{{ error() }}</span> }
        </div>
      </div>
      <mat-form-field appearance="outline">
        <mat-label>Nome</mat-label>
        <input matInput [(ngModel)]="name" maxlength="200" required cdkFocusInitial />
      </mat-form-field>
      <mat-form-field appearance="outline">
        <mat-label>Grupo (opcional)</mat-label>
        <input matInput [(ngModel)]="group" maxlength="100" placeholder="Ex.: Jovens, Infantis" />
      </mat-form-field>
      <mat-form-field appearance="outline">
        <mat-label>Descrição (opcional)</mat-label>
        <textarea matInput [(ngModel)]="description" maxlength="2000" rows="3"></textarea>
      </mat-form-field>
    </mat-dialog-content>
    <mat-dialog-actions align="end">
      <button mat-button mat-dialog-close>Cancelar</button>
      <button mat-flat-button [disabled]="!name.trim() || uploading()" (click)="save()">Salvar</button>
    </mat-dialog-actions>
  `,
  styles: `
    .content { display: flex; flex-direction: column; gap: 0.25rem; width: min(32rem, 80vw); }
    .cover-row { display: flex; gap: 1rem; align-items: center; margin: 0.5rem 0 1rem; }
    .cover { width: 7rem; height: 7rem; flex: none; border-radius: 8px; overflow: hidden; display: grid; place-items: center;
      background: var(--mat-sys-surface-container-high); color: var(--mat-sys-on-surface-variant); }
    .cover img { width: 100%; height: 100%; object-fit: cover; }
    .cover mat-icon { font-size: 3rem; width: 3rem; height: 3rem; }
    .cover-actions { display: flex; flex-direction: column; align-items: flex-start; gap: 0.4rem; }
    .error { color: var(--mat-sys-error); font-size: 0.85rem; }
  `,
})
export class AlbumDialog {
  protected readonly data = inject<Album | null>(MAT_DIALOG_DATA, { optional: true });
  private readonly ref = inject(MatDialogRef<AlbumDialog, AlbumInput>);
  private readonly media = inject(MediaApi);

  protected name = this.data?.name ?? '';
  protected group = this.data?.group ?? '';
  protected description = this.data?.description ?? '';
  protected readonly coverMediaId = signal<string | null>(this.data?.coverMediaId ?? null);
  protected readonly coverPreview = signal<string | null>(this.data?.coverUrl ?? null);
  protected readonly uploading = signal(false);
  protected readonly error = signal<string | null>(null);

  protected uploadCover(input: HTMLInputElement): void {
    const file = input.files?.[0];
    input.value = '';
    if (!file) {
      return;
    }
    this.uploading.set(true);
    this.error.set(null);
    this.media.upload(file, `Capa - ${this.name.trim() || file.name}`, 'COVER').subscribe({
      next: (progress) => {
        if (progress.state === 'done') {
          this.coverMediaId.set(progress.asset.id);
          this.coverPreview.set(URL.createObjectURL(file));
          this.uploading.set(false);
        }
      },
      error: (e: HttpErrorResponse) => {
        this.error.set(`Não foi possível enviar a imagem (${e.status}).`);
        this.uploading.set(false);
      },
    });
  }

  protected removeCover(): void {
    this.coverMediaId.set(null);
    this.coverPreview.set(null);
  }

  protected save(): void {
    this.ref.close({
      name: this.name.trim(),
      group: this.group.trim() || null,
      description: this.description.trim() || null,
      coverMediaId: this.coverMediaId(),
    });
  }
}
