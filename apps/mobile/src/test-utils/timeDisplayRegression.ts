import renderer from 'react-test-renderer';
import { simulateDeviceTimezone } from './deviceTimezone';

export const TIME_BOUNDARY = '2026-11-26T17:00:00Z';
export const VIETNAM_MOMENT = '00:00 27 thg 11, 2026';
export const VIETNAM_DAY = '27 thg 11, 2026';

/** Render real callers under device zones on both sides of the date line; always restore the runtime. */
export async function expectTimeDisplay(
  mount: () => renderer.ReactTestRenderer | Promise<renderer.ReactTestRenderer>,
  expected: string[],
) {
  let firstText: string | undefined;
  for (const zone of ['UTC', 'America/Los_Angeles', 'Pacific/Auckland', 'Asia/Ho_Chi_Minh']) {
    const restore = simulateDeviceTimezone(zone);
    let tree: renderer.ReactTestRenderer | undefined;
    try {
      tree = await mount();
      const text = tree.root.findAll((node) => (node.type as unknown) === 'Text')
        .map((node) => node.children.filter((child) => typeof child === 'string').join(''))
        .join('\n');
      expected.forEach((value) => expect(text).toContain(value));
      expect(text).not.toMatch(/Invalid Date|NaN/);
      if (firstText === undefined) firstText = text;
      else expect(text).toBe(firstText);
    } finally {
      if (tree) renderer.act(() => tree!.unmount());
      restore();
    }
  }
}
