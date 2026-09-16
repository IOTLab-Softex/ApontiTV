const { test } = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const puppeteer = require('../app/javascript/node_modules/puppeteer');

test('web login fills once, waits for dynamic forms, and rejects foreign origins/actions', async () => {
  const source = fs.readFileSync(path.join(__dirname, '../android_player_app/app/src/main/java/br/com/softextv/player/WebPageLogin.kt'), 'utf8');
  const script = source.match(/internal val SCRIPT = """([\s\S]*?)"""\.trimIndent/)[1]
    .replace('__LOGIN__', JSON.stringify({ username: 'viewer', password: 'quotes"\\\nsecret' }))
    .replace('__TARGET__', JSON.stringify('http://example.test/dashboard'));
  const browser = await puppeteer.launch({ headless: true, executablePath: 'C:/Program Files (x86)/Microsoft/Edge/Application/msedge.exe' });
  try {
    const page = await browser.newPage();
    await page.setRequestInterception(true);
    page.on('request', request => request.respond({ status: 200, contentType: 'text/html', body: '<html><body></body></html>' }));
    await page.goto('http://example.test/login');
    assert.equal(await page.evaluate(script), 'waiting');
    const form = '<form><input name="user"><input type="password"><button type="submit">Login</button></form>';
    await page.evaluate(html => {
      document.body.innerHTML = html;
      window.submits = 0;
      window.inputs = 0;
      document.querySelector('form').onsubmit = event => { event.preventDefault(); window.submits++; };
      document.addEventListener('input', () => window.inputs++);
    }, form);
    assert.equal(await page.evaluate(script), 'submitted');
    assert.equal(await page.evaluate(script), 'stopped');
    await new Promise(resolve => setTimeout(resolve, 250));
    // Password inputs strip line breaks, as they also do with manual entry.
    assert.deepEqual(await page.evaluate(() => [document.querySelector('[name=user]').value, document.querySelector('[type=password]').value, window.submits, window.inputs]), ['viewer', 'quotes"\\secret', 1, 2]);
    await page.goto('http://example.test/login');
    await page.setContent(form.replace('<form>', '<form action="http://other.test/login">'));
    assert.equal(await page.evaluate(script), 'stopped');
    assert.equal(await page.$eval('[type=password]', input => input.value), '');
    await page.goto('http://other.test/login');
    await page.setContent(form);
    assert.equal(await page.evaluate(script), 'stopped');
    assert.equal(await page.$eval('[type=password]', input => input.value), '');
  } finally {
    await browser.close();
  }
});
