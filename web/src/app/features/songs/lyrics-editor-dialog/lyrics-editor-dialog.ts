import { Component, computed, inject, signal } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { MatButtonModule } from '@angular/material/button';
import { MAT_DIALOG_DATA, MatDialogModule, MatDialogRef } from '@angular/material/dialog';
import { MatFormFieldModule } from '@angular/material/form-field';
import { MatInputModule } from '@angular/material/input';
import { LyricsTextError, lyricsToText, textToLyrics } from '../../../core/songs/lyrics-text';
import { Lyrics } from '../../../core/songs/song';

export interface LyricsEditorData {
  title: string;
  lyrics: Lyrics;
  hasTiming: boolean;
}

/** Edits lyrics as plain text (parts in [brackets], blank line between slides, Ordem: line). */
@Component({
  selector: 'app-lyrics-editor-dialog',
  imports: [FormsModule, MatDialogModule, MatButtonModule, MatFormFieldModule, MatInputModule],
  template: `
    <h2 mat-dialog-title>Letra · {{ data.title }}</h2>
    <mat-dialog-content class="content">
      <p class="help">
        <strong>[Nome da parte]</strong> inicia uma parte (Estrofe 1, Coro, Ponte, Instrumental…). Uma
        <strong>linha em branco</strong> separa slides. <strong>(Todos:)</strong> no início de um slide vira indicação.
        A linha <strong>Ordem:</strong> diz a sequência cantada, com repetições.
      </p>
      <mat-form-field appearance="outline" class="editor" subscriptSizing="dynamic">
        <mat-label>Letra</mat-label>
        <textarea matInput [ngModel]="text()" (ngModelChange)="text.set($event)" rows="22" spellcheck="true"></textarea>
      </mat-form-field>
      @if (error(); as e) {
        <p class="error" role="alert">{{ e }}</p>
      } @else {
        <p class="summary">{{ summary() }}</p>
      }
      @if (data.hasTiming && changesStructure()) {
        <p class="warning">A quantidade de slides mudou: os tempos das gravações precisarão ser revisados.</p>
      }
    </mat-dialog-content>
    <mat-dialog-actions align="end">
      <button mat-button mat-dialog-close>Cancelar</button>
      <button mat-flat-button [disabled]="!!error()" (click)="save()">Salvar letra</button>
    </mat-dialog-actions>
  `,
  styles: `
    .content {
      width: min(46rem, 85vw);
    }
    .editor {
      width: 100%;
    }
    textarea {
      font-family: ui-monospace, monospace;
      font-size: 0.95rem;
      line-height: 1.45;
    }
    .help,
    .summary {
      color: var(--mat-sys-on-surface-variant);
      font-size: 0.875rem;
    }
    .error {
      color: var(--mat-sys-error);
    }
    .warning {
      color: var(--mat-sys-tertiary);
      font-size: 0.875rem;
    }
  `,
})
export class LyricsEditorDialog {
  protected readonly data = inject<LyricsEditorData>(MAT_DIALOG_DATA);
  private readonly ref = inject(MatDialogRef<LyricsEditorDialog, Lyrics>);

  protected readonly text = signal(lyricsToText(this.data.lyrics));
  private readonly parsed = computed<{ lyrics?: Lyrics; error?: string }>(() => {
    try {
      return { lyrics: textToLyrics(this.text()) };
    } catch (e) {
      return { error: e instanceof LyricsTextError ? e.message : 'Texto inválido.' };
    }
  });
  protected readonly error = computed(() => this.parsed().error ?? null);
  protected readonly summary = computed(() => {
    const l = this.parsed().lyrics;
    return l ? `${l.parts.length} partes, ${slideCount(l)} slides na ordem cantada.` : '';
  });
  protected readonly changesStructure = computed(() => {
    const l = this.parsed().lyrics;
    return !!l && slideCount(l) !== slideCount(this.data.lyrics);
  });

  protected save(): void {
    const lyrics = this.parsed().lyrics;
    if (lyrics) {
      this.ref.close(lyrics);
    }
  }
}

function slideCount(lyrics: Lyrics): number {
  const sizes = new Map(lyrics.parts.map((p) => [p.id, p.slides.length]));
  return lyrics.order.reduce((n, id) => n + (sizes.get(id) ?? 0), 0);
}
