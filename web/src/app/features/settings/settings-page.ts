import { HttpErrorResponse } from '@angular/common/http';
import { Component, inject, signal } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { MatButtonModule } from '@angular/material/button';
import { MatDialog } from '@angular/material/dialog';
import { MatFormFieldModule } from '@angular/material/form-field';
import { MatIconModule } from '@angular/material/icon';
import { MatInputModule } from '@angular/material/input';
import { MatProgressBarModule } from '@angular/material/progress-bar';
import { MatSliderModule } from '@angular/material/slider';
import { MatSnackBar } from '@angular/material/snack-bar';
import { filter } from 'rxjs';
import { CurrentUser } from '../../core/auth/current-user';
import { AudioSettings } from '../../core/audio/background';
import { SettingsApi } from '../../core/audio/settings-api';
import { BackgroundChoice, BackgroundPickerData, BackgroundPickerDialog } from '../audio/background-picker-dialog';

/** System settings: background music (default, fades, ducking) and the mixer's levels. */
@Component({
  selector: 'app-settings-page',
  imports: [FormsModule, MatButtonModule, MatFormFieldModule, MatIconModule, MatInputModule, MatProgressBarModule, MatSliderModule],
  template: `
    <header class="page-header">
      <h1>Configurações</h1>
    </header>
    @if (form(); as f) {
      <section class="card">
        <h2>Fundo musical</h2>
        <p class="lead">Toca baixinho quando nada mais tem som: tela de descanso, slides, imagens e músicas sem áudio.
          Um item usa o fundo dele; sem isso, o da cena; depois o do evento; por fim, o padrão da plataforma, definido aqui.</p>
        <div class="row">
          <span class="label">Padrão da plataforma</span>
          <span class="current" aria-label="Fundo musical padrão"><mat-icon>graphic_eq</mat-icon> {{ backgroundName() }}</span>
          <button mat-stroked-button (click)="chooseDefault()" [disabled]="!canEdit()">Escolher</button>
        </div>
        <div class="fields">
          <mat-form-field appearance="outline">
            <mat-label>Entrada (fade in), em segundos</mat-label>
            <input matInput type="number" min="0" max="30" step="0.5" [ngModel]="f.fadeInMs / 1000" (ngModelChange)="patch({ fadeInMs: seconds($event) })" [disabled]="!canEdit()" />
          </mat-form-field>
          <mat-form-field appearance="outline">
            <mat-label>Saída (fade out), em segundos</mat-label>
            <input matInput type="number" min="0" max="30" step="0.5" [ngModel]="f.fadeOutMs / 1000" (ngModelChange)="patch({ fadeOutMs: seconds($event) })" [disabled]="!canEdit()" />
          </mat-form-field>
        </div>
        <label class="slider-row">
          <span class="label">Durante vídeos e músicas com áudio</span>
          <mat-slider min="0" max="60" step="5" discrete [disabled]="!canEdit()">
            <input matSliderThumb [ngModel]="f.duckPercent" (ngModelChange)="patch({ duckPercent: $event })" aria-label="Nível do fundo durante mídias com som" />
          </mat-slider>
          <span class="value">{{ f.duckPercent === 0 ? 'pausa' : f.duckPercent + '%' }}</span>
        </label>
        <p class="hint">0 (pausa): o fundo sai com fade e volta depois. Acima de 0: continua tocando nesse nível, por baixo.</p>
      </section>

      <section class="card">
        <h2>Mixer</h2>
        <p class="lead">Volume de cada canal nos players (0–100). Também ajustável ao vivo, na tela Ao vivo.</p>
        @for (c of channels; track c.key) {
          <label class="slider-row">
            <span class="label"><mat-icon>{{ c.icon }}</mat-icon> {{ c.label }}</span>
            <mat-slider min="0" max="100" step="1" discrete [disabled]="!canEdit()">
              <input matSliderThumb [ngModel]="f[c.key]" (ngModelChange)="setLevel(c.key, $event)" [attr.aria-label]="'Volume ' + c.label" />
            </mat-slider>
            <span class="value">{{ f[c.key] }}</span>
          </label>
        }
      </section>

      @if (canEdit()) {
        <div class="actions">
          <button mat-flat-button (click)="save()" [disabled]="saving() || !dirty()">Salvar</button>
          @if (dirty()) { <span class="hint">Alterações não salvas</span> }
        </div>
      }
    } @else {
      <mat-progress-bar mode="indeterminate" />
    }
  `,
  styles: `
    :host { display: flex; flex-direction: column; gap: 1rem; max-width: 52rem; }
    h1 { margin: 0; font: var(--mat-sys-headline-small); }
    h2 { margin: 0 0 0.5rem; font: var(--mat-sys-title-medium); }
    .card { padding: 1rem 1.25rem; border-radius: var(--mat-sys-corner-large); background: var(--mat-sys-surface-container-low);
      border: 1px solid var(--mat-sys-outline-variant); }
    .lead, .hint { margin: 0 0 1rem; color: var(--mat-sys-on-surface-variant); font-size: 0.9rem; }
    .row { display: flex; align-items: center; gap: 0.75rem; flex-wrap: wrap; margin-bottom: 1rem; }
    .current { display: inline-flex; align-items: center; gap: 0.35rem; font-weight: 500; }
    .current mat-icon, .label mat-icon { color: var(--mat-sys-primary); vertical-align: middle; }
    .fields { display: flex; gap: 1rem; flex-wrap: wrap; }
    .fields mat-form-field { flex: 1; min-width: 14rem; }
    .slider-row { display: grid; grid-template-columns: minmax(10rem, 16rem) 1fr 3.5rem; align-items: center; gap: 0.75rem; }
    .label { display: inline-flex; align-items: center; gap: 0.35rem; }
    .value { text-align: right; font-variant-numeric: tabular-nums; color: var(--mat-sys-on-surface-variant); }
    mat-slider { min-width: 0; }
    .actions { display: flex; align-items: center; gap: 1rem; }
    @media (max-width: 600px) { .slider-row { grid-template-columns: 1fr 3rem; } .slider-row .label { grid-column: 1 / -1; } }
  `,
})
export class SettingsPage {
  private readonly api = inject(SettingsApi);
  private readonly dialog = inject(MatDialog);
  private readonly snackBar = inject(MatSnackBar);
  private readonly currentUser = inject(CurrentUser);

  protected readonly channels = [
    { key: 'master', label: 'Geral', icon: 'volume_up' },
    { key: 'media', label: 'Mídia', icon: 'movie' },
    { key: 'background', label: 'Fundo musical', icon: 'graphic_eq' },
    { key: 'effects', label: 'Efeitos', icon: 'notifications_active' },
  ] as const;
  protected readonly form = signal<AudioSettings | null>(null);
  protected readonly backgroundName = signal('Silêncio');
  protected readonly saving = signal(false);
  protected readonly dirty = signal(false);
  protected readonly canEdit = this.currentUser.canManageLibrary;

  constructor() {
    this.api.audio().subscribe((a) => this.loaded(a));
  }

  protected patch(change: Partial<AudioSettings>): void {
    this.form.update((f) => f && { ...f, ...change });
    this.dirty.set(true);
  }

  protected setLevel(key: 'master' | 'media' | 'background' | 'effects', value: number): void {
    this.patch({ [key]: value } as Partial<AudioSettings>);
  }

  protected seconds(value: number | string): number {
    const n = Number(value);
    return Number.isFinite(n) ? Math.round(Math.min(30, Math.max(0, n)) * 1000) : 0;
  }

  protected chooseDefault(): void {
    this.dialog
      .open<BackgroundPickerDialog, BackgroundPickerData, BackgroundChoice>(BackgroundPickerDialog, {
        data: { title: 'Fundo musical padrão da plataforma', current: this.form()?.defaultBackground ?? null },
      })
      .afterClosed()
      .pipe(filter((choice): choice is BackgroundChoice => !!choice))
      .subscribe((choice) => {
        const source = choice.source?.kind === 'SILENCE' ? null : choice.source;
        this.patch({ defaultBackground: source });
        this.backgroundName.set(source ? 'Escolhido (salve para aplicar)' : 'Silêncio');
      });
  }

  protected save(): void {
    const f = this.form();
    if (!f) {
      return;
    }
    this.saving.set(true);
    this.api.updateAudio(f).subscribe({
      next: (a) => {
        this.loaded(a);
        this.saving.set(false);
        this.snackBar.open('Configurações salvas. Os players já usam os novos valores.', 'OK', { duration: 4000 });
      },
      error: (e: HttpErrorResponse) => {
        this.saving.set(false);
        this.snackBar.open(e.error?.detail ?? `Não foi possível salvar (${e.status}).`, 'OK', { duration: 6000 });
      },
    });
  }

  private loaded(a: AudioSettings): void {
    this.form.set(a);
    this.backgroundName.set(a.defaultBackgroundName ?? 'Silêncio');
    this.dirty.set(false);
  }
}
