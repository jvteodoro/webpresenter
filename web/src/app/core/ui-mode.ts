import { Injectable, computed, inject, signal } from '@angular/core';
import { CurrentUser } from './auth/current-user';

/** operation: during the event (live control first); admin: managing events, content and devices. */
export type UiMode = 'operation' | 'admin';

export interface NavEntry {
  path: string;
  label: string;
  icon: string;
}

const STORAGE_KEY = 'webpresenter.uiMode';

/** The interface mode: decides the navigation and the start page; remembered per browser. */
@Injectable({ providedIn: 'root' })
export class UiModeService {
  private readonly currentUser = inject(CurrentUser);
  private readonly chosen = signal<UiMode | null>(readStored());

  /** The chosen mode, or the default for the user's roles. */
  readonly mode = computed<UiMode>(() => {
    const chosen = this.chosen();
    if (chosen) {
      return chosen;
    }
    return this.currentUser.hasAnyRole('ADMIN', 'AV_TECHNICIAN') ? 'admin' : 'operation';
  });

  readonly nav = computed<NavEntry[]>(() => {
    if (this.mode() === 'operation') {
      return [
        { path: '/ao-vivo', label: 'Ao vivo', icon: 'live_tv' },
        { path: '/eventos', label: 'Eventos', icon: 'event_list' },
        { path: '/musicas', label: 'Músicas', icon: 'queue_music' },
      ];
    }
    const entries: NavEntry[] = [
      { path: '/eventos', label: 'Eventos', icon: 'event_list' },
      { path: '/calendario', label: 'Calendário', icon: 'calendar_month' },
      { path: '/liturgia', label: 'Liturgia', icon: 'event_repeat' },
      { path: '/musicas', label: 'Músicas', icon: 'queue_music' },
      { path: '/biblioteca', label: 'Biblioteca', icon: 'video_library' },
      { path: '/fila', label: 'Fila', icon: 'pending_actions' },
    ];
    if (this.currentUser.canManageDevices()) {
      entries.push({ path: '/dispositivos', label: 'Dispositivos', icon: 'connected_tv' });
      entries.push({ path: '/configuracoes', label: 'Configurações', icon: 'settings' });
    }
    return entries;
  });

  readonly startPage = computed(() => (this.mode() === 'operation' ? '/ao-vivo' : '/eventos'));

  set(mode: UiMode): void {
    this.chosen.set(mode);
    try {
      localStorage.setItem(STORAGE_KEY, mode);
    } catch {
      // storage unavailable: the choice lasts for this page only
    }
  }
}

function readStored(): UiMode | null {
  try {
    const value = localStorage.getItem(STORAGE_KEY);
    return value === 'operation' || value === 'admin' ? value : null;
  } catch {
    return null;
  }
}
