import { HttpErrorResponse } from '@angular/common/http';
import { Component, DestroyRef, computed, inject, signal } from '@angular/core';
import { takeUntilDestroyed } from '@angular/core/rxjs-interop';
import { FormsModule } from '@angular/forms';
import { MatButtonModule } from '@angular/material/button';
import { MatDialog } from '@angular/material/dialog';
import { MatFormFieldModule } from '@angular/material/form-field';
import { MatInputModule } from '@angular/material/input';
import { MatIconModule } from '@angular/material/icon';
import { MatProgressBarModule } from '@angular/material/progress-bar';
import { MatSnackBar } from '@angular/material/snack-bar';
import { filter, switchMap, timer } from 'rxjs';
import { CurrentUser } from '../../core/auth/current-user';
import { Device, DeviceSettings } from '../../core/devices/device';
import { DevicesApi } from '../../core/devices/devices-api';
import { ConfirmDialog, ConfirmDialogData } from '../../shared/dialogs/confirm-dialog';
import { TextPromptDialog, TextPromptDialogData } from '../../shared/dialogs/text-prompt-dialog';
import { DeviceCard } from './device-card/device-card';
import { DeviceSettingsDialog } from './device-settings-dialog/device-settings-dialog';

/** Polling interval; the list is small and lives on the LAN. Live updates arrive with the M5 WebSocket. */
const REFRESH_MS = 3000;

/** MediaPlayers: approve new ones, configure and remove. */
@Component({
  selector: 'app-devices-page',
  imports: [FormsModule, MatButtonModule, MatFormFieldModule, MatInputModule, MatIconModule, MatProgressBarModule, DeviceCard],
  templateUrl: './devices-page.html',
  styleUrl: './devices-page.scss',
})
export class DevicesPage {
  private readonly api = inject(DevicesApi);
  private readonly dialog = inject(MatDialog);
  private readonly snackBar = inject(MatSnackBar);
  protected readonly currentUser = inject(CurrentUser);

  protected readonly devices = signal<Device[] | null>(null);
  protected readonly loadError = signal(false);
  protected readonly pending = computed(() => (this.devices() ?? []).filter((d) => d.status === 'PENDING'));
  protected readonly approved = computed(() => (this.devices() ?? []).filter((d) => d.status === 'APPROVED'));

  constructor() {
    timer(0, REFRESH_MS)
      .pipe(
        switchMap(() => this.api.list()),
        takeUntilDestroyed(inject(DestroyRef)),
      )
      .subscribe({
        next: (devices) => {
          this.devices.set(devices);
          this.loadError.set(false);
        },
        error: () => this.loadError.set(true),
      });
  }

  protected readonly code = signal('');
  protected readonly codeError = signal<string | null>(null);

  /** Finds the pending player showing this code (case, spaces and dashes ignored) and asks for its name. */
  protected approveByCode(): void {
    const wanted = normalizeCode(this.code());
    const device = this.pending().find((d) => normalizeCode(d.pairingCode ?? '') === wanted);
    if (!device) {
      this.codeError.set('Nenhum player aguardando aprovação com esse código. Confira o telão e se o player está ligado a este servidor.');
      return;
    }
    this.code.set('');
    this.openApprove(device);
  }

  protected openApprove(device: Device): void {
    this.dialog
      .open<TextPromptDialog, TextPromptDialogData, string>(TextPromptDialog, {
        data: { title: `Aprovar player ${device.pairingCode}`, label: 'Nome do player', value: device.hostname, maxLength: 100 },
      })
      .afterClosed()
      .pipe(
        filter((name): name is string => !!name),
        switchMap((name) => this.api.approve(device.id, name)),
      )
      .subscribe({
        next: (updated) => {
          this.replace(updated);
          this.notify(`"${updated.name}" aprovado.`);
        },
        error: (e: HttpErrorResponse) => this.notify(e.error?.detail ?? `Não foi possível aprovar (${e.status}).`),
      });
  }

  protected openSettings(device: Device): void {
    this.dialog
      .open<DeviceSettingsDialog, Device, DeviceSettings>(DeviceSettingsDialog, { data: device })
      .afterClosed()
      .pipe(
        filter((settings): settings is DeviceSettings => !!settings),
        switchMap((settings) => this.api.configure(device.id, settings)),
      )
      .subscribe({
        next: (updated) => {
          this.replace(updated);
          this.notify(updated.online ? 'Configuração enviada ao player.' : 'Configuração salva; será enviada quando o player conectar.');
        },
        error: (e: HttpErrorResponse) => this.notify(e.error?.detail ?? `Não foi possível salvar (${e.status}).`),
      });
  }

  protected confirmRemove(device: Device): void {
    const pending = device.status === 'PENDING';
    this.dialog
      .open<ConfirmDialog, ConfirmDialogData, boolean>(ConfirmDialog, {
        data: {
          title: pending ? 'Recusar player' : 'Remover player',
          message: pending
            ? `O player "${device.name}" será recusado. Se continuar ligado, aparecerá de novo como pendente.`
            : `"${device.name}" deixará de receber conteúdo e precisará ser aprovado novamente.`,
          confirmLabel: pending ? 'Recusar' : 'Remover',
          destructive: true,
        },
      })
      .afterClosed()
      .pipe(
        filter(Boolean),
        switchMap(() => this.api.remove(device.id)),
      )
      .subscribe({
        next: () => this.devices.update((list) => (list ?? []).filter((d) => d.id !== device.id)),
        error: (e: HttpErrorResponse) => this.notify(`Não foi possível remover (${e.status}).`),
      });
  }

  private replace(device: Device): void {
    this.devices.update((list) => (list ?? []).map((d) => (d.id === device.id ? device : d)));
  }

  private notify(message: string): void {
    this.snackBar.open(message, 'OK', { duration: 4000 });
  }
}

function normalizeCode(code: string): string {
  return code.toUpperCase().replace(/[^A-Z0-9]/g, '');
}
