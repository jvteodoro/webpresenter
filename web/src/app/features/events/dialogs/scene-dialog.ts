import { Component, inject } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { MatButtonModule } from '@angular/material/button';
import { MAT_DIALOG_DATA, MatDialogModule, MatDialogRef } from '@angular/material/dialog';
import { MatFormFieldModule } from '@angular/material/form-field';
import { MatInputModule } from '@angular/material/input';
import { SceneInput } from '../../../core/events/event';

/** Name and planned times of a scene (a group of items, e.g. "Escola Sabatina", 9:30–10:10). */
@Component({
  selector: 'app-scene-dialog',
  imports: [FormsModule, MatButtonModule, MatDialogModule, MatFormFieldModule, MatInputModule],
  template: `
    <h2 mat-dialog-title>{{ data ? 'Editar cena' : 'Nova cena' }}</h2>
    <form (submit)="$event.preventDefault(); save()">
      <mat-dialog-content class="content">
        <p class="hint">Uma cena agrupa os itens que vêm depois dela, até a próxima cena. O horário é usado pelo cronômetro.</p>
        <mat-form-field appearance="outline">
          <mat-label>Nome da cena</mat-label>
          <input matInput name="title" [(ngModel)]="title" maxlength="200" required cdkFocusInitial placeholder="Ex.: Escola Sabatina" />
        </mat-form-field>
        <div class="times">
          <mat-form-field appearance="outline">
            <mat-label>Início (opcional)</mat-label>
            <input matInput name="starts" type="time" [(ngModel)]="starts" />
          </mat-form-field>
          <mat-form-field appearance="outline">
            <mat-label>Fim (opcional)</mat-label>
            <input matInput name="ends" type="time" [(ngModel)]="ends" />
          </mat-form-field>
        </div>
        @if (invalidTimes()) { <p class="error" role="alert">O fim precisa ser depois do início.</p> }
      </mat-dialog-content>
      <mat-dialog-actions align="end">
        <button mat-button type="button" mat-dialog-close>Cancelar</button>
        <button mat-flat-button type="submit" [disabled]="!title.trim() || invalidTimes()">Salvar</button>
      </mat-dialog-actions>
    </form>
  `,
  styles: `
    .content { display: flex; flex-direction: column; width: min(28rem, 80vw); }
    .times { display: flex; gap: 0.75rem; }
    .times mat-form-field { flex: 1; }
    .hint { margin: 0 0 1rem; font-size: 0.875rem; color: var(--mat-sys-on-surface-variant); }
    .error { margin: 0; color: var(--mat-sys-error); }
  `,
})
export class SceneDialog {
  protected readonly data = inject<SceneInput | null>(MAT_DIALOG_DATA, { optional: true });
  private readonly ref = inject(MatDialogRef<SceneDialog, SceneInput>);

  protected title = this.data?.title ?? '';
  protected starts = this.data?.starts?.slice(0, 5) ?? '';
  protected ends = this.data?.ends?.slice(0, 5) ?? '';

  protected invalidTimes(): boolean {
    return !!this.starts && !!this.ends && this.ends <= this.starts;
  }

  protected save(): void {
    this.ref.close({ title: this.title.trim(), starts: this.starts || null, ends: this.ends || null });
  }
}
