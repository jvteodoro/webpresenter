import { expect, test } from '@playwright/test';
import { join } from 'node:path';
import { FakePlayer, api, approveDevice, deleteDevice, uploadMedia } from './fake-player';
import { FIXTURES_DIR, login } from './fixtures';

function hhmm(date: Date): string {
  return `${String(date.getHours()).padStart(2, '0')}:${String(date.getMinutes()).padStart(2, '0')}`;
}

test.describe('Relógio e cronômetro', () => {
  test('cronômetro até o fim da cena com sinal, relógio, e relógio na tela de descanso', async ({ page }, testInfo) => {
    const tag = `E2E cronômetro ${testInfo.project.name} ${Date.now()}`;
    const bell = await uploadMedia(join(FIXTURES_DIR, 'song.m4a'), `${tag} sino`, 'audio/mp4', 'SOUND_EFFECT');
    // A scene that ends in 40 minutes (local time), so the timer has a future target.
    const ends = new Date(Date.now() + 40 * 60_000);
    const starts = new Date(Date.now() - 10 * 60_000);
    const event = (await api('POST', '/api/events', { title: `${tag} culto` })).id;
    await api('POST', `/api/events/${event}/items`, {
      type: 'SCENE', sceneTitle: 'Escola Sabatina', sceneStarts: hhmm(starts), sceneEnds: hhmm(ends),
    });
    const player = await FakePlayer.connect(`e2e-timer-${testInfo.project.name}-${Date.now()}`);
    try {
      await player.next('PAIRING_PENDING');
      await approveDevice(player.deviceId, `${tag} telão`);

      await login(page, 'tecnico', '/eventos');
      await page.getByRole('link', { name: new RegExp(`${tag} culto`) }).click();

      // Timer to the end of the scene, with a signal 5 minutes before.
      await page.getByRole('button', { name: 'Relógio ou cronômetro' }).click();
      await page.getByRole('menuitem', { name: 'Cronômetro' }).click();
      const dialog = page.getByRole('dialog');
      await dialog.getByLabel('Texto acima do cronômetro (opcional)').fill('Escola Sabatina');
      await expect(dialog.getByRole('radio', { name: /O fim da cena/ })).toBeChecked();
      await dialog.getByRole('button', { name: 'Adicionar sinal' }).click();
      await dialog.getByLabel('Minutos antes do fim, sinal 1').fill('5');
      await dialog.getByRole('combobox', { name: 'Som do sinal 1' }).click();
      await page.getByRole('option', { name: `${tag} sino` }).click();
      await dialog.getByRole('button', { name: 'Salvar' }).click();
      const timerItem = page.getByRole('listitem', { name: 'Escola Sabatina', exact: true });
      await expect(timerItem).toContainText('Até o fim da cena · 1 sinal');

      // A clock after it.
      await page.getByRole('button', { name: 'Relógio ou cronômetro' }).click();
      await page.getByRole('menuitem', { name: 'Relógio' }).click();
      await expect(page.getByRole('listitem', { name: 'Relógio' })).toBeVisible();

      await page.getByRole('button', { name: 'Preparar nos players' }).click();
      const plan = await player.nextPrepareFor(`${tag} culto`);
      expect(plan.items.map((i: { type: string }) => i.type)).toEqual(['TIMER', 'CLOCK']);
      expect(plan.items[0].title).toBe('Escola Sabatina');
      expect(plan.items[0].timer.targetTime).toBe(hhmm(ends));
      expect(plan.items[0].timer.signals[0].beforeEndMs).toBe(300_000);
      expect(plan.items[0].timer.signals[0].sound.mediaId).toBe(bell);

      // Live: the timer counts down on the operator's screen too.
      await page.getByRole('radio', { name: 'Modo de operação' }).click();
      await page.getByRole('button', { name: 'Escola Sabatina' }).click();
      expect((await player.next('PLAY')).itemId).toBe(plan.items[0].itemId);
      const onScreen = page.getByLabel('Relógio no telão');
      await expect(onScreen).toContainText(`até ${hhmm(ends)}`);
      await expect(onScreen.locator('.clock-value')).toHaveText(/^(3[0-9]|40):\d\d$/); // about 40 min left (less when the machine is busy)
      await page.getByRole('button', { name: 'Relógio', exact: true }).click();
      await expect(onScreen.locator('.clock-value')).toHaveText(/^\d\d:\d\d$/);

      // Clock on the rest screen (device setting).
      await page.getByRole('radio', { name: 'Modo administrativo' }).click();
      await page.getByRole('link', { name: 'Dispositivos' }).click();
      const card = page.getByRole('article', { name: `${tag} telão` });
      await card.getByRole('button', { name: 'Configurar' }).click();
      await page.getByRole('switch', { name: 'Mostrar o relógio na tela de descanso' }).click();
      await page.getByRole('dialog').getByRole('button', { name: 'Salvar' }).click();
      for (;;) {
        const config = await player.next('CONFIG');
        if (config.restClock) {
          break;
        }
      }
    } finally {
      player.close();
      await deleteDevice(player.deviceId).catch(() => undefined);
      await api('DELETE', '/api/events/current', undefined, 'tecnico').catch(() => undefined);
      await api('DELETE', `/api/events/${event}`).catch(() => undefined);
      await api('DELETE', `/api/media/${bell}`, undefined, 'tecnico').catch(() => undefined);
    }
  });
});
