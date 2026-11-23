import { expect, test } from '@playwright/test';
import { join } from 'node:path';
import { FakePlayer, api, approveDevice, deleteDevice, uploadMedia } from './fake-player';
import { FIXTURES_DIR, login } from './fixtures';

test.describe('Aparência dos slides', () => {
  test('padrão da música, do evento e do item chegam ao player', async ({ page }, testInfo) => {
    const tag = `E2E aparência ${testInfo.project.name} ${Date.now()}`;
    const image = await uploadMedia(join(FIXTURES_DIR, 'slide.png'), `${tag} fundo`, 'image/png', 'SONG_BACKGROUND');
    const song = (await api('POST', '/api/songs', {
      title: `${tag} hino`,
      lyrics: { parts: [{ id: 'v', kind: 'VERSE', label: 'E', slides: [{ lines: ['Santo, santo'] }] }], order: ['v'] },
    }, 'tecnico')).id;
    const event = (await api('POST', '/api/events', { title: `${tag} culto` })).id;
    await api('POST', `/api/events/${event}/items`, { type: 'SONG', songId: song });
    await api('POST', `/api/events/${event}/items`, { type: 'SLIDE', slideTitle: `${tag} avisos` });
    const player = await FakePlayer.connect(`e2e-style-${testInfo.project.name}-${Date.now()}`);
    try {
      await player.next('PAIRING_PENDING');
      await approveDevice(player.deviceId, `${tag} telão`);

      // The song's own look: background and colour.
      await login(page, 'tecnico', '/eventos');
      await page.goto(`/musicas/${song}`);
      await page.getByRole('button', { name: 'Aparência dos slides' }).click();
      let dialog = page.getByRole('dialog');
      await dialog.getByRole('button', { name: `${tag} fundo` }).click();
      await dialog.getByRole('button', { name: 'Cor #ffd27a' }).click();
      await expect(dialog.getByLabel('Prévia do slide')).toBeVisible();
      await dialog.getByRole('button', { name: 'Aplicar' }).click();
      await expect(page.getByText('Aparência salva')).toBeVisible();

      // The event: only a bigger text. The slide item: its own colour.
      await page.goto(`/eventos/${event}`);
      await page.getByRole('button', { name: 'Opções do evento' }).click();
      await page.getByRole('menuitem', { name: 'Aparência dos slides do evento' }).click();
      dialog = page.getByRole('dialog');
      await dialog.getByRole('checkbox', { name: /Usar o padrão de cada música/ }).nth(2).uncheck();
      await dialog.getByLabel('Tamanho do texto').fill('130');
      await dialog.getByRole('button', { name: 'Aplicar' }).click();
      await expect(page.getByRole('dialog')).toHaveCount(0);

      const slideItem = page.getByRole('listitem', { name: `${tag} avisos` });
      await slideItem.getByRole('button', { name: 'Aparência' }).click();
      dialog = page.getByRole('dialog');
      await dialog.getByRole('checkbox', { name: /Usar o do evento/ }).nth(1).uncheck();
      await dialog.getByRole('button', { name: 'Cor #bfe3ff' }).click();
      await dialog.getByRole('button', { name: 'Aplicar' }).click();
      await expect(slideItem).toContainText('cor própria');

      await page.getByRole('button', { name: 'Preparar nos players' }).click();
      const plan = await player.nextPrepareFor(`${tag} culto`);
      expect(plan.items[0].style.background.mediaId).toBe(image); // the song's
      expect(plan.items[0].style.textColor).toBe('#ffd27a');      // the song's
      expect(plan.items[0].style.textScale).toBe(130);            // the event's
      expect(plan.items[1].style.textColor).toBe('#bfe3ff');      // the item's
      expect(plan.items[1].style.textScale).toBe(130);            // the event's
      expect(plan.items[1].style.background).toBeNull();          // a text slide has no song beneath
    } finally {
      player.close();
      await deleteDevice(player.deviceId).catch(() => undefined);
      await api('DELETE', '/api/events/current', undefined, 'tecnico').catch(() => undefined);
      await api('DELETE', `/api/events/${event}`).catch(() => undefined);
      await api('DELETE', `/api/songs/${song}`, undefined, 'tecnico').catch(() => undefined);
      await api('DELETE', `/api/media/${image}`, undefined, 'tecnico').catch(() => undefined);
    }
  });
});
