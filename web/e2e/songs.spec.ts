import { expect, test } from '@playwright/test';
import { join } from 'node:path';
import { api, apiToken } from './fake-player';
import { FIXTURES_DIR, login } from './fixtures';

/** Creates a song with a timed recording through the API (as the AV technician). */
async function createSong(title: string): Promise<{ id: string; mediaId: string }> {
  const { readFileSync } = await import('node:fs');
  const form = new FormData();
  form.append(
    'file',
    new Blob([readFileSync(join(FIXTURES_DIR, 'song.m4a'))], { type: 'audio/mp4' }),
    `${title}.m4a`,
  );
  form.append('purpose', 'SONG_AUDIO');
  const media = await fetch('http://localhost:8090/api/media', {
    method: 'POST',
    headers: { Authorization: `Bearer ${await apiToken('tecnico')}` },
    body: form,
  }).then((r) => r.json());
  const song = await api(
    'POST',
    '/api/songs',
    {
      title,
      status: 'READY',
      lyrics: {
        parts: [
          {
            id: 'v1',
            kind: 'VERSE',
            label: 'Estrofe 1',
            slides: [{ lines: ['Castelo forte é nosso Deus', 'Refúgio e fortaleza'] }],
          },
          {
            id: 'c',
            kind: 'CHORUS',
            label: 'Coro',
            slides: [{ lines: ['Com força nos protege'], cue: 'Todos:' }],
          },
        ],
        order: ['v1', 'c', 'c'],
      },
      collections: [{ slug: 'e2e-hinario', name: 'Hinário E2E', group: 'Testes', number: '7' }],
    },
    'tecnico',
  );
  await api(
    'POST',
    `/api/songs/${song.id}/recordings`,
    {
      kind: 'SUNG',
      mediaId: media.id,
      timing: {
        source: 'IMPORT',
        reviewed: true,
        cues: [
          { slide: 0, startMs: 0 },
          { slide: 1, startMs: 4000 },
          { slide: 2, startMs: 8000 },
        ],
      },
    },
    'tecnico',
  );
  return { id: song.id, mediaId: media.id };
}

test.describe('Músicas', () => {
  test('busca, revisão dos tempos com o áudio e edição da letra', async ({ page }, testInfo) => {
    const title = `E2E Castelo ${testInfo.project.name} ${Date.now()}`;
    const { id, mediaId } = await createSong(title);
    try {
      await login(page, 'tecnico');
      await page.getByRole('link', { name: 'Músicas' }).click();

      // Accent-insensitive search on the first line.
      await page.getByLabel('Buscar por título ou letra').fill('REFUGIO E FORTALEZA');
      const row = page.getByRole('link', { name: new RegExp(title) });
      await expect(row).toContainText('Hinário E2E · 7');
      await expect(row).toContainText('Cantado');
      await row.click();

      await expect(page.getByRole('heading', { level: 1, name: title })).toBeVisible();
      const lyrics = page.getByRole('region', { name: 'Letra' });
      await expect(lyrics).toContainText('3 slides');
      await expect(lyrics).toContainText('Todos:');

      // Moving the audio to 0:05 highlights the slide its timing says is on screen.
      const audio = page.locator('audio');
      await expect
        .poll(() => audio.evaluate((a: HTMLAudioElement) => a.readyState))
        .toBeGreaterThanOrEqual(1);
      await audio.evaluate((a: HTMLAudioElement) => {
        a.currentTime = 5;
      });
      await expect(page.locator('li.slide.active')).toContainText('Com força nos protege');
      await expect(page.locator('li.slide.active')).toHaveAttribute('id', 'slide-1');

      // Edit lyrics as text: a bridge and a new order; timing is flagged as out of date.
      await page.getByRole('button', { name: 'Editar letra' }).click();
      const dialog = page.getByRole('dialog');
      const editor = dialog.getByLabel('Letra');
      const text = await editor.inputValue();
      expect(text).toContain('[Coro]\n(Todos:) Com força nos protege');
      await editor.fill(
        text.replace(
          'Ordem: Estrofe 1, Coro, Coro',
          '[Ponte]\nPonte nova\n\nOrdem: Estrofe 1, Coro, Ponte, Coro',
        ),
      );
      await expect(dialog).toContainText('3 partes, 4 slides');
      await expect(dialog).toContainText('tempos das gravações precisarão ser revisados');
      await dialog.getByRole('button', { name: 'Salvar letra' }).click();
      await expect(lyrics).toContainText('4 slides');
      await expect(lyrics).toContainText('Ponte nova');
      await expect(page.getByLabel('Gravações')).toContainText(
        'A letra mudou depois destes tempos',
      );

      // Mistakes are explained before saving.
      await page.getByRole('button', { name: 'Editar letra' }).click();
      await page.getByRole('dialog').getByLabel('Letra').fill('[A]\nx\n\nOrdem: A, B');
      await expect(page.getByRole('dialog').getByRole('alert')).toContainText('"B"');
      await expect(
        page.getByRole('dialog').getByRole('button', { name: 'Salvar letra' }),
      ).toBeDisabled();
    } finally {
      await api('DELETE', `/api/songs/${id}`, undefined, 'tecnico');
      await api('DELETE', `/api/media/${mediaId}`, undefined, 'tecnico').catch(() => null);
    }
  });

  test('músico consulta músicas mas não edita', async ({ page }, testInfo) => {
    const { id, mediaId } = await createSong(`E2E consulta ${testInfo.project.name} ${Date.now()}`);
    try {
      await login(page, 'musico');
      await page.goto(`/musicas/${id}`);
      await expect(page.getByRole('region', { name: 'Letra' })).toContainText(
        'Castelo forte é nosso Deus',
      );
      await expect(page.getByRole('button', { name: 'Editar letra' })).toHaveCount(0);
    } finally {
      await api('DELETE', `/api/songs/${id}`, undefined, 'tecnico');
      await api('DELETE', `/api/media/${mediaId}`, undefined, 'tecnico').catch(() => null);
    }
  });
});
