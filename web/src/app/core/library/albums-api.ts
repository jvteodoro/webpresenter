import { HttpClient } from '@angular/common/http';
import { Injectable, inject } from '@angular/core';
import { Observable } from 'rxjs';
import { environment } from '../../../environments/environment';
import { Album, AlbumDetail, AlbumInput } from './album';

@Injectable({ providedIn: 'root' })
export class AlbumsApi {
  private readonly http = inject(HttpClient);
  private readonly baseUrl = `${environment.apiUrl}/api/albums`;

  list(): Observable<Album[]> {
    return this.http.get<Album[]>(this.baseUrl);
  }

  get(id: string): Observable<AlbumDetail> {
    return this.http.get<AlbumDetail>(`${this.baseUrl}/${id}`);
  }

  create(input: AlbumInput): Observable<Album> {
    return this.http.post<Album>(this.baseUrl, input);
  }

  update(id: string, input: AlbumInput): Observable<Album> {
    return this.http.put<Album>(`${this.baseUrl}/${id}`, input);
  }

  setCover(id: string, coverMediaId: string | null): Observable<Album> {
    return this.http.put<Album>(`${this.baseUrl}/${id}/cover`, { coverMediaId });
  }

  setTracks(id: string, songIds: string[]): Observable<AlbumDetail> {
    return this.http.put<AlbumDetail>(`${this.baseUrl}/${id}/tracks`, { songIds });
  }

  delete(id: string): Observable<void> {
    return this.http.delete<void>(`${this.baseUrl}/${id}`);
  }
}
