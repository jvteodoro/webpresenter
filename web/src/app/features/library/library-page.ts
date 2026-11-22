import { Component } from '@angular/core';
import { MatIconModule } from '@angular/material/icon';
import { MatTabsModule } from '@angular/material/tabs';
import { RouterLink, RouterLinkActive, RouterOutlet } from '@angular/router';

interface Section {
  path: string;
  label: string;
  icon: string;
}

/** Library frame: one tab per section (albums, playlists, loose songs, background music, effects, media). */
@Component({
  selector: 'app-library-page',
  imports: [MatIconModule, MatTabsModule, RouterLink, RouterLinkActive, RouterOutlet],
  template: `
    <header class="page-header">
      <h1>Biblioteca</h1>
    </header>
    <nav mat-tab-nav-bar [tabPanel]="panel" class="tabs" aria-label="Seções da biblioteca">
      @for (s of sections; track s.path) {
        <a mat-tab-link [routerLink]="s.path" routerLinkActive #rla="routerLinkActive" [active]="rla.isActive">
          <mat-icon class="tab-icon">{{ s.icon }}</mat-icon>
          {{ s.label }}
        </a>
      }
    </nav>
    <mat-tab-nav-panel #panel class="panel">
      <router-outlet />
    </mat-tab-nav-panel>
  `,
  styles: `
    :host { display: flex; flex-direction: column; gap: 0.75rem; }
    .page-header h1 { margin: 0; font: var(--mat-sys-headline-small); }
    .tab-icon { margin-right: 0.4rem; }
    .panel { display: block; padding-top: 0.75rem; }
  `,
})
export class LibraryPage {
  protected readonly sections: Section[] = [
    { path: 'albuns', label: 'Álbuns', icon: 'album' },
    { path: 'playlists', label: 'Playlists', icon: 'playlist_play' },
    { path: 'avulsas', label: 'Músicas avulsas', icon: 'music_note' },
    { path: 'fundos', label: 'Fundos musicais', icon: 'graphic_eq' },
    { path: 'efeitos', label: 'Efeitos sonoros', icon: 'notifications_active' },
    { path: 'midia', label: 'Mídia', icon: 'perm_media' },
  ];
}
