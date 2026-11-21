import { Component, inject, signal } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { MatButtonModule } from '@angular/material/button';
import { MAT_DIALOG_DATA, MatDialogModule, MatDialogRef } from '@angular/material/dialog';
import { MatFormFieldModule } from '@angular/material/form-field';
import { MatInputModule } from '@angular/material/input';
import { EventInput } from '../../../core/events/event';

export interface EventDialogData {
  heading: string;
  value: EventInput | null;
}

/** Title and optional date/time of an event. */
@Component({
  selector: 'app-event-dialog',
  imports: [FormsModule, MatDialogModule, MatButtonModule, MatFormFieldModule, MatInputModule],
  template: `
    <h2 mat-dialog-title>{{ data.heading }}</h2>
    <form (ngSubmit)="save()">
      <mat-dialog-content>
        <div class="form">
          <mat-form-field appearance="outline">
            <mat-label>Título</mat-label>
            <input matInput name="title" [ngModel]="title()" (ngModelChange)="title.set($event)" maxlength="200" required cdkFocusInitial />
          </mat-form-field>
          <mat-form-field appearance="outline">
            <mat-label>Data e hora</mat-label>
            <input matInput type="datetime-local" name="startsAt" [ngModel]="startsAt()" (ngModelChange)="startsAt.set($event)" />
            <mat-hint>Opcional</mat-hint>
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
      width: min(28rem, 75vw);
      padding-top: 0.5rem;
    }
  `,
})
export class EventDialog {
  protected readonly data = inject<EventDialogData>(MAT_DIALOG_DATA);
  private readonly ref = inject(MatDialogRef<EventDialog, EventInput>);

  protected readonly title = signal(this.data.value?.title ?? '');
  protected readonly startsAt = signal(toLocalInput(this.data.value?.startsAt ?? null));

  protected save(): void {
    if (!this.title().trim()) {
      return;
    }
    const local = this.startsAt();
    this.ref.close({ title: this.title().trim(), startsAt: local ? new Date(local).toISOString() : null });
  }
}

/** ISO instant → value for <input type="datetime-local"> in the browser's time zone. */
function toLocalInput(iso: string | null): string {
  if (!iso) {
    return '';
  }
  const d = new Date(iso);
  const pad = (n: number) => String(n).padStart(2, '0');
  return `${d.getFullYear()}-${pad(d.getMonth() + 1)}-${pad(d.getDate())}T${pad(d.getHours())}:${pad(d.getMinutes())}`;
}
