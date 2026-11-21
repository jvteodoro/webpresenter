import { expect, test } from '@playwright/test';
import { FakePlayer, api, approveDevice, deleteDevice } from './fake-player';
import { login } from './fixtures';

test.describe('Músicas ao vivo', () => {
  test('líder adiciona música sem áudio e conduz os slides; músico acompanha a letra', async ({
    browser,
  }, testInfo) => {
    const tag = `E2E música ${testInfo.project.name} ${Date.now()}`;
    const song = await api(
      'POST',
      '/api/songs',
      {
        title: `${tag} Castelo`,
        status: 'READY',
        lyrics: {
          parts: [
            {
              id: 'v1',
              kind: 'VERSE',
              label: 'Estrofe 1',
              slides: [
                { lines: ['Castelo forte é nosso Deus'] },
                { lines: ['Refúgio e fortaleza'] },
              ],
            },
            {
              id: 'c',
              kind: 'CHORUS',
              label: 'Coro',
              slides: [{ lines: ['Com força nos protege'], cue: 'Todos:' }],
            },
          ],
          order: ['v1', 'c'],
        },
      },
      'tecnico',
    );
    const event = await api('POST', '/api/events', { title: `${tag} culto` });
    const player = await FakePlayer.connect(`e2e-song-live-${testInfo.project.name}-${Date.now()}`);
    const leaderContext = await browser.newContext();
    const musicianContext = await browser.newContext();
    try {
      await player.next('PAIRING_PENDING');
      await approveDevice(player.deviceId, `${tag} telão`);

      // Add the song from the event editor, lyrics only.
      const leader = await leaderContext.newPage();
      await login(leader, 'lider', '/eventos');
      await leader.goto(`/eventos/${event.id}`);
      await leader.getByRole('button', { name: 'Adicionar música' }).click();
      const picker = leader.getByRole('dialog');
      await picker.getByLabel('Buscar por título ou letra').fill(tag);
      await picker.getByRole('button', { name: new RegExp(`${tag} Castelo`) }).click();
      await picker.getByRole('radio', { name: /Sem áudio/ }).check();
      await picker.getByRole('button', { name: 'Adicionar' }).click();
      await expect(leader.locator('li.item')).toContainText('Sem áudio · avanço manual · 3 slides');

      await leader.getByRole('button', { name: 'Preparar nos players' }).click();
      const plan = await player.nextPrepareFor(`${tag} culto`);
      expect(plan.items[0].type).toBe('SONG');
      expect(plan.items[0].song.slides.map((s: { lines: string[] }) => s.lines[0])).toEqual([
        'Castelo forte é nosso Deus',
        'Refúgio e fortaleza',
        'Com força nos protege',
      ]);

      // Drive it live.
      await leader.getByRole('link', { name: 'Ao vivo' }).click();
      const musician = await musicianContext.newPage();
      await login(musician, 'musico', '/eventos');
      await musician.goto('/ao-vivo');

      await leader.getByRole('button', { name: 'Próximo' }).click();
      const play = await player.next('PLAY');
      expect(play.slide).toBe(0);
      await expect(leader.getByLabel('Letra no telão')).toContainText('Castelo forte é nosso Deus');
      await expect(leader.getByRole('article', { name: 'A seguir' })).toContainText(
        'Refúgio e fortaleza',
      );
      await expect(musician.getByLabel('Letra no telão')).toContainText(
        'Castelo forte é nosso Deus',
      );

      await leader.keyboard.press('ArrowRight');
      expect(await player.next('SHOW_SLIDE')).toEqual({ itemId: plan.items[0].itemId, slide: 1 });
      await expect(musician.getByLabel('Letra no telão')).toContainText('Refúgio e fortaleza');

      // Click a slide in the song panel.
      const panel = leader.getByRole('region', { name: 'Slides da música' });
      await panel.getByRole('button', { name: /Com força nos protege/ }).click();
      expect((await player.next('SHOW_SLIDE')).slide).toBe(2);
      await expect(leader.getByLabel('Letra no telão')).toContainText('Todos:');
      await expect(leader.getByRole('article', { name: 'A seguir' })).toContainText(
        'Fim do evento',
      );

      // The musician sees the slides but cannot drive them.
      await expect(
        musician.getByRole('region', { name: 'Slides da música' }).getByRole('button').first(),
      ).toBeDisabled();

      // After the last slide, next leaves the song.
      await leader.getByRole('button', { name: 'Próximo' }).click();
      await player.next('SHOW_REST');
    } finally {
      await leaderContext.close();
      await musicianContext.close();
      player.close();
      await deleteDevice(player.deviceId);
      await api('DELETE', '/api/events/current').catch(() => null);
      await api('DELETE', `/api/events/${event.id}`).catch(() => null);
      await api('DELETE', `/api/songs/${song.id}`, undefined, 'tecnico').catch(() => null);
    }
  });
});
