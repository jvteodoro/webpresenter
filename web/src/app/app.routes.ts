import { Routes } from '@angular/router';

export const routes: Routes = [
  { path: '', pathMatch: 'full', redirectTo: 'biblioteca' },
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
  { path: '**', redirectTo: 'biblioteca' },
];
