// Captures the README screenshots from a running gateway (v4 C.4). Run through
// scripts/screenshots.sh, which starts a fresh stack, replays the demo traffic
// and passes everything below as environment variables:
//
//   GATEWAI_URL         the gateway (it serves the dashboard too)
//   GATEWAI_API_KEY     an admin key: the decision trace is admin-only
//   SCREENSHOT_SOURCE   what produced the traffic, stamped into every image
//   SCREENSHOT_PROMPT   the prompt the "why this decision" panel explains
//   SCREENSHOT_OUT      where the PNGs go
//
// Each image carries its own provenance line, so it still says where it comes
// from once copied out of the README.
import { mkdir } from 'node:fs/promises';
import { join } from 'node:path';
import { env, exit, stderr } from 'node:process';
import { chromium } from 'playwright';

const required = [
  'GATEWAI_URL',
  'GATEWAI_API_KEY',
  'SCREENSHOT_SOURCE',
  'SCREENSHOT_PROMPT',
  'SCREENSHOT_OUT',
];
const missing = required.filter((name) => !env[name]);
if (missing.length > 0) {
  stderr.write(`Missing environment variable(s): ${missing.join(', ')}\n`);
  exit(1);
}

const TIMEOUT_MS = 30_000;
const MARGIN_PX = 24;
const outDir = env.SCREENSHOT_OUT;

/** Appends the provenance line inside `parent`, styled like the dashboard's notes. */
async function stamp(parent, text) {
  await parent.evaluate((element, line) => {
    const note = document.createElement('p');
    note.className = 'scope-note screenshot-stamp';
    note.textContent = line;
    element.append(note);
  }, text);
  return parent.locator('p.screenshot-stamp');
}

/**
 * The element's box in page coordinates. `boundingBox()` is relative to the
 * viewport, so it drifts as soon as a click scrolls; a full-page clip is not.
 */
function pageBox(locator) {
  return locator.evaluate((element) => {
    const rect = element.getBoundingClientRect();
    return {
      x: rect.left + window.scrollX,
      y: rect.top + window.scrollY,
      width: rect.width,
      height: rect.height,
    };
  });
}

/** Screenshots the page band from the top of `from` to the bottom of `to`, `main`'s width. */
async function capture(page, file, from, to) {
  const frame = await pageBox(page.locator('main'));
  // Half a margin above: a full one would catch the edge of whatever sits there.
  const top = (await pageBox(from)).y - MARGIN_PX / 2;
  const bottom = await pageBox(to);
  await page.screenshot({
    path: join(outDir, file),
    fullPage: true,
    clip: {
      x: frame.x,
      y: top,
      width: frame.width,
      height: bottom.y + bottom.height + MARGIN_PX - top,
    },
  });
}

const browser = await chromium.launch();
try {
  // Fixed locale, time zone and size: the same traffic gives the same picture.
  const page = await browser.newPage({
    viewport: { width: 960, height: 900 },
    deviceScaleFactor: 2,
    locale: 'en-GB',
    timezoneId: 'UTC',
    colorScheme: 'dark',
  });
  page.setDefaultTimeout(TIMEOUT_MS);
  await page.goto(env.GATEWAI_URL);
  await page.getByLabel('API key').fill(env.GATEWAI_API_KEY);
  await page.getByRole('button', { name: 'Test connection' }).click();
  await page.locator('table.decisions').waitFor();
  await mkdir(outDir, { recursive: true });

  // Overview: from the title to the model mix — the figures, not the admin forms.
  const modelMix = page.locator('section.trends', { hasText: 'Model mix' });
  const overviewStamp = await stamp(modelMix, env.SCREENSHOT_SOURCE);
  await capture(page, 'dashboard.png', page.locator('header'), overviewStamp);
  await overviewStamp.evaluate((note) => note.remove());

  // "Why this decision": one prompt from the replayed set explained — which words
  // carried the route, and where it would have gone. The 20-row trace above it
  // is left out of the picture: it would triple its height.
  const panel = page.locator('section.admin', { hasText: 'Why this decision' });
  await panel.locator('textarea').fill(env.SCREENSHOT_PROMPT);
  await panel.getByRole('button', { name: 'Explain this prompt' }).click();
  await panel.locator('.explanation').waitFor();
  const panelStamp = await stamp(panel, env.SCREENSHOT_SOURCE);
  await capture(page, 'decision-explanation.png', panel.locator('.explain-prompt'), panelStamp);
} finally {
  await browser.close();
}
