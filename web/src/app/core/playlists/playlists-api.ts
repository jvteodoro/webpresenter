import { HttpClient } from '@angular/common/http';
import { Injectable, inject } from '@angular/core';
import { Observable } from 'rxjs';
import { environment } from '../../../environments/environment';
import { PlaylistDetail, PlaylistItemSpec, PlaylistSummary } from './playlist';

@Injectable({ providedIn: 'root' })
export class PlaylistsApi {
  private readonly http = inject(HttpClient);
  private readonly baseUrl = `${environment.apiUrl}/api/playlists`;

  list(): Observable<PlaylistSummary[]> {
    return this.http.get<PlaylistSummary[]>(this.baseUrl);
  }

  get(id: string): Observable<PlaylistDetail> {
    return this.http.get<PlaylistDetail>(`${this.baseUrl}/${id}`);
  }

  create(name: string, description: string | null = null, shuffle = false): Observable<PlaylistDetail> {
    return this.http.post<PlaylistDetail>(this.baseUrl, { name, description, shuffle });
  }

  update(id: string, name: string, description: string | null, shuffle: boolean): Observable<PlaylistDetail> {
    return this.http.put<PlaylistDetail>(`${this.baseUrl}/${id}`, { name, description, shuffle });
  }

  setItems(id: string, items: PlaylistItemSpec[]): Observable<PlaylistDetail> {
    return this.http.put<PlaylistDetail>(`${this.baseUrl}/${id}/items`, { items });
  }

  delete(id: string): Observable<void> {
    return this.http.delete<void>(`${this.baseUrl}/${id}`);
  }
}
