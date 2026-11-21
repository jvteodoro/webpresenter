import { DatePipe } from '@angular/common';
import { Component, computed, input, output } from '@angular/core';
import { MatButtonModule } from '@angular/material/button';
import { MatIconModule } from '@angular/material/icon';
import { MatProgressBarModule } from '@angular/material/progress-bar';
import { MatTooltipModule } from '@angular/material/tooltip';
import { Device, displayLabel } from '../../../core/devices/device';
import { FileSizePipe } from '../../../shared/pipes/file-size.pipe';

/** One MediaPlayer: state, pairing code or settings summary, and actions. */
@Component({
  selector: 'app-device-card',
  imports: [DatePipe, MatButtonModule, MatIconModule, MatProgressBarModule, MatTooltipModule, FileSizePipe],
  templateUrl: './device-card.html',
  styleUrl: './device-card.scss',
})
export class DeviceCard {
  readonly device = input.required<Device>();

  readonly approve = output<Device>();
  readonly configure = output<Device>();
  readonly remove = output<Device>();

  protected readonly displayText = computed(() => {
    const d = this.device();
    const display = d.displays.find((x) => x.index === d.displayIndex);
    return display ? displayLabel(display) : `Tela ${d.displayIndex + 1}`;
  });

  protected readonly cachePercent = computed(() => {
    const c = this.device().cacheStatus;
    return c && c.bytesTotal > 0 ? Math.round((100 * c.bytesReady) / c.bytesTotal) : null;
  });

  protected readonly cacheReady = computed(() => {
    const c = this.device().cacheStatus;
    return !!c && c.itemsReady === c.itemsTotal;
  });
}
