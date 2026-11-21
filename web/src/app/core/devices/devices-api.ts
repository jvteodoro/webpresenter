import { HttpClient } from '@angular/common/http';
import { Injectable, inject } from '@angular/core';
import { Observable } from 'rxjs';
import { environment } from '../../../environments/environment';
import { Device, DeviceSettings } from './device';

@Injectable({ providedIn: 'root' })
export class DevicesApi {
  private readonly http = inject(HttpClient);
  private readonly baseUrl = `${environment.apiUrl}/api/devices`;

  list(): Observable<Device[]> {
    return this.http.get<Device[]>(this.baseUrl);
  }

  approve(id: string, name: string): Observable<Device> {
    return this.http.post<Device>(`${this.baseUrl}/${id}/approve`, { name });
  }

  configure(id: string, settings: DeviceSettings): Observable<Device> {
    return this.http.put<Device>(`${this.baseUrl}/${id}/settings`, settings);
  }

  remove(id: string): Observable<void> {
    return this.http.delete<void>(`${this.baseUrl}/${id}`);
  }
}
