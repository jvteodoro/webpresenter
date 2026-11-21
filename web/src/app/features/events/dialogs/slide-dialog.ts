import { Component, inject, signal } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { MatButtonModule } from '@angular/material/button';
import { MAT_DIALOG_DATA, MatDialogModule, MatDialogRef } from '@angular/material/dialog';
import { MatFormFieldModule } from '@angular/material/form-field';
import { MatInputModule } from '@angular/material/input';
import { SlideInput } from '../../../core/events/event';

/** Title and text of a slide; the player renders it full screen. */
@Component({
  selector: 'app-slide-dialog',
  imports: [FormsModule, MatDialogModule, MatButtonModule, MatFormFieldModule, MatInputModule],
  template: `
    <h2 mat-dialog-title>{{ data ? 'Editar slide' : 'Novo slide de texto' }}</h2>
    <form (ngSubmit)="save()">
      <mat-dialog-content>
        <div class="form">
          <mat-form-field appearance="outline">
            <mat-label>Título</mat-label>
            <input matInput name="title" [ngModel]="title()" (ngModelChange)="title.set($event)" maxlength="200" required cdkFocusInitial />
          </mat-form-field>
          <mat-form-field appearance="outline">
            <mat-label>Texto</mat-label>
            <textarea matInput name="body" rows="6" [ngModel]="body()" (ngModelChange)="body.set($event)" maxlength="4000"></textarea>
            <mat-hint>Cada linha aparece em uma linha do telão</mat-hint>
          </mat-form-field>
        </div>
      </mat-dialog-content>
      <mat-dialog-actions align="end">
        <button mat-button type="button" mat-dialog-close>Cancelar</button>
        <button mat-flat-button type="submit" [disabled]="!title().trim()">Salvar</button>
      </mat-dialog-actions>
    </form>
  `,
  styles: `
    .form {
      display: flex;
      flex-direction: column;
      gap: 0.25rem;
      width: min(32rem, 75vw);
      padding-top: 0.5rem;
    }
  `,
})
export class SlideDialog {
  protected readonly data = inject<SlideInput | null>(MAT_DIALOG_DATA);
  private readonly ref = inject(MatDialogRef<SlideDialog, SlideInput>);
  protected readonly title = signal(this.data?.slideTitle ?? '');
  protected readonly body = signal(this.data?.slideBody ?? '');

  protected save(): void {
    if (this.title().trim()) {
      this.ref.close({ slideTitle: this.title().trim(), slideBody: this.body().trim() });
    }
  }
}
