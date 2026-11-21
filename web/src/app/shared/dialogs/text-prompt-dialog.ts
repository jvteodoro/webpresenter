import { Component, inject, signal } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { MatButtonModule } from '@angular/material/button';
import { MAT_DIALOG_DATA, MatDialogModule, MatDialogRef } from '@angular/material/dialog';
import { MatFormFieldModule } from '@angular/material/form-field';
import { MatInputModule } from '@angular/material/input';

export interface TextPromptDialogData {
  title: string;
  label: string;
  value: string;
  maxLength: number;
}

/** Asks for a single line of text. Closes with the trimmed text, or nothing when cancelled. */
@Component({
  selector: 'app-text-prompt-dialog',
  imports: [FormsModule, MatDialogModule, MatButtonModule, MatFormFieldModule, MatInputModule],
  template: `
    <h2 mat-dialog-title>{{ data.title }}</h2>
    <form (ngSubmit)="save()">
      <mat-dialog-content>
        <mat-form-field appearance="outline" class="field">
          <mat-label>{{ data.label }}</mat-label>
          <input matInput name="value" [ngModel]="value()" (ngModelChange)="value.set($event)"
                 [maxlength]="data.maxLength" required cdkFocusInitial />
        </mat-form-field>
      </mat-dialog-content>
      <mat-dialog-actions align="end">
        <button mat-button type="button" mat-dialog-close>Cancelar</button>
        <button mat-flat-button type="submit" [disabled]="!value().trim()">Salvar</button>
      </mat-dialog-actions>
    </form>
  `,
  styles: `
    .field {
      width: min(28rem, 70vw);
    }
  `,
})
export class TextPromptDialog {
  protected readonly data = inject<TextPromptDialogData>(MAT_DIALOG_DATA);
  private readonly ref = inject(MatDialogRef<TextPromptDialog, string>);
  protected readonly value = signal(this.data.value);

  protected save(): void {
    const text = this.value().trim();
    if (text) {
      this.ref.close(text);
    }
  }
}
