import { Component, computed, inject, signal } from '@angular/core';
import { toSignal } from '@angular/core/rxjs-interop';
import { FormsModule } from '@angular/forms';
import { MatButtonModule } from '@angular/material/button';
import { MAT_DIALOG_DATA, MatDialogModule, MatDialogRef } from '@angular/material/dialog';
import { MatFormFieldModule } from '@angular/material/form-field';
import { MatInputModule } from '@angular/material/input';
import { MatSelectModule } from '@angular/material/select';
import { Device, DeviceSettings, displayLabel } from '../../../core/devices/device';
import { MediaApi } from '../../../core/media/media-api';

/** Name, output display and rest screen of an approved player. Closes with the new settings. */
@Component({
  selector: 'app-device-settings-dialog',
  imports: [FormsModule, MatDialogModule, MatButtonModule, MatFormFieldModule, MatInputModule, MatSelectModule],
  templateUrl: './device-settings-dialog.html',
  styles: `
    .form {
      display: flex;
      flex-direction: column;
      gap: 0.25rem;
      width: min(30rem, 75vw);
      padding-top: 0.5rem;
    }
  `,
})
export class DeviceSettingsDialog {
  protected readonly device = inject<Device>(MAT_DIALOG_DATA);
  private readonly ref = inject(MatDialogRef<DeviceSettingsDialog, DeviceSettings>);

  protected readonly displayLabel = displayLabel;
  protected readonly name = signal(this.device.name);
  protected readonly displayIndex = signal(this.device.displayIndex);
  protected readonly restMediaId = signal<string | null>(this.device.restScreen?.mediaId ?? null);

  /** Images and videos can be rest screens. */
  private readonly media = toSignal(inject(MediaApi).list(), { initialValue: [] });
  protected readonly restOptions = computed(() => this.media().filter((m) => m.kind !== 'AUDIO'));

  protected save(): void {
    if (!this.name().trim()) {
      return;
    }
    this.ref.close({ name: this.name().trim(), displayIndex: this.displayIndex(), restMediaId: this.restMediaId() });
  }
}
