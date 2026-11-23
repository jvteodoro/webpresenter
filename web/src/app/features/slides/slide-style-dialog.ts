import { Component, computed, effect, inject, signal, untracked } from '@angular/core';
import { toSignal } from '@angular/core/rxjs-interop';
import { FormsModule } from '@angular/forms';
import { MatButtonModule } from '@angular/material/button';
import { MatCheckboxModule } from '@angular/material/checkbox';
import { MAT_DIALOG_DATA, MatDialogModule, MatDialogRef } from '@angular/material/dialog';
import { MatIconModule } from '@angular/material/icon';
import { MatSliderModule } from '@angular/material/slider';
import { catchError, map, of } from 'rxjs';
import { SlideStyle } from '../../core/events/event';
import { MediaApi } from '../../core/media/media-api';

export interface SlideStyleDialogData {
  title: string;
  current: SlideStyle | null;
  /** URL of the current background, for the preview. */
  currentBackgroundUrl?: string | null;
  /**
   * What an unset field falls back to ("o do evento ou da música"); absent: no inheriting (the
   * song's own defaults), unset fields mean the platform default.
   */
  inheritLabel?: string;
}

/**
 * Background image, text colour and text size of slides. With inheriting, each field can be left
 * to the level below (item → event → song). Closes with the style; cancel closes with nothing.
 */
@Component({
  selector: 'app-slide-style-dialog',
  imports: [FormsModule, MatButtonModule, MatCheckboxModule, MatDialogModule, MatIconModule, MatSliderModule],
  template: `
    <h2 mat-dialog-title>{{ data.title }}</h2>
    <mat-dialog-content class="content">
      <div class="preview" [style.background-image]="previewUrl() ? 'url(' + previewUrl() + ')' : null"
           aria-label="Prévia do slide">
        <span class="shade"></span>
        <span class="sample" [style.color]="color() ?? '#ffffff'" [style.font-size.%]="(scale() ?? 100) * 1.5">
          Castelo forte é nosso Deus<br />Espada e bom escudo
        </span>
      </div>

      <section class="field">
        <span class="name">Fundo</span>
        @if (data.inheritLabel) {
          <mat-checkbox [checked]="background() === undefined" (change)="background.set($event.checked ? undefined : null)">
            Usar {{ data.inheritLabel }}
          </mat-checkbox>
        }
        @if (background() !== undefined) {
          <div class="images" role="listbox" aria-label="Imagens de fundo">
            <button type="button" class="image none" [class.selected]="background() === null" (click)="background.set(null)"
                    aria-label="Fundo padrão">Padrão</button>
            @for (m of images(); track m.id) {
              <button type="button" class="image" [class.selected]="background() === m.id" (click)="background.set(m.id)"
                      [attr.aria-label]="m.title" [title]="m.title"
                      [style.background-image]="thumbs()[m.id] ? 'url(' + thumbs()[m.id] + ')' : null">
                <span class="label">{{ m.title }}</span>
              </button>
            }
          </div>
        }
      </section>

      <section class="field">
        <span class="name">Cor do texto</span>
        @if (data.inheritLabel) {
          <mat-checkbox [checked]="color() === undefined" (change)="color.set($event.checked ? undefined : '#ffffff')">
            Usar {{ data.inheritLabel }}
          </mat-checkbox>
        }
        @if (color() !== undefined) {
          <div class="row">
            <input type="color" [ngModel]="color() ?? '#ffffff'" (ngModelChange)="color.set($event)" aria-label="Cor do texto" />
            @for (c of swatches; track c) {
              <button type="button" class="swatch" [style.background]="c" (click)="color.set(c)" [attr.aria-label]="'Cor ' + c"></button>
            }
            @if (!data.inheritLabel) {
              <button mat-button type="button" (click)="color.set(null)">Branco (padrão)</button>
            }
          </div>
        }
      </section>

      <section class="field">
        <span class="name">Tamanho do texto</span>
        @if (data.inheritLabel) {
          <mat-checkbox [checked]="scale() === undefined" (change)="scale.set($event.checked ? undefined : 100)">
            Usar {{ data.inheritLabel }}
          </mat-checkbox>
        }
        @if (scale() !== undefined) {
          <div class="row">
            <mat-slider min="50" max="200" step="5" discrete>
              <input matSliderThumb [ngModel]="scale() ?? 100" (ngModelChange)="scale.set($event)" aria-label="Tamanho do texto" />
            </mat-slider>
            <span class="value">{{ scale() ?? 100 }}%</span>
          </div>
        }
      </section>
    </mat-dialog-content>
    <mat-dialog-actions align="end">
      <button mat-button mat-dialog-close>Cancelar</button>
      <button mat-flat-button (click)="save()">Aplicar</button>
    </mat-dialog-actions>
  `,
  styles: `
    .content { display: flex; flex-direction: column; gap: 1rem; width: min(36rem, 85vw); }
    .preview { position: relative; aspect-ratio: 16 / 9; border-radius: 8px; overflow: hidden; display: grid; place-items: center;
      background: linear-gradient(135deg, #1d2b53, #0b1020) center / cover; }
    .preview[style*='url'] { background-size: cover; background-position: center; }
    .shade { position: absolute; inset: 0; background: rgba(0, 0, 0, 0.45); } /* as on the players */
    .sample { position: relative; font-weight: 700; text-align: center; line-height: 1.25; text-shadow: 0 2px 8px rgba(0, 0, 0, 0.85); }
    .field { display: flex; flex-direction: column; gap: 0.35rem; }
    .name { font-weight: 500; }
    .row { display: flex; align-items: center; gap: 0.5rem; flex-wrap: wrap; }
    input[type='color'] { width: 3rem; height: 2.2rem; border: 0; background: none; padding: 0; }
    .swatch { width: 1.8rem; height: 1.8rem; border-radius: 50%; border: 1px solid var(--mat-sys-outline); cursor: pointer; }
    mat-slider { flex: 1; min-width: 12rem; }
    .value { width: 3.5rem; text-align: right; font-variant-numeric: tabular-nums; }
    .images { display: grid; grid-template-columns: repeat(auto-fill, minmax(6.5rem, 1fr)); gap: 0.4rem; max-height: 14rem; overflow: auto; }
    .image { position: relative; aspect-ratio: 16 / 9; border-radius: 6px; border: 2px solid transparent; cursor: pointer;
      background: var(--mat-sys-surface-container-high) center / cover; color: inherit; font: inherit; padding: 0; overflow: hidden; }
    .image.selected { border-color: var(--mat-sys-primary); }
    .image.none { display: grid; place-items: center; background: linear-gradient(135deg, #1d2b53, #0b1020); color: white; font-size: 0.8rem; }
    .label { position: absolute; left: 0; right: 0; bottom: 0; padding: 0.1rem 0.3rem; font-size: 0.7rem; text-align: left;
      background: rgba(0, 0, 0, 0.55); color: white; white-space: nowrap; overflow: hidden; text-overflow: ellipsis; }
  `,
})
export class SlideStyleDialog {
  protected readonly data = inject<SlideStyleDialogData>(MAT_DIALOG_DATA);
  private readonly ref = inject(MatDialogRef<SlideStyleDialog, SlideStyle>);
  private readonly media = inject(MediaApi);

  protected readonly swatches = ['#ffffff', '#fff3c4', '#ffd27a', '#bfe3ff', '#000000'];
  /** undefined = inherit (only with inheriting); null = default; a value = this. */
  protected readonly background = signal<string | null | undefined>(this.initial(this.data.current?.backgroundMediaId));
  protected readonly color = signal<string | null | undefined>(this.initial(this.data.current?.textColor));
  protected readonly scale = signal<number | null | undefined>(this.initial(this.data.current?.textScale));
  /** Short-lived links to the images, for thumbnails and the preview. */
  protected readonly thumbs = signal<Record<string, string>>({});

  /** Library images that can be slide backgrounds. */
  protected readonly images = toSignal(
    this.media.list(['SONG_BACKGROUND', 'GENERAL']).pipe(
      map((list) => list.filter((m) => m.kind === 'IMAGE')),
      catchError(() => of([])),
    ),
    { initialValue: [] },
  );
  protected readonly previewUrl = computed(() => {
    const bg = this.background();
    if (!bg) {
      return null;
    }
    return this.thumbs()[bg] ?? (bg === this.data.current?.backgroundMediaId ? (this.data.currentBackgroundUrl ?? null) : null);
  });

  constructor() {
    effect(() => {
      for (const m of this.images().slice(0, 200)) {
        untracked(() =>
          this.media.downloadUrl(m.id).subscribe((u) => this.thumbs.update((t) => ({ ...t, [m.id]: u.url }))),
        );
      }
    });
  }

  protected save(): void {
    const value = <T>(v: T | null | undefined): T | null => (v === undefined ? null : v);
    this.ref.close({
      backgroundMediaId: value(this.background()),
      textColor: value(this.color()),
      textScale: value(this.scale()),
    });
  }

  private initial<T>(v: T | null | undefined): T | null | undefined {
    if (this.data.inheritLabel) {
      return v ?? undefined; // unset = inherit
    }
    return v ?? null; // the song's own: unset = platform default
  }
}
