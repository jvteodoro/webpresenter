import { HttpErrorResponse } from '@angular/common/http';
import { Component, computed, effect, inject, input, signal, untracked } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { MatButtonModule } from '@angular/material/button';
import { MatButtonToggleModule } from '@angular/material/button-toggle';
import { MatDialog } from '@angular/material/dialog';
import { MatFormFieldModule } from '@angular/material/form-field';
import { MatIconModule } from '@angular/material/icon';
import { MatInputModule } from '@angular/material/input';
import { MatMenuModule } from '@angular/material/menu';
import { MatProgressBarModule } from '@angular/material/progress-bar';
import { MatSnackBar } from '@angular/material/snack-bar';
import { MatTooltipModule } from '@angular/material/tooltip';
import { Router, RouterLink } from '@angular/router';
import { Observable, Subject, concatMap, filter, switchMap } from 'rxjs';
import { CurrentUser } from '../../core/auth/current-user';
import { ItemSpec, SlotView, TemplateDetail, WEEKDAYS, WEEKDAY_LABELS, Weekday } from '../../core/liturgy/liturgy';
import { LiturgyApi } from '../../core/liturgy/liturgy-api';
import { ConfirmDialog, ConfirmDialogData } from '../../shared/dialogs/confirm-dialog';
import { ItemSpecPicker } from './item-spec-picker';

const ICONS: Record<string, string> = {
  SONG: 'queue_music', MEDIA: 'perm_media', SLIDE: 'text_fields', SCENE: 'segment', CLOCK: 'schedule', TIMER: 'timer', SLOT: 'edit_calendar',
};

/** A liturgy template: weekdays, start time and its slots (fixed items or chosen each event). */
@Component({
  selector: 'app-template-page',
  imports: [FormsModule, MatButtonModule, MatButtonToggleModule, MatFormFieldModule, MatIconModule, MatInputModule, MatMenuModule,
    MatProgressBarModule, MatTooltipModule, RouterLink],
  template: `
    <a mat-button routerLink="/liturgia" class="back"><mat-icon>arrow_back</mat-icon> Liturgia</a>
    @if (template(); as t) {
      <section class="card details">
        <div class="row">
          <mat-form-field appearance="outline" class="name">
            <mat-label>Nome do modelo</mat-label>
            <input matInput [ngModel]="t.name" (change)="rename($any($event.target).value)" [disabled]="!canEdit()" maxlength="200" />
          </mat-form-field>
          <mat-form-field appearance="outline" class="time">
            <mat-label>Início</mat-label>
            <input matInput type="time" [ngModel]="t.startTime?.slice(0, 5)" (change)="setTime($any($event.target).value)" [disabled]="!canEdit()" />
          </mat-form-field>
        </div>
        <span class="label">Dias da semana</span>
        <mat-button-toggle-group multiple [value]="t.weekdays" (change)="setDays($event.value)" [disabled]="!canEdit()"
                                 aria-label="Dias da semana" class="days">
          @for (d of weekdays; track d) {
            <mat-button-toggle [value]="d">{{ dayLabels[d] }}</mat-button-toggle>
          }
        </mat-button-toggle-group>
      </section>

      <section class="card">
        <div class="slots-header">
          <h2>Ordem</h2>
          @if (canEdit()) {
            <div class="add">
              <button mat-flat-button (click)="add(picker.slot())"><mat-icon>edit_calendar</mat-icon> Espaço a escolher</button>
              <button mat-stroked-button [matMenuTriggerFor]="fixedMenu"><mat-icon>add</mat-icon> Item fixo</button>
              <mat-menu #fixedMenu="matMenu">
                <button mat-menu-item (click)="add(picker.song())"><mat-icon>queue_music</mat-icon>Música</button>
                <button mat-menu-item (click)="addMany(picker.media())"><mat-icon>perm_media</mat-icon>Mídia</button>
                <button mat-menu-item (click)="add(picker.slide())"><mat-icon>text_fields</mat-icon>Slide</button>
                <button mat-menu-item (click)="add(picker.scene())"><mat-icon>segment</mat-icon>Cena</button>
                <button mat-menu-item (click)="addSpec({ type: 'CLOCK' })"><mat-icon>schedule</mat-icon>Relógio</button>
                <button mat-menu-item (click)="add(picker.timer())"><mat-icon>timer</mat-icon>Cronômetro</button>
              </mat-menu>
            </div>
          }
        </div>
        @if (saving()) { <mat-progress-bar mode="indeterminate" /> }
        <ol class="slots" aria-label="Itens do modelo">
          @for (s of t.slots; track $index; let i = $index, last = $last) {
            <li class="slot" [class.variable]="s.spec.type === 'SLOT'" [class.scene]="s.spec.type === 'SCENE'" [attr.aria-label]="s.title">
              <span class="position">{{ positions()[i] || '' }}</span>
              <mat-icon class="kind">{{ icon(s) }}</mat-icon>
              <span class="main">
                <span class="title">{{ s.title }}</span>
                <span class="detail">{{ s.detail }}@if (sceneTimes(s); as times) { · {{ times }} }</span>
              </span>
              @if (canEdit()) {
                <span class="actions">
                  <button mat-icon-button [disabled]="i === 0" (click)="move(i, -1)" matTooltip="Subir" aria-label="Subir"><mat-icon>arrow_upward</mat-icon></button>
                  <button mat-icon-button [disabled]="last" (click)="move(i, 1)" matTooltip="Descer" aria-label="Descer"><mat-icon>arrow_downward</mat-icon></button>
                  <button mat-icon-button (click)="remove(i)" matTooltip="Remover" [attr.aria-label]="'Remover ' + s.title"><mat-icon>close</mat-icon></button>
                </span>
              }
            </li>
          } @empty {
            <li class="empty">Nenhum item. Adicione os itens fixos (cenas, avisos, vídeos) e os espaços que mudam a cada semana (hinos).</li>
          }
        </ol>
      </section>

      @if (canEdit()) {
        <button mat-button class="danger" (click)="deleteTemplate()"><mat-icon>delete</mat-icon> Excluir modelo</button>
      }
    } @else if (loadError()) {
      <p class="empty">Modelo não encontrado.</p>
    } @else {
      <mat-progress-bar mode="indeterminate" />
    }
  `,
  styles: `
    :host { display: flex; flex-direction: column; gap: 1rem; }
    .back, .danger { align-self: flex-start; }
    .danger { color: var(--mat-sys-error); }
    .card { padding: 1rem; border-radius: var(--mat-sys-corner-large); background: var(--mat-sys-surface-container-low);
      border: 1px solid var(--mat-sys-outline-variant); }
    .row { display: flex; gap: 1rem; flex-wrap: wrap; }
    .name { flex: 1; min-width: 14rem; }
    .time { width: 9rem; }
    .label { display: block; margin-bottom: 0.4rem; color: var(--mat-sys-on-surface-variant); }
    .days { flex-wrap: wrap; }
    .slots-header { display: flex; justify-content: space-between; align-items: center; gap: 0.75rem; flex-wrap: wrap; margin-bottom: 0.75rem; }
    h2 { margin: 0; font: var(--mat-sys-title-medium); }
    .add { display: flex; gap: 0.5rem; flex-wrap: wrap; }
    .slots { list-style: none; margin: 0; padding: 0; display: flex; flex-direction: column; gap: 0.35rem; }
    .slot { display: flex; align-items: center; gap: 0.6rem; padding: 0.45rem 0.6rem; border-radius: var(--mat-sys-corner-medium);
      background: var(--mat-sys-surface); border: 1px solid var(--mat-sys-outline-variant); }
    .slot.variable { border-style: dashed; border-color: var(--mat-sys-primary); background: var(--mat-sys-primary-container);
      color: var(--mat-sys-on-primary-container); }
    .slot.scene { margin-top: 0.5rem; background: var(--mat-sys-secondary-container); color: var(--mat-sys-on-secondary-container); }
    .position { width: 1.5rem; text-align: right; color: var(--mat-sys-on-surface-variant); font-variant-numeric: tabular-nums; }
    .kind { color: var(--mat-sys-primary); }
    .variable .kind, .scene .kind, .variable .detail, .scene .detail, .variable .position { color: inherit; }
    .main { flex: 1; min-width: 0; display: flex; flex-direction: column; }
    .title { font-weight: 500; }
    .detail { font-size: 0.8rem; color: var(--mat-sys-on-surface-variant); }
    .actions { display: flex; }
    .empty { text-align: center; color: var(--mat-sys-on-surface-variant); padding: 1.5rem; }
  `,
})
export class TemplatePage {
  readonly id = input.required<string>();

  private readonly api = inject(LiturgyApi);
  private readonly dialog = inject(MatDialog);
  private readonly router = inject(Router);
  private readonly snackBar = inject(MatSnackBar);
  protected readonly picker = inject(ItemSpecPicker);
  protected readonly currentUser = inject(CurrentUser);
  protected readonly weekdays = WEEKDAYS;
  protected readonly dayLabels = WEEKDAY_LABELS;

  protected readonly template = signal<TemplateDetail | null>(null);
  protected readonly loadError = signal(false);
  protected readonly saving = signal(false);
  protected readonly canEdit = computed(() => this.currentUser.canEditEvents());
  /** Numbers of presented items (scenes are not numbered). */
  protected readonly positions = computed(() => {
    let n = 0;
    return (this.template()?.slots ?? []).map((s) => (s.spec.type === 'SCENE' ? 0 : ++n));
  });

  /** Saves run one at a time, in order: each sends the state as it was when the change was made. */
  private readonly saves = new Subject<() => Observable<TemplateDetail>>();

  constructor() {
    this.saves.pipe(concatMap((send) => send())).subscribe({
      next: (t) => {
        this.template.set(t);
        this.saving.set(false);
      },
      error: (e: HttpErrorResponse) => {
        this.saving.set(false);
        this.fail(e);
        this.api.get(this.id()).subscribe((t) => this.template.set(t)); // back to what was saved
      },
    });
    effect(() => {
      const id = this.id();
      untracked(() => this.api.get(id).subscribe({ next: (t) => this.template.set(t), error: () => this.loadError.set(true) }));
    });
  }

  protected icon(s: SlotView): string {
    return ICONS[s.spec.type] ?? 'help';
  }

  protected sceneTimes(s: SlotView): string {
    const a = s.spec.sceneStarts?.slice(0, 5);
    const b = s.spec.sceneEnds?.slice(0, 5);
    return s.spec.type === 'SCENE' && (a || b) ? [a, b].filter(Boolean).join('–') : '';
  }

  protected add(choice: Observable<ItemSpec>): void {
    choice.subscribe((spec) => this.addSpec(spec));
  }

  protected addMany(choice: Observable<ItemSpec[]>): void {
    choice.subscribe((specs) => this.saveSlots([...this.specs(), ...specs]));
  }

  protected addSpec(spec: ItemSpec): void {
    this.saveSlots([...this.specs(), spec]);
  }

  protected move(index: number, delta: number): void {
    const specs = this.specs();
    const [spec] = specs.splice(index, 1);
    specs.splice(index + delta, 0, spec);
    this.saveSlots(specs);
  }

  protected remove(index: number): void {
    const specs = this.specs();
    specs.splice(index, 1);
    this.saveSlots(specs);
  }

  protected rename(name: string): void {
    const t = this.template()!;
    if (name.trim() && name.trim() !== t.name) {
      this.saveDetails(name.trim(), t.weekdays, t.startTime);
    }
  }

  protected setTime(time: string): void {
    const t = this.template()!;
    this.saveDetails(t.name, t.weekdays, time || null);
  }

  protected setDays(days: Weekday[]): void {
    const t = this.template()!;
    this.saveDetails(t.name, days, t.startTime);
  }

  protected deleteTemplate(): void {
    const t = this.template()!;
    this.dialog
      .open<ConfirmDialog, ConfirmDialogData, boolean>(ConfirmDialog, {
        data: { title: 'Excluir modelo', message: `O modelo "${t.name}" será excluído. Os eventos já criados com ele continuam.`,
          confirmLabel: 'Excluir', destructive: true },
      })
      .afterClosed()
      .pipe(filter(Boolean), switchMap(() => this.api.delete(t.id)))
      .subscribe({ next: () => void this.router.navigate(['/liturgia']), error: (e) => this.fail(e) });
  }

  private specs(): ItemSpec[] {
    return (this.template()?.slots ?? []).map((s) => s.spec);
  }

  private saveSlots(specs: ItemSpec[]): void {
    const id = this.id();
    this.saving.set(true);
    this.saves.next(() => this.api.setSlots(id, specs));
  }

  /** Applied locally at once (a following change builds on it), then saved in turn. */
  private saveDetails(name: string, days: Weekday[], time: string | null): void {
    const id = this.id();
    this.template.update((t) => t && { ...t, name, weekdays: days, startTime: time });
    this.saving.set(true);
    this.saves.next(() => this.api.update(id, name, days, time));
  }

  private fail(e: HttpErrorResponse): void {
    this.snackBar.open(e.error?.detail ? `Não foi possível salvar: ${e.error.detail}` : `Não foi possível salvar (${e.status}).`, 'OK', { duration: 6000 });
  }
}
