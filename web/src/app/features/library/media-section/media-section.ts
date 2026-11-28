import { HttpErrorResponse } from '@angular/common/http';
import { Component, computed, effect, inject, input, signal, untracked } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { MatButtonToggleModule } from '@angular/material/button-toggle';
import { MatDialog } from '@angular/material/dialog';
import { MatFormFieldModule } from '@angular/material/form-field';
import { MatIconModule } from '@angular/material/icon';
import { MatInputModule } from '@angular/material/input';
import { MatProgressBarModule } from '@angular/material/progress-bar';
import { MatSnackBar } from '@angular/material/snack-bar';
import { filter, switchMap } from 'rxjs';
import { CurrentUser } from '../../../core/auth/current-user';
import { MediaAsset, MediaKind, MediaPurpose } from '../../../core/media/media';
import { ImportTarget, Job, isActive } from '../../../core/jobs/job';
import { JobsStream } from '../../../core/jobs/jobs-stream';
import { ImportDialog, ImportDialogData } from '../../jobs/import-dialog';
import { MatButtonModule } from '@angular/material/button';
import { MediaApi } from '../../../core/media/media-api';
import { normalizeText } from '../../../shared/text';
import { ConfirmDialog, ConfirmDialogData } from '../../../shared/dialogs/confirm-dialog';
import { TextPromptDialog, TextPromptDialogData } from '../../../shared/dialogs/text-prompt-dialog';
import { MediaPreviewDialog } from '../media-preview-dialog/media-preview-dialog';
import { MediaTable } from '../media-table/media-table';
import { VideoSourcesDialog } from '../video-sources/video-sources-dialog';
import { MediaUpload } from '../media-upload/media-upload';

const DEFAULT_HINT = 'Arraste vídeos, áudios ou imagens para cá';
const DEFAULT_EMPTY = 'Nenhuma mídia na biblioteca ainda.';

/** One section of the library (general media, background music, sound effects): upload, search, preview, rename, delete. */
@Component({
  selector: 'app-media-section',
  imports: [
    FormsModule,
    MatButtonModule,
    MatButtonToggleModule,
    MatFormFieldModule,
    MatIconModule,
    MatInputModule,
    MatProgressBarModule,
    MediaTable,
    MediaUpload,
  ],
  templateUrl: './media-section.html',
  styleUrl: './media-section.scss',
})
export class MediaSection {
  /** Route data: the purpose listed and given to uploads. */
  // Route inputs absent from the route data arrive as undefined: fall back to the defaults.
  readonly purpose = input('GENERAL' as MediaPurpose, { transform: (v: MediaPurpose | undefined) => v ?? 'GENERAL' });
  /** Route data: file types accepted by the upload. */
  readonly accept = input('video/*,audio/*,image/*', { transform: (v: string | undefined) => v ?? 'video/*,audio/*,image/*' });
  readonly hint = input(DEFAULT_HINT, { transform: (v: string | undefined) => v ?? DEFAULT_HINT });
  readonly emptyText = input(DEFAULT_EMPTY, { transform: (v: string | undefined) => v ?? DEFAULT_EMPTY });
  /** Route data: show the video/audio/image filter. */
  readonly kindFilter = input(true, { transform: (v: boolean | undefined) => v ?? true });
  /** Route data: destination of "Importar do YouTube" in this section (none: no button). */
  readonly importTarget = input<ImportTarget | undefined>(undefined);
  /** Route data: the section offers the recurring-video channels (a video per event date). */
  readonly videoSources = input(false, { transform: (v: boolean | undefined) => v ?? false });

  private readonly jobs = inject(JobsStream);
  /** Downloads into this section seen in progress; the list reloads when one finishes. */
  private readonly followed = new Set<string>();
  private readonly sectionImports = computed(() =>
    this.jobs.jobs().filter((j) => j.type === 'YOUTUBE_IMPORT' && j.params['target'] === this.importTarget()),
  );
  protected readonly importing = computed(() => this.sectionImports().filter(isActive));

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
    effect(() => {
      let finished = false;
      for (const job of this.sectionImports()) {
        if (isActive(job)) {
          this.followed.add(job.id);
        } else if (this.followed.delete(job.id) && job.status === 'DONE') {
          finished = true;
        }
      }
      if (finished) {
        untracked(() => this.reload());
      }
    });
    effect(() => {
      this.purpose();
      untracked(() => this.reload());
    });
  }

  protected openVideoSources(): void {
    this.dialog.open(VideoSourcesDialog, { width: '44rem', maxWidth: '94vw' });
  }

  protected reload(): void {
    this.loading.set(true);
    this.api.list([this.purpose()]).subscribe({
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

  protected openImport(): void {
    this.dialog
      .open<ImportDialog, ImportDialogData, Job>(ImportDialog, { data: { target: this.importTarget() } })
      .afterClosed()
      .subscribe((job) => {
        if (job) {
          this.jobs.apply(job);
          this.notify('Download na fila. A mídia aparece aqui quando terminar.');
        }
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
