import { HttpClient } from '@angular/common/http';
import { Injectable, inject } from '@angular/core';
import { Observable } from 'rxjs';
import { environment } from '../../../environments/environment';
import { ItemSpec, TemplateDetail, TemplateSummary, WEEKDAYS, Weekday } from './liturgy';

@Injectable({ providedIn: 'root' })
export class LiturgyApi {
  private readonly http = inject(HttpClient);
  private readonly baseUrl = `${environment.apiUrl}/api/liturgy/templates`;

  list(): Observable<TemplateSummary[]> {
    return this.http.get<TemplateSummary[]>(this.baseUrl);
  }

  get(id: string): Observable<TemplateDetail> {
    return this.http.get<TemplateDetail>(`${this.baseUrl}/${id}`);
  }

  create(name: string, weekdays: Weekday[] = [], startTime: string | null = null): Observable<TemplateDetail> {
    return this.http.post<TemplateDetail>(this.baseUrl, { name, weekdays: isoDays(weekdays), startTime });
  }

  update(id: string, name: string, weekdays: Weekday[], startTime: string | null): Observable<TemplateDetail> {
    return this.http.put<TemplateDetail>(`${this.baseUrl}/${id}`, { name, weekdays: isoDays(weekdays), startTime });
  }

  setSlots(id: string, slots: ItemSpec[]): Observable<TemplateDetail> {
    return this.http.put<TemplateDetail>(`${this.baseUrl}/${id}/slots`, { slots });
  }

  delete(id: string): Observable<void> {
    return this.http.delete<void>(`${this.baseUrl}/${id}`);
  }

  /** Creates the template's event for a date ("YYYY-MM-DD"); 409 with {eventId} if it exists. */
  createEvent(id: string, date: string, startsAt: string | null): Observable<{ id: string; title: string; pendingSlots: number }> {
    return this.http.post<{ id: string; title: string; pendingSlots: number }>(`${this.baseUrl}/${id}/events`, { date, startsAt });
  }
}

function isoDays(days: Weekday[]): number[] {
  return days.map((d) => WEEKDAYS.indexOf(d) + 1);
}
