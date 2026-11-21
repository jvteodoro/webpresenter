import { defineConfig, devices } from '@playwright/test';

/**
 * End-to-end tests against the real stack. Before running:
 *   docker compose -f ../deploy/compose.yaml up -d
 *   (cd .. && ./gradlew :server:bootRun)
 * The Angular dev server is started automatically if it is not already running (if you start it
 * yourself, use `npx ng serve --no-hmr --live-reload=false` for stable runs).
 */
export default defineConfig({
  testDir: './e2e',
  globalSetup: './e2e/global-setup.ts',
  timeout: 60_000,
  expect: { timeout: 10_000 },
  fullyParallel: false,
  workers: 1,
  reporter: [['list'], ['html', { open: 'never' }]],
  use: {
    baseURL: 'http://localhost:4200',
    locale: 'pt-BR',
    trace: 'retain-on-failure',
    screenshot: 'only-on-failure',
  },
  projects: [
    { name: 'chromium', use: { ...devices['Desktop Chrome'] } },
    { name: 'firefox', use: { ...devices['Desktop Firefox'] } },
  ],
  webServer: {
    // No HMR/live reload: a rebuild during a run would reload pages mid-test (for example mid-login).
    command: 'npx ng serve --no-hmr --live-reload=false',
    url: 'http://localhost:4200',
    reuseExistingServer: true,
    timeout: 120_000,
  },
});
