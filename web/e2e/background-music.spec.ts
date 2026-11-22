import { expect, test } from '@playwright/test';
import { join } from 'node:path';
import { FakePlayer, api, approveDevice, deleteDevice, uploadMedia } from './fake-player';
import { FIXTURES_DIR, login } from './fixtures';

const DEFAULT_AUDIO = { master: 100, media: 100, background: 60, effects: 100, fadeInMs: 2000, fadeOutMs: 1500, duckPercent: 0 };

/** Next message of a type that satisfies the test (the other browser's run may send its own). */
async function nextMatching(player: FakePlayer, type: string, match: (m: any) => boolean): Promise<any> {
  const deadline = Date.now() + 15_000;
  for (;;) {
    const message = await player.next(type, Math.max(1, deadline - Date.now()));
    if (match(message)) {
      return message;
    }
  }
}

test.describe('Fundo musical e mixer', () => {
  test('padrão da plataforma, cena com playlist e mixer ao vivo chegam ao player', async ({ page }, testInfo) => {
    const tag = `E2E fundo ${testInfo.project.name} ${Date.now()}`;
    const level = testInfo.project.name === 'chromium' ? 41 : 43; // distinct per browser (runs in parallel)
    const piano = await uploadMedia(join(FIXTURES_DIR, 'song.m4a'), `${tag} piano`, 'audio/mp4', 'BACKGROUND_MUSIC');
    const playlist = (await api('POST', '/api/playlists', { name: `${tag} lista` })).id;
    await api('PUT', `/api/playlists/${playlist}/items`, { items: [{ type: 'MEDIA', mediaId: piano }] });
    const event = (await api('POST', '/api/events', { title: `${tag} culto` })).id;
    const player = await FakePlayer.connect(`e2e-bg-${testInfo.project.name}-${Date.now()}`);
    try {
      await player.next('PAIRING_PENDING');
      await approveDevice(player.deviceId, `${tag} telão`);
      await player.next('MIXER');

      // Settings: platform default background music, fade and the background level.
      await login(page, 'tecnico', '/eventos');
      await page.getByRole('link', { name: 'Configurações' }).click();
      await expect(page.getByRole('heading', { level: 1, name: 'Configurações' })).toBeVisible();
      await page.getByRole('button', { name: 'Escolher' }).click();
      const picker = page.getByRole('dialog');
      await picker.getByRole('radio', { name: 'Uma faixa da biblioteca' }).check();
      await picker.getByLabel('Buscar faixa').fill(tag);
      await picker.getByRole('button', { name: new RegExp(`${tag} piano`) }).click();
      await picker.getByRole('button', { name: 'Aplicar' }).click();
      await page.getByLabel('Entrada (fade in), em segundos').fill('3');
      await page.locator('input[aria-label="Volume Fundo musical"]').fill(String(level));
      await page.getByRole('button', { name: 'Salvar' }).click();
      await expect(page.getByLabel('Fundo musical padrão')).toContainText(`${tag} piano`);

      const mixer = await nextMatching(player, 'MIXER', (m) => m.background === level);
      expect(mixer.fadeInMs).toBe(3000);
      await nextMatching(player, 'PREPARE', (p) => p.rest?.key === `media:${piano}`);

      // Event: a scene with the playlist as its background, a slide inside it.
      await page.getByRole('link', { name: 'Eventos' }).click();
      await page.getByRole('link', { name: new RegExp(`${tag} culto`) }).click();
      await page.getByRole('button', { name: 'Adicionar cena' }).click();
      let dialog = page.getByRole('dialog');
      await dialog.getByLabel('Nome da cena').fill('Escola Sabatina');
      await dialog.getByLabel('Início (opcional)').fill('09:30');
      await dialog.getByLabel('Fim (opcional)').fill('10:10');
      await dialog.getByRole('button', { name: 'Salvar' }).click();
      const scene = page.getByRole('listitem', { name: 'Cena Escola Sabatina' });
      await expect(scene).toContainText('09:30–10:10');

      await page.getByRole('button', { name: 'Adicionar slide' }).click();
      dialog = page.getByRole('dialog');
      await dialog.getByLabel('Título').fill(`${tag} hino`);
      await dialog.getByRole('button', { name: /Salvar|Adicionar/ }).click();
      await expect(page.getByRole('listitem', { name: `${tag} hino` })).toBeVisible();

      await scene.getByRole('button', { name: 'Fundo musical' }).click();
      dialog = page.getByRole('dialog');
      await dialog.getByRole('radio', { name: 'Uma playlist' }).check();
      await dialog.getByRole('combobox', { name: 'Playlist' }).click();
      await page.getByRole('option', { name: new RegExp(`${tag} lista`) }).click();
      await dialog.getByRole('button', { name: 'Aplicar' }).click();
      await expect(scene).toContainText(`Playlist ${tag} lista`);

      await page.getByRole('button', { name: 'Preparar nos players' }).click();
      const plan = await player.nextPrepareFor(`${tag} culto`);
      expect(plan.items).toHaveLength(1); // the scene marker is not presented
      expect(plan.items[0].background.key).toMatch(new RegExp(`^playlist:${playlist}`));
      expect(plan.items[0].background.tracks[0].mediaId).toBe(piano);
      expect(plan.rest.key).toBe(`media:${piano}`); // the event has none: platform default

      // Live: the scene groups its items; the mixer reaches the players at once.
      await page.getByRole('radio', { name: 'Modo de operação' }).click(); // opens the live page
      await expect(page.locator('li.scene', { hasText: 'Escola Sabatina' })).toBeVisible();
      await page.getByRole('region', { name: 'Mixer' }).locator('input[aria-label="Volume Geral"]').fill(String(level + 30));
      await nextMatching(player, 'MIXER', (m) => m.master === level + 30);
    } finally {
      player.close();
      await deleteDevice(player.deviceId).catch(() => undefined);
      await api('DELETE', '/api/events/current', undefined, 'tecnico').catch(() => undefined);
      await api('DELETE', `/api/events/${event}`).catch(() => undefined);
      await api('PUT', '/api/settings/audio', DEFAULT_AUDIO, 'tecnico').catch(() => undefined);
      await api('DELETE', `/api/playlists/${playlist}`).catch(() => undefined);
      await api('DELETE', `/api/media/${piano}`, undefined, 'tecnico').catch(() => undefined);
    }
  });
});
