import { HttpErrorResponse } from '@angular/common/http';
import { Component, OnDestroy, inject, input, signal } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { MatIconModule } from '@angular/material/icon';
import { MatSliderModule } from '@angular/material/slider';
import { MatSnackBar } from '@angular/material/snack-bar';
import { Subject, debounceTime, switchMap } from 'rxjs';
import { MixerLevels } from '../../core/audio/background';
import { SettingsApi } from '../../core/audio/settings-api';

interface Channel {
  key: keyof MixerLevels;
  label: string;
  icon: string;
}

/** Mixer faders (master, media, background music, effects); changes reach the players live. */
@Component({
  selector: 'app-mixer-panel',
  imports: [FormsModule, MatIconModule, MatSliderModule],
  template: `
    @if (levels(); as l) {
      <div class="channels" [class.compact]="compact()">
        @for (c of channels; track c.key) {
          <label class="channel">
            <span class="name"><mat-icon>{{ c.icon }}</mat-icon> {{ c.label }}</span>
            <mat-slider min="0" max="100" step="1" discrete [disabled]="disabled()">
              <input matSliderThumb [ngModel]="l[c.key]" (ngModelChange)="set(c.key, $event)" [attr.aria-label]="'Volume ' + c.label" />
            </mat-slider>
            <span class="value">{{ l[c.key] }}</span>
          </label>
        }
      </div>
    }
  `,
  styles: `
    .channels { display: grid; gap: 0.25rem 1rem; grid-template-columns: repeat(auto-fit, minmax(14rem, 1fr)); }
    .channel { display: grid; grid-template-columns: 9.5rem 1fr 2.2rem; align-items: center; gap: 0.5rem; }
    .compact .channel { grid-template-columns: 8rem 1fr 2rem; }
    .name { display: inline-flex; align-items: center; gap: 0.35rem; font-size: 0.9rem; }
    .name mat-icon { color: var(--mat-sys-primary); font-size: 1.2rem; width: 1.2rem; height: 1.2rem; }
    mat-slider { min-width: 0; margin: 0; }
    .value { text-align: right; font-variant-numeric: tabular-nums; color: var(--mat-sys-on-surface-variant); }
  `,
})
export class MixerPanel implements OnDestroy {
  private readonly api = inject(SettingsApi);
  private readonly snackBar = inject(MatSnackBar);

  readonly compact = input(false);
  readonly disabled = input(false);

  protected readonly channels: Channel[] = [
    { key: 'master', label: 'Geral', icon: 'volume_up' },
    { key: 'media', label: 'Mídia', icon: 'movie' },
    { key: 'background', label: 'Fundo musical', icon: 'graphic_eq' },
    { key: 'effects', label: 'Efeitos', icon: 'notifications_active' },
  ];
  protected readonly levels = signal<MixerLevels | null>(null);
  private readonly changes = new Subject<MixerLevels>();
  private readonly subscription = this.changes
    .pipe(
      debounceTime(150),
      switchMap((levels) => this.api.updateLevels(levels)),
    )
    .subscribe({
      error: (e: HttpErrorResponse) => this.snackBar.open(`Não foi possível ajustar o volume (${e.status}).`, 'OK', { duration: 5000 }),
    });

  constructor() {
    this.api.audio().subscribe((a) =>
      this.levels.set({ master: a.master, media: a.media, background: a.background, effects: a.effects }),
    );
  }

  protected set(key: keyof MixerLevels, value: number): void {
    const next = { ...this.levels()!, [key]: value };
    this.levels.set(next);
    this.changes.next(next);
  }

  ngOnDestroy(): void {
    this.subscription.unsubscribe();
  }
}
