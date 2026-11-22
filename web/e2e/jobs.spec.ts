import { expect, test } from '@playwright/test';
import { login } from './fixtures';

/**
 * The queue page. Without RabbitMQ (as in this suite) a job fails at once with a clear message
 * and can be retried; with a worker it would progress (covered by the server tests and by hand).
 */
test.describe('Fila de processamento', () => {
  test('técnico importa um link do YouTube e acompanha o trabalho na fila', async ({ page }, testInfo) => {
    const title = `E2E fundo ${testInfo.project.name} ${Date.now()}`;
    await login(page, 'tecnico');
    await page.getByRole('tab', { name: 'Fundos musicais' }).click();
    await page.getByRole('button', { name: 'Importar do YouTube' }).click();

    const dialog = page.getByRole('dialog');
    await expect(dialog.getByRole('combobox', { name: 'Destino' })).toContainText('Fundo musical');
    await dialog.getByLabel('Link do vídeo').fill('nao é um link');
    await dialog.getByLabel('Título (opcional)').fill(title);
    await dialog.getByRole('button', { name: 'Importar' }).click();
    await expect(dialog.getByRole('alert')).toContainText('Link inválido');
    await dialog.getByLabel('Link do vídeo').fill('https://www.youtube.com/watch?v=Xx1MjhzKcYw');
    await dialog.getByRole('button', { name: 'Importar' }).click();
    await expect(page.getByRole('dialog')).toHaveCount(0);

    // The queue shows it with its outcome, updated live.
    await page.getByRole('link', { name: 'Fila' }).click();
    await expect(page.getByRole('heading', { level: 1, name: 'Fila de processamento' })).toBeVisible();
    const job = page.getByRole('listitem', { name: `Importar · ${title}` });
    await expect(job).toBeVisible();
    await expect(job).toContainText(/Falhou|Na fila|Processando/);

    if ((await job.textContent())?.includes('Falhou')) {
      await expect(job).toContainText('Fila de processamento indisponível');
      await job.getByRole('button', { name: 'Tentar novamente' }).click();
      await expect(job).toContainText('tentativa 2');
    } else {
      await job.getByRole('button', { name: 'Cancelar' }).click();
      await expect(job).toContainText('Cancelado');
    }
    await expect(job.getByRole('button', { name: `Remover Importar · ${title}` })).toBeVisible();
    await job.getByRole('button', { name: `Remover Importar · ${title}` }).click();
    await expect(job).toHaveCount(0);
  });

  test('líder acompanha a fila mas não cria trabalhos', async ({ page }) => {
    await login(page, 'lider', '/eventos');
    await page.goto('/fila');
    await expect(page.getByRole('heading', { level: 1, name: 'Fila de processamento' })).toBeVisible();
    await expect(page.getByRole('button', { name: 'Importar do YouTube' })).toHaveCount(0);
  });
});
