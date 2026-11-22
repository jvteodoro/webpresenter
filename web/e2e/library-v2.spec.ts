import { expect, test } from '@playwright/test';
import { join } from 'node:path';
import { api, uploadMedia } from './fake-player';
import { FIXTURES_DIR, login, mediaRow } from './fixtures';

const LYRICS = { parts: [{ id: 'v', kind: 'VERSE', label: 'Estrofe 1', slides: [{ lines: ['Linha'] }] }], order: ['v'] };

async function createSong(title: string): Promise<string> {
  return (await api('POST', '/api/songs', { title, lyrics: LYRICS }, 'tecnico')).id;
}

async function addRecording(songId: string, title: string): Promise<string> {
  const mediaId = await uploadMedia(join(FIXTURES_DIR, 'song.m4a'), title, 'audio/mp4', 'SONG_AUDIO');
  await api('POST', `/api/songs/${songId}/recordings`, { kind: 'SUNG', mediaId }, 'tecnico');
  return mediaId;
}

test.describe('Biblioteca v2', () => {
  test('cria álbum com capa, adiciona e ordena músicas; avulsas ficam à parte', async ({ page }, testInfo) => {
    const tag = `E2E Álbum ${testInfo.project.name} ${Date.now()}`;
    const first = await createSong(`${tag} primeira`);
    const second = await createSong(`${tag} segunda`);
    const loose = await createSong(`${tag} avulsa`);
    try {
      await login(page, 'tecnico');
      await page.getByRole('tab', { name: 'Álbuns' }).click();
      await page.getByRole('button', { name: 'Novo álbum' }).click();

      const dialog = page.getByRole('dialog');
      await dialog.locator('input[type=file]').setInputFiles(join(FIXTURES_DIR, 'slide.png'));
      await expect(dialog.getByRole('img', { name: 'Capa do álbum' })).toBeVisible();
      await dialog.getByLabel('Nome').fill(tag);
      await dialog.getByLabel('Grupo (opcional)').fill('Testes E2E');
      await dialog.getByRole('button', { name: 'Salvar' }).click();

      await expect(page.getByRole('heading', { level: 1, name: tag })).toBeVisible();
      await expect(page.getByRole('img', { name: 'Capa do álbum' })).toBeVisible();

      for (const title of [`${tag} primeira`, `${tag} segunda`]) {
        await page.getByRole('button', { name: 'Adicionar música' }).click();
        await page.getByRole('dialog').getByLabel('Buscar por título ou letra').fill(title);
        await page.getByRole('dialog').getByRole('button', { name: new RegExp(title) }).click();
        await expect(page.getByRole('dialog')).toHaveCount(0);
      }
      const tracks = page.getByRole('list', { name: 'Músicas do álbum' }).getByRole('listitem');
      await expect(tracks).toHaveCount(2);
      await expect(tracks.nth(0)).toContainText('primeira');

      // Move the second up: numbers follow the new order.
      await tracks.nth(1).getByRole('button', { name: 'Subir' }).click();
      await expect(tracks.nth(0)).toContainText('segunda');
      await expect(tracks.nth(0)).toContainText('1');

      // The album appears in the grid, in its group, with its cover.
      await page.getByRole('link', { name: 'Álbuns' }).click();
      const card = page.getByRole('link', { name: tag });
      await expect(card).toContainText('2 músicas');
      await expect(card.locator('img')).toBeVisible();
      await expect(page.getByRole('heading', { name: /Testes E2E/ })).toBeVisible();

      // Loose songs: only the one in no album.
      await page.getByRole('tab', { name: 'Músicas avulsas' }).click();
      await page.getByLabel('Buscar por título ou letra').fill(tag);
      await expect(page.getByRole('link', { name: new RegExp(`${tag} avulsa`) })).toBeVisible();
      await expect(page.getByRole('link', { name: new RegExp(`${tag} primeira`) })).toHaveCount(0);

      // Delete the album: the songs stay.
      await page.getByRole('tab', { name: 'Álbuns' }).click();
      await page.getByRole('link', { name: tag }).click();
      await page.getByRole('button', { name: 'Excluir álbum' }).click();
      await page.getByRole('dialog').getByRole('button', { name: 'Excluir' }).click();
      await expect(page.getByRole('link', { name: tag })).toHaveCount(0);
    } finally {
      for (const id of [first, second, loose]) {
        await api('DELETE', `/api/songs/${id}`, undefined, 'tecnico').catch(() => undefined);
      }
    }
  });

  test('líder monta playlist com fundo musical e música; fundos e efeitos têm seções próprias', async ({ page }, testInfo) => {
    const tag = `E2E Playlist ${testInfo.project.name} ${Date.now()}`;
    const song = await createSong(`${tag} canção`);
    const recordingMedia = await addRecording(song, `${tag} gravação`);
    const music = await uploadMedia(join(FIXTURES_DIR, 'song.m4a'), `${tag} piano`, 'audio/mp4', 'BACKGROUND_MUSIC');
    // As the AV technician, then as the worship leader in a second browser context.
    try {
      // Background music appears in its own section, not in general media.
      await login(page, 'tecnico');
      await page.getByRole('tab', { name: 'Fundos musicais' }).click();
      await expect(mediaRow(page, `${tag} piano`)).toBeVisible();
      await expect(page.getByText('Arraste músicas instrumentais')).toBeVisible();
      await page.getByRole('tab', { name: 'Mídia' }).click();
      await expect(page.getByText('Arraste vídeos, áudios ou imagens')).toBeVisible();
      await expect(mediaRow(page, `${tag} piano`)).toHaveCount(0);
      await page.getByRole('tab', { name: 'Efeitos sonoros' }).click();
      await expect(page.getByText('Arraste sons curtos')).toBeVisible();
    } catch (e) {
      await api('DELETE', `/api/songs/${song}`, undefined, 'tecnico').catch(() => undefined);
      throw e;
    }

    const leader = await page.context().browser()!.newContext({ baseURL: testInfo.project.use.baseURL, locale: 'pt-BR' });
    const lp = await leader.newPage();
    try {
      await login(lp, 'lider');
      await lp.getByRole('tab', { name: 'Playlists' }).click();
      await lp.getByRole('button', { name: 'Nova playlist' }).click();
      await lp.getByRole('dialog').getByLabel('Nome').fill(tag);
      await lp.getByRole('dialog').getByRole('button', { name: /Salvar|OK|Confirmar/ }).click();
      await expect(lp.getByRole('heading', { level: 1, name: tag })).toBeVisible();

      await lp.getByRole('button', { name: 'Adicionar áudio ou vídeo' }).click();
      const picker = lp.getByRole('dialog');
      await picker.getByLabel('Buscar na biblioteca').fill(`${tag} piano`);
      await picker.getByText(`${tag} piano`, { exact: true }).click();
      await picker.getByRole('button', { name: 'Adicionar' }).click();

      await lp.getByRole('button', { name: 'Adicionar música' }).click();
      await lp.getByRole('dialog').getByLabel('Buscar por título ou letra').fill(`${tag} canção`);
      await lp.getByRole('dialog').getByRole('button', { name: new RegExp(`${tag} canção`) }).click();
      await expect(lp.getByRole('dialog').getByText('Sem áudio')).toHaveCount(0);
      await lp.getByRole('dialog').getByRole('button', { name: 'Adicionar' }).click();

      const items = lp.getByRole('list', { name: 'Itens da playlist' }).getByRole('listitem');
      await expect(items).toHaveCount(2);
      await expect(items.nth(0)).toContainText('piano');
      await expect(items.nth(1)).toContainText('Música · Cantado');

      await lp.getByRole('switch', { name: 'Ordem aleatória no fundo musical' }).click();
      await lp.reload();
      await expect(lp.getByRole('switch', { name: 'Ordem aleatória no fundo musical' })).toBeChecked();
      await expect(items).toHaveCount(2);

      await lp.getByRole('link', { name: 'Playlists' }).click();
      await expect(lp.getByRole('link', { name: new RegExp(tag) })).toContainText('2 itens');
      await lp.getByRole('link', { name: new RegExp(tag) }).click();
      await lp.getByRole('button', { name: 'Excluir' }).click();
      await lp.getByRole('dialog').getByRole('button', { name: 'Excluir' }).click();
      await expect(lp.getByRole('link', { name: new RegExp(tag) })).toHaveCount(0);
    } finally {
      await leader.close();
      await api('DELETE', `/api/songs/${song}`, undefined, 'tecnico').catch(() => undefined);
      for (const id of [music, recordingMedia]) {
        await api('DELETE', `/api/media/${id}`, undefined, 'tecnico').catch(() => undefined);
      }
    }
  });
});
