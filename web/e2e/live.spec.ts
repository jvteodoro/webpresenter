import { expect, test } from '@playwright/test';
import { join } from 'node:path';
import {
  FakePlayer,
  api,
  approveDevice,
  deleteDevice,
  deleteTestData,
  uploadMedia,
} from './fake-player';
import { FIXTURES_DIR, login } from './fixtures';

test.describe('Ao vivo', () => {
  test('líder conduz o culto; player executa; músico acompanha em tempo real', async ({
    browser,
  }, testInfo) => {
    const tag = `E2E live ${testInfo.project.name} ${Date.now()}`;
    const video = await uploadMedia(join(FIXTURES_DIR, 'clip.mp4'), `${tag} vídeo`, 'video/mp4');
    const image = await uploadMedia(join(FIXTURES_DIR, 'slide.png'), `${tag} imagem`, 'image/png');
    const event = await api('POST', '/api/events', { title: `${tag} culto` });
    await api('POST', `/api/events/${event.id}/items`, {
      type: 'SLIDE',
      slideTitle: 'Bem-vindos',
      slideBody: 'Culto de domingo',
    });
    await api('POST', `/api/events/${event.id}/items`, { type: 'MEDIA', mediaId: video });
    const detail = await api('POST', `/api/events/${event.id}/items`, {
      type: 'MEDIA',
      mediaId: image,
    });
    const [slideItem, videoItem, imageItem] = detail.items.map((i: { id: string }) => i.id);

    const player = await FakePlayer.connect(`e2e-live-${testInfo.project.name}-${Date.now()}`);
    const leaderContext = await browser.newContext();
    const musicianContext = await browser.newContext();
    try {
      await player.next('PAIRING_PENDING');
      await approveDevice(player.deviceId, `${tag} telão`);
      await api('POST', `/api/events/${event.id}/current`);
      const plan = await player.nextPrepareFor(`${tag} culto`);
      player.send('CACHE_STATUS', {
        eventId: plan.eventId,
        itemsReady: 2,
        itemsTotal: 2,
        itemsFailed: 0,
        bytesReady: 10,
        bytesTotal: 10,
      });

      const leader = await leaderContext.newPage();
      await leader.goto('/ao-vivo');
      await leader.locator('#username').fill('lider');
      await leader.locator('#password').fill('lider');
      await leader.locator('#kc-login').click();
      await expect(leader.getByRole('heading', { level: 1, name: `${tag} culto` })).toBeVisible();
      await expect(leader.locator('.connection')).toContainText('Conectado');

      const musician = await musicianContext.newPage();
      await musician.goto('/ao-vivo');
      await musician.locator('#username').fill('musico');
      await musician.locator('#password').fill('musico');
      await musician.locator('#kc-login').click();
      await expect(musician.getByRole('heading', { level: 1, name: `${tag} culto` })).toBeVisible();
      await expect(musician.getByRole('button', { name: 'Próximo' })).toHaveCount(0);

      const now = leader.getByRole('article', { name: 'No telão' });
      const next = leader.getByRole('article', { name: 'A seguir' });
      await expect(now).toContainText('Tela de descanso');
      await expect(next).toContainText('Bem-vindos');

      // "Próximo" shows the slide; the player receives it and the musician sees it without reloading.
      await leader.getByRole('button', { name: 'Próximo' }).click();
      expect((await player.next('PLAY')).itemId).toBe(slideItem);
      await expect(now).toContainText('Bem-vindos');
      await expect(next).toContainText(`${tag} vídeo`);
      await expect(musician.getByRole('article', { name: 'No telão' })).toContainText('Bem-vindos');

      // Keyboard (as a presentation clicker): the video; reported progress is shown.
      await leader.keyboard.press('PageDown');
      expect((await player.next('PLAY')).itemId).toBe(videoItem);
      player.send('PLAYBACK_STATE', {
        itemId: videoItem,
        status: 'PLAYING',
        positionMs: 1000,
        durationMs: 3000,
        detail: null,
      });
      await expect(now).toContainText('Tocando');
      await expect(now).toContainText('/ 0:03');
      await expect(leader.getByLabel('Controles')).toBeVisible();

      await leader.getByRole('button', { name: 'Pausar' }).click();
      await player.next('PAUSE');
      await expect(now).toContainText('Pausado');
      await leader.getByRole('button', { name: 'Continuar' }).click();
      await player.next('RESUME');

      // The player reports the end of the video: everyone sees the rest screen.
      player.send('PLAYBACK_STATE', {
        itemId: videoItem,
        status: 'ENDED',
        positionMs: 3000,
        durationMs: 3000,
        detail: null,
      });
      await expect(now).toContainText('Tela de descanso');
      await expect(musician.getByRole('article', { name: 'No telão' })).toContainText(
        'Tela de descanso',
      );
      await expect(next).toContainText(`${tag} imagem`);

      // Jump straight to an item from the list, then back to the rest screen with Esc.
      await leader.getByRole('button', { name: `${tag} imagem` }).click();
      expect((await player.next('PLAY')).itemId).toBe(imageItem);
      await expect(now).toContainText(`${tag} imagem`);
      await expect(next).toContainText('Fim do evento');
      await leader.keyboard.press('Escape');
      await player.next('SHOW_REST');
      await expect(now).toContainText('Tela de descanso');

      // The players panel shows the fake player.
      player.send('PLAYBACK_STATE', { itemId: null, status: 'REST', positionMs: 0, durationMs: 0, detail: null });
      await expect(leader.getByLabel(`${tag} telão`)).toContainText('Tela de descanso');
    } finally {
      await leaderContext.close();
      await musicianContext.close();
      player.close();
      await deleteDevice(player.deviceId);
      await api('DELETE', '/api/events/current').catch(() => null);
      await deleteTestData(tag);
    }
  });

  test('sem evento atual, a página orienta a preparar um', async ({ page }) => {
    await api('DELETE', '/api/events/current');
    await page.goto('/ao-vivo');
    await page.locator('#username').fill('lider');
    await page.locator('#password').fill('lider');
    await page.locator('#kc-login').click();
    await expect(
      page.getByRole('heading', { level: 1, name: 'Nenhum evento atual' }),
    ).toBeVisible();
    await expect(page.getByRole('link', { name: 'Ir para Eventos' })).toBeVisible();
  });
});
