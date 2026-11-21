import { HttpClient } from '@angular/common/http';
import { Injectable, inject } from '@angular/core';
import { Observable } from 'rxjs';
import { environment } from '../../../environments/environment';
import { EventDetail, EventInput, EventSummary, Readiness, SlideInput } from './event';

@Injectable({ providedIn: 'root' })
export class EventsApi {
  private readonly http = inject(HttpClient);
  private readonly baseUrl = `${environment.apiUrl}/api/events`;

  list(): Observable<EventSummary[]> {
    return this.http.get<EventSummary[]>(this.baseUrl);
  }

  get(id: string): Observable<EventDetail> {
    return this.http.get<EventDetail>(`${this.baseUrl}/${id}`);
  }

  create(input: EventInput): Observable<EventDetail> {
    return this.http.post<EventDetail>(this.baseUrl, input);
  }

  update(id: string, input: EventInput): Observable<EventDetail> {
    return this.http.put<EventDetail>(`${this.baseUrl}/${id}`, input);
  }

  delete(id: string): Observable<void> {
    return this.http.delete<void>(`${this.baseUrl}/${id}`);
  }

  addMedia(id: string, mediaId: string): Observable<EventDetail> {
    return this.http.post<EventDetail>(`${this.baseUrl}/${id}/items`, { type: 'MEDIA', mediaId });
  }

  addSlide(id: string, slide: SlideInput): Observable<EventDetail> {
    return this.http.post<EventDetail>(`${this.baseUrl}/${id}/items`, { type: 'SLIDE', ...slide });
  }

  addSong(id: string, songId: string, recordingId: string | null): Observable<EventDetail> {
    return this.http.post<EventDetail>(`${this.baseUrl}/${id}/items`, { type: 'SONG', songId, recordingId });
  }

  chooseRecording(id: string, itemId: string, recordingId: string | null): Observable<EventDetail> {
    return this.http.put<EventDetail>(`${this.baseUrl}/${id}/items/${itemId}/recording`, { recordingId });
  }

  editSlide(id: string, itemId: string, slide: SlideInput): Observable<EventDetail> {
    return this.http.put<EventDetail>(`${this.baseUrl}/${id}/items/${itemId}`, slide);
  }

  removeItem(id: string, itemId: string): Observable<EventDetail> {
    return this.http.delete<EventDetail>(`${this.baseUrl}/${id}/items/${itemId}`);
  }

  reorder(id: string, itemIds: string[]): Observable<EventDetail> {
    return this.http.put<EventDetail>(`${this.baseUrl}/${id}/items/order`, { itemIds });
  }

  makeCurrent(id: string): Observable<EventDetail> {
    return this.http.post<EventDetail>(`${this.baseUrl}/${id}/current`, {});
  }

  readiness(): Observable<Readiness[]> {
    return this.http.get<Readiness[]>(`${environment.apiUrl}/api/devices/readiness`);
  }
}
