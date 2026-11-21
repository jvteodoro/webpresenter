import { Component, inject, signal } from '@angular/core';
import { MatButtonModule } from '@angular/material/button';
import { MAT_DIALOG_DATA, MatDialogModule } from '@angular/material/dialog';
import { MatProgressSpinnerModule } from '@angular/material/progress-spinner';
import { MediaAsset } from '../../../core/media/media';
import { MediaApi } from '../../../core/media/media-api';
import { DurationPipe } from '../../../shared/pipes/duration.pipe';
import { FileSizePipe } from '../../../shared/pipes/file-size.pipe';

/** Plays or shows a media asset from a short-lived direct link. */
@Component({
  selector: 'app-media-preview-dialog',
  imports: [MatDialogModule, MatButtonModule, MatProgressSpinnerModule, DurationPipe, FileSizePipe],
  templateUrl: './media-preview-dialog.html',
  styleUrl: './media-preview-dialog.scss',
})
export class MediaPreviewDialog {
  protected readonly asset = inject<MediaAsset>(MAT_DIALOG_DATA);
  protected readonly url = signal<string | null>(null);
  protected readonly failed = signal(false);

  constructor() {
    inject(MediaApi)
      .downloadUrl(this.asset.id)
      .subscribe({
        next: (link) => this.url.set(link.url),
        error: () => this.failed.set(true),
      });
  }
}
