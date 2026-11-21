import { HttpErrorResponse } from '@angular/common/http';
import { Component, computed, inject, signal } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { MatButtonToggleModule } from '@angular/material/button-toggle';
import { MatDialog } from '@angular/material/dialog';
import { MatFormFieldModule } from '@angular/material/form-field';
import { MatIconModule } from '@angular/material/icon';
import { MatInputModule } from '@angular/material/input';
import { MatProgressBarModule } from '@angular/material/progress-bar';
import { MatSnackBar } from '@angular/material/snack-bar';
import { filter, switchMap } from 'rxjs';
import { CurrentUser } from '../../core/auth/current-user';
import { MediaAsset, MediaKind } from '../../core/media/media';
import { MediaApi } from '../../core/media/media-api';
import { normalizeText } from '../../shared/text';
import { ConfirmDialog, ConfirmDialogData } from '../../shared/dialogs/confirm-dialog';
import { TextPromptDialog, TextPromptDialogData } from '../../shared/dialogs/text-prompt-dialog';
import { MediaPreviewDialog } from './media-preview-dialog/media-preview-dialog';
import { MediaTable } from './media-table/media-table';
import { MediaUpload } from './media-upload/media-upload';

/** Media library: upload, search, preview, rename and delete. */
@Component({
  selector: 'app-library-page',
  imports: [
    FormsModule,
    MatButtonToggleModule,
    MatFormFieldModule,
    MatIconModule,
    MatInputModule,
    MatProgressBarModule,
    MediaTable,
    MediaUpload,
  ],
  templateUrl: './library-page.html',
  styleUrl: './library-page.scss',
})
export class LibraryPage {
  private readonly api = inject(MediaApi);
  private readonly dialog = inject(MatDialog);
  private readonly snackBar = inject(MatSnackBar);
  protected readonly currentUser = inject(CurrentUser);

  protected readonly assets = signal<MediaAsset[]>([]);
  protected readonly loading = signal(true);
  protected readonly loadError = signal(false);
  protected readonly search = signal('');
  protected readonly kind = signal<MediaKind | 'ALL'>('ALL');

  protected readonly visible = computed(() => {
    const term = normalizeText(this.search());
    const kind = this.kind();
    return this.assets().filter(
      (a) =>
        (kind === 'ALL' || a.kind === kind) &&
        (!term || normalizeText(a.title).includes(term) || normalizeText(a.originalFilename).includes(term)),
    );
  });

  constructor() {
    this.reload();
  }

  protected reload(): void {
    this.loading.set(true);
    this.api.list().subscribe({
      next: (assets) => {
        this.assets.set(assets);
        this.loadError.set(false);
        this.loading.set(false);
      },
      error: () => {
        this.loadError.set(true);
        this.loading.set(false);
      },
    });
  }

  protected onUploaded(asset: MediaAsset): void {
    this.assets.update((assets) => [asset, ...assets.filter((a) => a.id !== asset.id)]);
  }

  protected openPreview(asset: MediaAsset): void {
    this.dialog.open(MediaPreviewDialog, { data: asset, width: 'min(960px, 95vw)', maxWidth: '95vw' });
  }

  protected openRename(asset: MediaAsset): void {
    this.dialog
      .open<TextPromptDialog, TextPromptDialogData, string>(TextPromptDialog, {
        data: { title: 'Renomear mídia', label: 'Título', value: asset.title, maxLength: 300 },
      })
      .afterClosed()
      .pipe(
        filter((title): title is string => !!title && title !== asset.title),
        switchMap((title) => this.api.rename(asset.id, title)),
      )
      .subscribe({
        next: (updated) => this.assets.update((assets) => assets.map((a) => (a.id === updated.id ? updated : a))),
        error: (e: HttpErrorResponse) => this.notify(`Não foi possível renomear (${e.status}).`),
      });
  }

  protected confirmDelete(asset: MediaAsset): void {
    this.dialog
      .open<ConfirmDialog, ConfirmDialogData, boolean>(ConfirmDialog, {
        data: {
          title: 'Excluir mídia',
          message: `"${asset.title}" será removida da biblioteca. Esta ação não pode ser desfeita.`,
          confirmLabel: 'Excluir',
          destructive: true,
        },
      })
      .afterClosed()
      .pipe(
        filter(Boolean),
        switchMap(() => this.api.delete(asset.id)),
      )
      .subscribe({
        next: () => {
          this.assets.update((assets) => assets.filter((a) => a.id !== asset.id));
          this.notify(`"${asset.title}" excluída.`);
        },
        error: (e: HttpErrorResponse) =>
          this.notify(
            e.status === 409 && e.error?.usages
              ? `"${asset.title}" está em uso no ${(e.error.usages as string[]).join(', ')}. Remova-a de lá antes de excluir.`
              : `Não foi possível excluir (${e.status}).`,
          ),
      });
  }

  private notify(message: string): void {
    this.snackBar.open(message, 'OK', { duration: 6000 });
  }
}
