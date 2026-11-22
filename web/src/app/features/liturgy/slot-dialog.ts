import { Component, inject } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { MatButtonModule } from '@angular/material/button';
import { MatDialogModule, MatDialogRef } from '@angular/material/dialog';
import { MatFormFieldModule } from '@angular/material/form-field';
import { MatInputModule } from '@angular/material/input';
import { MatRadioModule } from '@angular/material/radio';
import { SLOT_KIND_LABELS, SlotKind } from '../../core/liturgy/liturgy';

export interface SlotDialogResult {
  label: string;
  kind: SlotKind;
}

/** A variable slot of a template ("Hino escola sabatina 1"): chosen again for each event. */
@Component({
  selector: 'app-slot-dialog',
  imports: [FormsModule, MatButtonModule, MatDialogModule, MatFormFieldModule, MatInputModule, MatRadioModule],
  template: `
    <h2 mat-dialog-title>Espaço a escolher</h2>
    <form (submit)="$event.preventDefault(); save()">
      <mat-dialog-content class="content">
        <p class="hint">Em cada evento criado com este modelo, alguém escolhe o que entra aqui.</p>
        <mat-form-field appearance="outline">
          <mat-label>Nome do espaço</mat-label>
          <input matInput name="label" [(ngModel)]="label" maxlength="200" required cdkFocusInitial placeholder="Ex.: Hino escola sabatina 1" />
        </mat-form-field>
        <mat-radio-group name="kind" class="kinds" [(ngModel)]="kind" aria-label="O que entra">
          @for (k of kinds; track k) {
            <mat-radio-button [value]="k">{{ labels[k] }}</mat-radio-button>
          }
        </mat-radio-group>
      </mat-dialog-content>
      <mat-dialog-actions align="end">
        <button mat-button type="button" mat-dialog-close>Cancelar</button>
        <button mat-flat-button type="submit" [disabled]="!label.trim()">Adicionar</button>
      </mat-dialog-actions>
    </form>
  `,
  styles: `
    .content { display: flex; flex-direction: column; width: min(28rem, 80vw); }
    .hint { margin: 0 0 1rem; font-size: 0.875rem; color: var(--mat-sys-on-surface-variant); }
    .kinds { display: flex; flex-direction: column; }
  `,
})
export class SlotDialog {
  private readonly ref = inject(MatDialogRef<SlotDialog, SlotDialogResult>);
  protected readonly kinds: SlotKind[] = ['SONG', 'MEDIA', 'SLIDE', 'ANY'];
  protected readonly labels = SLOT_KIND_LABELS;
  protected label = '';
  protected kind: SlotKind = 'SONG';

  protected save(): void {
    this.ref.close({ label: this.label.trim(), kind: this.kind });
  }
}
