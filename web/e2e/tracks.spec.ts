import { expect, test } from '@playwright/test';
import { execFileSync } from 'node:child_process';
import { mkdtempSync, readFileSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import { api, apiToken } from './fake-player';
import { login } from './fixtures';

/** Uploads a distinct tone (the library refuses duplicate files) as song audio. */
async function tone(frequency: number): Promise<string> {
  const file = join(mkdtempSync(join(tmpdir(), 'tracks-')), `${frequency}.m4a`);
  execFileSync('ffmpeg', ['-v', 'error', '-y', '-f', 'lavfi', '-i', `sine=frequency=${frequency}:duration=12`, '-c:a', 'aac', file]);
  const form = new FormData();
  form.append('file', new Blob([readFileSync(file)], { type: 'audio/mp4' }), `${frequency}.m4a`);
  form.append('purpose', 'SONG_AUDIO');
  const media = await fetch('http://localhost:8090/api/media', {
    method: 'POST',
    headers: { Authorization: `Bearer ${await apiToken('tecnico')}` },
    body: form,
  }).then((r) => r.json());
  return media.id;
}

test.describe('Faixas separadas', () => {
  test('tocam juntas fora de evento, com solo, mudo e a letra acompanhando', async ({ page }) => {
    test.setTimeout(120_000); // five tones are generated and uploaded
    const base = 300 + Math.floor(Math.random() * 3000);
    const song = await api('POST', '/api/songs', {
      title: `Kit de ensaio ${base}`,
      lyrics: {
        parts: [{ id: 'v', kind: 'VERSE', label: 'Estrofe', slides: [{ lines: ['Primeira linha'] }, { lines: ['Segunda linha'] }] }],
        order: ['v'],
      },
    }, 'tecnico');
    const created = await api('POST', `/api/songs/${song.id}/recordings`, {
      kind: 'SUNG', mediaId: await tone(base),
      timing: { source: 'MANUAL', reviewed: true, cues: [{ slide: 0, startMs: 0 }, { slide: 1, startMs: 2000 }] },
    }, 'tecnico');
    const sung = created.recordings[0].id;
    const add = async (part: string, frequency: number, derivedFrom: string) =>
      (await api('POST', `/api/songs/${song.id}/recordings`, { kind: 'STEM', part, mediaId: await tone(frequency), derivedFrom }, 'tecnico'))
        .recordings.find((r: { part: string }) => r.part === part).id;
    const vocals = await add('VOCALS', base + 11, sung);
    await add('OTHER', base + 23, sung);
    await add('SOPRANO', base + 37, vocals);
    await add('TENOR', base + 41, vocals);

    await login(page, 'tecnico');
    await page.goto(`/musicas/${song.id}`);
    const tracks = page.getByRole('group', { name: 'Faixas separadas de Cantado' });
    await expect(tracks).toBeVisible();
    // Only the song's own recording is among the recordings to choose; tracks are in the mixer.
    await expect(page.getByLabel('Gravações').locator('mat-button-toggle')).toHaveCount(1);
    const channels = tracks.getByRole('list', { name: 'Faixas' }).getByRole('listitem');
    await expect(channels).toHaveCount(5);
    await expect(channels.nth(0)).toContainText('Original (cantado)');
    await expect(channels.nth(1)).toContainText('Voz');
    await expect(channels.nth(3)).toContainText('Soprano');

    // "Tudo": the separated tracks, without the original and without the full vocals (the voices are separated).
    await expect(channels.nth(0)).toHaveClass(/silent/);
    await expect(channels.nth(1)).toHaveClass(/silent/);
    await expect(channels.nth(2)).not.toHaveClass(/silent/);

    // Solo one voice: only it is heard.
    await tracks.getByRole('button', { name: 'Solo Tenor' }).click();
    for (const i of [0, 1, 2, 3]) {
      await expect(channels.nth(i)).toHaveClass(/silent/);
    }
    await expect(channels.nth(4)).not.toHaveClass(/silent/);
    await tracks.getByRole('button', { name: 'Solo Tenor' }).click();
    await tracks.getByRole('button', { name: 'Silenciar Soprano' }).click();
    await expect(channels.nth(3)).toHaveClass(/silent/);
    await tracks.getByRole('button', { name: 'Só voz' }).click();
    await expect(channels.nth(2)).toHaveClass(/silent/);
    await expect(channels.nth(3)).not.toHaveClass(/silent/);

    // Playing: time advances and the lyrics follow the original's timing.
    await tracks.getByRole('button', { name: 'Tocar faixas' }).click();
    await expect(tracks.locator('.time')).toContainText(/^0:0[2-9]/, { timeout: 10000 });
    await expect(page.locator('li.slide.active')).toContainText('Segunda linha');
    // The sticky player shows the separated tracks playing and pauses them from anywhere on the page.
    const player = page.getByLabel('Gravações');
    await expect(player.getByRole('status')).toContainText('Faixas separadas');
    await player.getByRole('button', { name: 'Pausar as faixas separadas' }).click();
    await expect(player.getByRole('status')).toHaveCount(0);
    await expect(tracks.getByRole('button', { name: 'Tocar faixas' })).toBeVisible();

    // The vocals track offers the choir voices' separation.
    await expect(tracks.getByRole('button', { name: 'Separar naipes' })).toBeVisible();
    await api('DELETE', `/api/songs/${song.id}`, undefined, 'tecnico');
  });
});
