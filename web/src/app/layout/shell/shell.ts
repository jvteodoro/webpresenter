import { Component, OnInit, inject } from '@angular/core';
import { MatButtonModule } from '@angular/material/button';
import { MatBadgeModule } from '@angular/material/badge';
import { MatButtonToggleModule } from '@angular/material/button-toggle';
import { MatIconModule } from '@angular/material/icon';
import { MatMenuModule } from '@angular/material/menu';
import { MatToolbarModule } from '@angular/material/toolbar';
import { Router, RouterLink, RouterLinkActive, RouterOutlet } from '@angular/router';
import { CurrentUser } from '../../core/auth/current-user';
import { UiMode, UiModeService } from '../../core/ui-mode';
import { JobsStream } from '../../core/jobs/jobs-stream';

/** Application frame: top bar with navigation and the user menu. */
@Component({
  selector: 'app-shell',
  imports: [RouterOutlet, RouterLink, RouterLinkActive, MatToolbarModule, MatBadgeModule, MatButtonModule, MatButtonToggleModule, MatIconModule, MatMenuModule],
  templateUrl: './shell.html',
  styleUrl: './shell.scss',
})
export class Shell implements OnInit {
  protected readonly currentUser = inject(CurrentUser);
  protected readonly uiMode = inject(UiModeService);
  /** Followed while signed in, for the active-jobs badge on "Fila". */
  protected readonly jobs = inject(JobsStream);
  private readonly router = inject(Router);

  /** Changing mode opens that mode's start page. */
  protected switchMode(mode: UiMode): void {
    this.uiMode.set(mode);
    void this.router.navigateByUrl(this.uiMode.startPage());
  }

  ngOnInit(): void {
    void this.currentUser.load();
    this.jobs.attach();
  }
}
