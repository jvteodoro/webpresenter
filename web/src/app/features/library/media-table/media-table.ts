import { DatePipe } from '@angular/common';
import { Component, input, output } from '@angular/core';
import { MatButtonModule } from '@angular/material/button';
import { MatIconModule } from '@angular/material/icon';
import { MatMenuModule } from '@angular/material/menu';
import { MatTableModule } from '@angular/material/table';
import { MatTooltipModule } from '@angular/material/tooltip';
import { MEDIA_KIND_ICONS, MEDIA_KIND_LABELS, MediaAsset } from '../../../core/media/media';
import { DurationPipe } from '../../../shared/pipes/duration.pipe';
import { FileSizePipe } from '../../../shared/pipes/file-size.pipe';

/** Presentational list of media assets. Actions are reported to the parent. */
@Component({
  selector: 'app-media-table',
  imports: [DatePipe, MatTableModule, MatButtonModule, MatIconModule, MatMenuModule, MatTooltipModule, DurationPipe, FileSizePipe],
  templateUrl: './media-table.html',
  styleUrl: './media-table.scss',
})
export class MediaTable {
  readonly items = input.required<MediaAsset[]>();
  readonly canManage = input(false);

  readonly preview = output<MediaAsset>();
  readonly rename = output<MediaAsset>();
  readonly remove = output<MediaAsset>();

  protected readonly columns = ['kind', 'title', 'duration', 'size', 'createdAt', 'actions'];

  protected kindIcon(item: MediaAsset): string {
    return MEDIA_KIND_ICONS[item.kind];
  }

  protected kindLabel(item: MediaAsset): string {
    return MEDIA_KIND_LABELS[item.kind];
  }
}
