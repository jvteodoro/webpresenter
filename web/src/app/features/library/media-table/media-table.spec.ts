import { TestBed } from '@angular/core/testing';
import { MediaAsset } from '../../../core/media/media';
import { MediaTable } from './media-table';

const video: MediaAsset = {
  id: '1',
  title: 'Abertura',
  originalFilename: 'abertura.mp4',
  kind: 'VIDEO',
  contentType: 'video/mp4',
  sizeBytes: 1536,
  sha256: 'x',
  durationMs: 65_000,
  width: 1920,
  height: 1080,
  purpose: 'GENERAL',
  createdAt: '2026-11-21T12:00:00Z',
  createdBy: 'tecnico',
};

describe('MediaTable', () => {
  function render(canManage: boolean): HTMLElement {
    const fixture = TestBed.createComponent(MediaTable);
    fixture.componentRef.setInput('items', [video]);
    fixture.componentRef.setInput('canManage', canManage);
    fixture.detectChanges();
    return fixture.nativeElement as HTMLElement;
  }

  it('shows title, duration and size', () => {
    const text = render(false).textContent ?? '';

    expect(text).toContain('Abertura');
    expect(text).toContain('1:05');
    expect(text).toContain('1,5 KB');
  });

  it('offers management actions only to users who can manage the library', () => {
    expect(render(false).querySelector('[aria-label="Mais ações"]')).toBeNull();
    expect(render(true).querySelector('[aria-label="Mais ações"]')).not.toBeNull();
  });
});
