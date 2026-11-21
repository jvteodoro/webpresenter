import { execFileSync } from 'node:child_process';
import { mkdirSync } from 'node:fs';
import { FIXTURES_DIR } from './fixtures';

/** Generates small media files with ffmpeg and checks that the API is up. */
export default async function globalSetup(): Promise<void> {
  mkdirSync(FIXTURES_DIR, { recursive: true });
  const ffmpeg = (...args: string[]) => execFileSync('ffmpeg', ['-loglevel', 'error', '-y', ...args]);
  ffmpeg('-f', 'lavfi', '-i', 'testsrc2=size=640x360:rate=25', '-f', 'lavfi', '-i', 'sine=frequency=440',
    '-t', '3', '-c:v', 'libx264', '-pix_fmt', 'yuv420p', '-c:a', 'aac', '-shortest', `${FIXTURES_DIR}/clip.mp4`);
  ffmpeg('-f', 'lavfi', '-i', 'testsrc2=size=800x450', '-frames:v', '1', `${FIXTURES_DIR}/slide.png`);

  const health = await fetch('http://localhost:8090/actuator/health').catch(() => null);
  if (!health?.ok) {
    throw new Error('Central server is not running on :8090 (run ./gradlew :server:bootRun)');
  }
}
