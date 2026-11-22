import { countdown, timerTarget } from './timer';

describe('timer', () => {
  it('counts down rounding up and up past the target', () => {
    expect(countdown(300_000)).toBe('5:00');
    expect(countdown(299_001)).toBe('5:00');
    expect(countdown(1)).toBe('0:01');
    expect(countdown(3_723_000)).toBe('1:02:03');
    expect(countdown(-7_400)).toBe('+0:07');
  });

  it('targets a local time on the day the item started, or a duration after it', () => {
    const start = new Date(2026, 10, 21, 9, 40, 0).getTime();
    expect(timerTarget({ targetTime: '10:10', durationMs: null, signalsBeforeEndMs: [] }, start)).toBe(
      new Date(2026, 10, 21, 10, 10, 0).getTime(),
    );
    expect(timerTarget({ targetTime: null, durationMs: 120_000, signalsBeforeEndMs: [] }, start)).toBe(start + 120_000);
    expect(timerTarget({ targetTime: null, durationMs: null, signalsBeforeEndMs: [] }, start)).toBeNull();
  });
});
