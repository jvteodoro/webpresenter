import { HttpClient } from '@angular/common/http';
import { Injectable, inject } from '@angular/core';
import { Observable } from 'rxjs';
import { environment } from '../../../environments/environment';
import { LiveAction, LiveState } from './live';

@Injectable({ providedIn: 'root' })
export class LiveApi {
  private readonly http = inject(HttpClient);

  command(action: LiveAction, itemId?: string): Observable<LiveState> {
    return this.http.post<LiveState>(`${environment.apiUrl}/api/live/commands`, { action, itemId });
  }
}
