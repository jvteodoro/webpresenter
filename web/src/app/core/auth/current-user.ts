import { HttpClient } from '@angular/common/http';
import { Injectable, computed, inject, signal } from '@angular/core';
import Keycloak from 'keycloak-js';
import { firstValueFrom } from 'rxjs';
import { environment } from '../../../environments/environment';

/** Fixed global roles, as returned by the server. */
export type Role = 'ADMIN' | 'AV_TECHNICIAN' | 'WORSHIP_LEADER' | 'MUSICIAN';

export interface User {
  username: string;
  displayName: string | null;
  roles: Role[];
}

/** The signed-in user, loaded from the server so roles follow the server's mapping. */
@Injectable({ providedIn: 'root' })
export class CurrentUser {
  private readonly http = inject(HttpClient);
  private readonly keycloak = inject(Keycloak);

  private readonly state = signal<User | null>(null);

  readonly user = this.state.asReadonly();
  readonly displayName = computed(() => {
    const user = this.state();
    return user?.displayName || user?.username || '';
  });
  /** May add, rename and remove library media. */
  readonly canManageLibrary = computed(() => this.hasAnyRole('ADMIN', 'AV_TECHNICIAN'));

  async load(): Promise<void> {
    this.state.set(await firstValueFrom(this.http.get<User>(`${environment.apiUrl}/api/me`)));
  }

  hasAnyRole(...roles: Role[]): boolean {
    const user = this.state();
    return !!user && roles.some((role) => user.roles.includes(role));
  }

  logout(): Promise<void> {
    return this.keycloak.logout({ redirectUri: window.location.origin });
  }
}
