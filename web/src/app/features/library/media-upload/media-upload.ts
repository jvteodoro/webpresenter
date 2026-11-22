import { HttpErrorResponse } from '@angular/common/http';
import { Component, inject, input, output, signal } from '@angular/core';
import { MatButtonModule } from '@angular/material/button';
import { MatIconModule } from '@angular/material/icon';
import { MatProgressBarModule } from '@angular/material/progress-bar';
import { MediaAsset, MediaPurpose } from '../../../core/media/media';
import { MediaApi } from '../../../core/media/media-api';
import { FileSizePipe } from '../../../shared/pipes/file-size.pipe';

interface UploadItem {
  id: number;
  file: File;
  percent: number;
  status: 'waiting' | 'uploading' | 'processing' | 'done' | 'error';
  error?: string;
}

/** Drop zone and file picker; uploads files one at a time and reports each finished asset. */
@Component({
  selector: 'app-media-upload',
  imports: [MatButtonModule, MatIconModule, MatProgressBarModule, FileSizePipe],
  templateUrl: './media-upload.html',
  styleUrl: './media-upload.scss',
})
export class MediaUpload {
  private readonly api = inject(MediaApi);

  /** Purpose given to every uploaded file. */
  readonly purpose = input<MediaPurpose>('GENERAL');
  /** File types offered by the picker. */
  readonly accept = input('video/*,audio/*,image/*');
  readonly hint = input('Arraste vídeos, áudios ou imagens para cá');

  readonly uploaded = output<MediaAsset>();

  protected readonly items = signal<UploadItem[]>([]);
  protected readonly dragging = signal(false);
  private nextId = 0;
  private running = false;

  protected onFilesSelected(input: HTMLInputElement): void {
    this.enqueue(Array.from(input.files ?? []));
    input.value = '';
  }

  protected onDragOver(event: DragEvent): void {
    event.preventDefault();
    this.dragging.set(true);
  }

  protected onDrop(event: DragEvent): void {
    event.preventDefault();
    this.dragging.set(false);
    this.enqueue(Array.from(event.dataTransfer?.files ?? []));
  }

  protected clearFinished(): void {
    this.items.update((items) => items.filter((i) => i.status !== 'done' && i.status !== 'error'));
  }

  protected hasFinished(): boolean {
    return this.items().some((i) => i.status === 'done' || i.status === 'error');
  }

  private enqueue(files: File[]): void {
    if (files.length === 0) {
      return;
    }
    this.items.update((items) => [
      ...items,
      ...files.map((file): UploadItem => ({ id: this.nextId++, file, percent: 0, status: 'waiting' })),
    ]);
    void this.processQueue();
  }

  private async processQueue(): Promise<void> {
    if (this.running) {
      return;
    }
    this.running = true;
    try {
      let next: UploadItem | undefined;
      while ((next = this.items().find((i) => i.status === 'waiting'))) {
        await this.uploadOne(next.id, next.file);
      }
    } finally {
      this.running = false;
    }
  }

  private uploadOne(id: number, file: File): Promise<void> {
    this.patch(id, { status: 'uploading' });
    return new Promise((resolve) => {
      this.api.upload(file, undefined, this.purpose()).subscribe({
        next: (progress) => {
          if (progress.state === 'uploading') {
            // At 100% the server is still hashing, probing and storing the file.
            this.patch(id, { percent: progress.percent, status: progress.percent >= 100 ? 'processing' : 'uploading' });
          } else {
            this.patch(id, { percent: 100, status: 'done' });
            this.uploaded.emit(progress.asset);
          }
        },
        error: (error: HttpErrorResponse) => {
          this.patch(id, { status: 'error', error: describeError(error) });
          resolve();
        },
        complete: () => resolve(),
      });
    });
  }

  private patch(id: number, changes: Partial<UploadItem>): void {
    this.items.update((items) => items.map((i) => (i.id === id ? { ...i, ...changes } : i)));
  }
}

function describeError(error: HttpErrorResponse): string {
  switch (error.status) {
    case 415:
      return 'Formato não suportado (envie vídeo, áudio ou imagem).';
    case 413:
      return 'Arquivo grande demais.';
    case 403:
      return 'Sem permissão para enviar mídia.';
    case 0:
      return 'Servidor inacessível.';
    default:
      return error.error?.detail ?? `Falha no envio (${error.status}).`;
  }
}
