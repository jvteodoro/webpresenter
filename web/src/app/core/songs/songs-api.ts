import { HttpClient, HttpParams } from '@angular/common/http';
import { Injectable, inject } from '@angular/core';
import { Observable } from 'rxjs';
import { environment } from '../../../environments/environment';
import { DownloadUrl } from '../media/media';
import { SongCollection, SongDetail, SongInput, SongPage, Timing } from './song';

@Injectable({ providedIn: 'root' })
export class SongsApi {
  private readonly http = inject(HttpClient);
  private readonly baseUrl = `${environment.apiUrl}/api`;

  search(q: string, collection: string | null, offset: number, limit = 50): Observable<SongPage> {
    let params = new HttpParams().set('q', q).set('offset', offset).set('limit', limit);
    if (collection) {
      params = params.set('collection', collection);
    }
    return this.http.get<SongPage>(`${this.baseUrl}/songs`, { params });
  }

  get(id: string): Observable<SongDetail> {
    return this.http.get<SongDetail>(`${this.baseUrl}/songs/${id}`);
  }

  collections(): Observable<SongCollection[]> {
    return this.http.get<SongCollection[]>(`${this.baseUrl}/song-collections`);
  }

  update(id: string, input: SongInput): Observable<SongDetail> {
    return this.http.put<SongDetail>(`${this.baseUrl}/songs/${id}`, input);
  }

  setTiming(id: string, recordingId: string, timing: Partial<Timing>): Observable<SongDetail> {
    return this.http.put<SongDetail>(`${this.baseUrl}/songs/${id}/recordings/${recordingId}/timing`, timing);
  }

  mediaUrl(mediaId: string): Observable<DownloadUrl> {
    return this.http.get<DownloadUrl>(`${this.baseUrl}/media/${mediaId}/download-url`);
  }
}
