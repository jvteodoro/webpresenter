import { HttpClient } from '@angular/common/http';
import { Injectable, inject } from '@angular/core';
import { Observable } from 'rxjs';
import { environment } from '../../../environments/environment';
import { ItemSpec } from '../liturgy/liturgy';
import { EventDetail, EventInput, EventSummary, Readiness, SlideInput, SceneInput, BackgroundSource, TimerSettings } from './event';

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

  /** Fills a variable slot with what was chosen for this event. */
  fillSlot(id: string, itemId: string, spec: ItemSpec): Observable<EventDetail> {
    return this.http.put<EventDetail>(`${this.baseUrl}/${id}/items/${itemId}/fill`, spec);
  }

  addClock(id: string, label: string | null): Observable<EventDetail> {
    return this.http.post<EventDetail>(`${this.baseUrl}/${id}/items`, { type: 'CLOCK', label });
  }

  addTimer(id: string, label: string | null, timer: TimerSettings): Observable<EventDetail> {
    return this.http.post<EventDetail>(`${this.baseUrl}/${id}/items`, { type: 'TIMER', label, timer });
  }

  /** Label of a clock or timer and, for timers, their settings. */
  editClock(id: string, itemId: string, label: string | null, timer: TimerSettings | null): Observable<EventDetail> {
    return this.http.put<EventDetail>(`${this.baseUrl}/${id}/items/${itemId}/clock`, { label, timer });
  }

  addScene(id: string, scene: SceneInput): Observable<EventDetail> {
    return this.http.post<EventDetail>(`${this.baseUrl}/${id}/items`, {
      type: 'SCENE',
      sceneTitle: scene.title,
      sceneStarts: scene.starts,
      sceneEnds: scene.ends,
    });
  }

  editScene(id: string, itemId: string, scene: SceneInput): Observable<EventDetail> {
    return this.http.put<EventDetail>(`${this.baseUrl}/${id}/items/${itemId}/scene`, scene);
  }

  /** Background music of an item or scene (null: inherit). */
  setItemBackground(id: string, itemId: string, source: BackgroundSource | null): Observable<EventDetail> {
    return this.http.put<EventDetail>(`${this.baseUrl}/${id}/items/${itemId}/background`, { source });
  }

  /** Background music of the event (null: the platform's). */
  setEventBackground(id: string, source: BackgroundSource | null): Observable<EventDetail> {
    return this.http.put<EventDetail>(`${this.baseUrl}/${id}/background`, { source });
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
