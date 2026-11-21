import { Routes } from '@angular/router';

export const routes: Routes = [
  { path: '', pathMatch: 'full', redirectTo: 'eventos' },
  {
    path: 'ao-vivo',
    title: 'Ao vivo · webpresenter',
    loadComponent: () => import('./features/live/live-page').then((m) => m.LivePage),
  },
  {
    path: 'eventos',
    title: 'Eventos · webpresenter',
    loadComponent: () => import('./features/events/events-page').then((m) => m.EventsPage),
  },
  {
    path: 'eventos/:id',
    title: 'Evento · webpresenter',
    loadComponent: () => import('./features/events/event-editor/event-editor-page').then((m) => m.EventEditorPage),
  },
  {
    path: 'musicas',
    title: 'Músicas · webpresenter',
    loadComponent: () => import('./features/songs/songs-page').then((m) => m.SongsPage),
  },
  {
    path: 'musicas/:id',
    title: 'Música · webpresenter',
    loadComponent: () => import('./features/songs/song-page/song-page').then((m) => m.SongPage),
  },
  {
    path: 'biblioteca',
    title: 'Biblioteca · webpresenter',
    loadComponent: () => import('./features/library/library-page').then((m) => m.LibraryPage),
  },
  {
    path: 'dispositivos',
    title: 'Dispositivos · webpresenter',
    loadComponent: () => import('./features/devices/devices-page').then((m) => m.DevicesPage),
  },
  { path: '**', redirectTo: 'eventos' },
];
