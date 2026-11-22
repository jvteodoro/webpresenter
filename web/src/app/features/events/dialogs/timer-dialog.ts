import { Component, inject, signal } from '@angular/core';
import { toSignal } from '@angular/core/rxjs-interop';
import { FormsModule } from '@angular/forms';
import { MatButtonModule } from '@angular/material/button';
import { MAT_DIALOG_DATA, MatDialogModule, MatDialogRef } from '@angular/material/dialog';
import { MatFormFieldModule } from '@angular/material/form-field';
import { MatIconModule } from '@angular/material/icon';
import { MatInputModule } from '@angular/material/input';
import { MatRadioModule } from '@angular/material/radio';
import { MatSelectModule } from '@angular/material/select';
import { catchError, map, of } from 'rxjs';
import { TimerMode, TimerSettings, TimerView } from '../../../core/events/event';
import { MediaApi } from '../../../core/media/media-api';

export interface TimerDialogData {
  label: string | null;
  timer: TimerView | null;
}

export interface TimerDialogResult {
  label: string | null;
  timer: TimerSettings;
}

interface SignalRow {
  minutes: number;
  soundMediaId: string | null;
}

/** A timer: what it counts to (scene end, a time, a duration) and its sound signals. */
@Component({
  selector: 'app-timer-dialog',
  imports: [FormsModule, MatButtonModule, MatDialogModule, MatFormFieldModule, MatIconModule, MatInputModule, MatRadioModule, MatSelectModule],
  template: `
    <h2 mat-dialog-title>{{ data.timer ? 'Editar cronômetro' : 'Novo cronômetro' }}</h2>
    <mat-dialog-content class="content">
      <mat-form-field appearance="outline">
        <mat-label>Texto acima do cronômetro (opcional)</mat-label>
        <input matInput [(ngModel)]="label" maxlength="200" placeholder="Ex.: Escola Sabatina" />
      </mat-form-field>

      <span class="section">Contar até</span>
      <mat-radio-group class="modes" [ngModel]="mode()" (ngModelChange)="mode.set($event)" aria-label="Contar até">
        <mat-radio-button value="SCENE_END">O fim da cena (horário de fim da cena deste item)</mat-radio-button>
        <mat-radio-button value="TIME">Um horário</mat-radio-button>
        <mat-radio-button value="DURATION">Uma duração, a partir de quando o cronômetro entra</mat-radio-button>
      </mat-radio-group>
      @if (mode() === 'TIME') {
        <mat-form-field appearance="outline" class="narrow">
          <mat-label>Horário</mat-label>
          <input matInput type="time" [(ngModel)]="time" required />
        </mat-form-field>
      }
      @if (mode() === 'DURATION') {
        <div class="row">
          <mat-form-field appearance="outline" class="narrow">
            <mat-label>Minutos</mat-label>
            <input matInput type="number" min="0" max="1440" [(ngModel)]="minutes" />
          </mat-form-field>
          <mat-form-field appearance="outline" class="narrow">
            <mat-label>Segundos</mat-label>
            <input matInput type="number" min="0" max="59" [(ngModel)]="seconds" />
          </mat-form-field>
        </div>
      }

      <span class="section">Sinais sonoros</span>
      <p class="hint">Tocam no canal Efeitos do mixer. Use 0 minuto para tocar no fim.</p>
      @for (s of signals(); track $index; let i = $index) {
        <div class="row signal">
          <mat-form-field appearance="outline" class="narrow">
            <mat-label>Minutos antes do fim</mat-label>
            <input matInput type="number" min="0" max="1440" [(ngModel)]="s.minutes" [attr.aria-label]="'Minutos antes do fim, sinal ' + (i + 1)" />
          </mat-form-field>
          <mat-form-field appearance="outline" class="sound">
            <mat-label>Som do sinal {{ i + 1 }}</mat-label>
            <mat-select [(ngModel)]="s.soundMediaId">
              @for (m of sounds(); track m.id) {
                <mat-option [value]="m.id">{{ m.title }}</mat-option>
              } @empty {
                <mat-option disabled>Nenhum som. Envie em Biblioteca → Efeitos sonoros.</mat-option>
              }
            </mat-select>
          </mat-form-field>
          <button mat-icon-button type="button" (click)="removeSignal(i)" [attr.aria-label]="'Remover sinal ' + (i + 1)"><mat-icon>close</mat-icon></button>
        </div>
      }
      <button mat-stroked-button type="button" class="add" (click)="addSignal()" [disabled]="signals().length >= 10">
        <mat-icon>add_alert</mat-icon> Adicionar sinal
      </button>
    </mat-dialog-content>
    <mat-dialog-actions align="end">
      <button mat-button mat-dialog-close>Cancelar</button>
      <button mat-flat-button [disabled]="!valid()" (click)="save()">Salvar</button>
    </mat-dialog-actions>
  `,
  styles: `
    .content { display: flex; flex-direction: column; width: min(36rem, 85vw); padding-top: 0.5rem; }
    .section { font-weight: 500; margin: 0.25rem 0 0.4rem; }
    .modes { display: flex; flex-direction: column; margin-bottom: 0.75rem; }
    .row { display: flex; gap: 0.75rem; align-items: center; }
    .narrow { width: 10rem; }
    .sound { flex: 1; min-width: 10rem; }
    .hint { margin: 0 0 0.5rem; font-size: 0.85rem; color: var(--mat-sys-on-surface-variant); }
    .add { align-self: flex-start; margin-bottom: 0.5rem; }
  `,
})
export class TimerDialog {
  protected readonly data = inject<TimerDialogData>(MAT_DIALOG_DATA);
  private readonly ref = inject(MatDialogRef<TimerDialog, TimerDialogResult>);

  protected label = this.data.label ?? '';
  protected readonly mode = signal<TimerMode>(this.data.timer?.mode ?? 'SCENE_END');
  protected time = this.data.timer?.time?.slice(0, 5) ?? '';
  protected minutes = Math.floor((this.data.timer?.durationSeconds ?? 300) / 60);
  protected seconds = (this.data.timer?.durationSeconds ?? 300) % 60;
  protected readonly signals = signal<SignalRow[]>(
    (this.data.timer?.signals ?? []).map((s) => ({ minutes: s.beforeEndSeconds / 60, soundMediaId: s.soundMediaId })),
  );
  /** Short sounds first (effects), then other library audio. */
  protected readonly sounds = toSignal(
    inject(MediaApi)
      .list(['SOUND_EFFECT', 'BACKGROUND_MUSIC', 'GENERAL'])
      .pipe(
        map((media) =>
          media
            .filter((m) => m.kind !== 'IMAGE')
            .sort((a, b) => Number(b.purpose === 'SOUND_EFFECT') - Number(a.purpose === 'SOUND_EFFECT')),
        ),
        catchError(() => of([])),
      ),
    { initialValue: [] },
  );

  protected addSignal(): void {
    this.signals.update((rows) => [...rows, { minutes: rows.length === 0 ? 5 : 0, soundMediaId: this.sounds()[0]?.id ?? null }]);
  }

  protected removeSignal(index: number): void {
    this.signals.update((rows) => rows.filter((_, i) => i !== index));
  }

  protected valid(): boolean {
    if (this.mode() === 'TIME' && !this.time) {
      return false;
    }
    if (this.mode() === 'DURATION' && this.durationSeconds() <= 0) {
      return false;
    }
    return this.signals().every((s) => !!s.soundMediaId && s.minutes >= 0);
  }

  protected save(): void {
    const mode = this.mode();
    this.ref.close({
      label: this.label.trim() || null,
      timer: {
        mode,
        time: mode === 'TIME' ? this.time : null,
        durationSeconds: mode === 'DURATION' ? this.durationSeconds() : null,
        signals: this.signals().map((s) => ({ beforeEndSeconds: Math.round(s.minutes * 60), soundMediaId: s.soundMediaId! })),
      },
    });
  }

  private durationSeconds(): number {
    return Math.round((Number(this.minutes) || 0) * 60 + (Number(this.seconds) || 0));
  }
}
