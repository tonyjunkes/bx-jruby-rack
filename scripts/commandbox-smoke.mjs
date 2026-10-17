/** Start extracted candidate packages on CommandBox and verify Ruby, BoxLang, SSE and source isolation. */
import assert from 'node:assert/strict';
import fs from 'node:fs';
import path from 'node:path';
import net from 'node:net';
import { spawnSync } from 'node:child_process';

const root = path.resolve('build', 'commandbox-smoke-' + process.pid);
const candidate = path.resolve('build/consumer/modules/bx-jruby-rack');
fs.mkdirSync(root, { recursive: true });
fs.cpSync(path.join(candidate, 'examples/commandbox'), root, { recursive: true });
assert.ok(fs.existsSync(path.join(root, '.gitignore')), 'packaged starter .gitignore');
for (const name of ['bx-jruby', 'bx-jruby-rack']) {
    fs.cpSync(path.resolve('build/consumer/modules', name), path.join(root, 'modules', name), { recursive: true });
}
const probe = net.createServer();
await new Promise(resolve => probe.listen(0, '127.0.0.1', resolve));
const port = probe.address().port;
await new Promise(resolve => probe.close(resolve));
const serverConfig = JSON.parse(fs.readFileSync(path.join(root, 'server.json'), 'utf8'));
serverConfig.name = 'bx-jruby-rack-smoke-' + process.pid;
serverConfig.web.http.port = port;
fs.writeFileSync(path.join(root, 'server.json'), JSON.stringify(serverConfig, null, 2));
const box = process.env.BOX_BINARY || (process.platform === 'win32' ? 'box.exe' : 'box');

function runBox(label, args) {
    const result = spawnSync(box, args, { cwd: root, encoding: 'utf8', windowsHide: true, timeout: 240000 });
    fs.writeFileSync(path.join(root, label + '.log'), (result.stdout || '') + (result.stderr || ''));
    if (result.error) throw result.error;
    assert.equal(result.status, 0, label + ': inspect ' + root);
}

try {
    runBox('setup', ['task', 'run', ':installGems=false', ':bundlePath=' + path.resolve('build/bundles/custom')]);
    runBox('start', ['server', 'start', 'serverConfigFile=server.json', 'openBrowser=false', 'trayEnable=false']);
    for (const [uri, expected, content] of [
        ['/', 200, 'BoxLang says 42'], ['/ruby', 200, 'Ruby in BoxLang'],
        ['/ruby/events', 200, 'data: 2'], ['/app/config.ru', 404], ['/app/Gemfile', 404],
        ['/config/boxlang.json', 404], ['/modules/bx-jruby/box.json', 404], ['/box.json', 404]
    ]) {
        const response = await fetch('http://127.0.0.1:' + port + uri, { signal: AbortSignal.timeout(15000) });
        const body = await response.text();
        assert.equal(response.status, expected, uri + ': ' + body.slice(0, 300));
        if (content) assert.ok(body.includes(content), uri);
        console.log(uri + ': ' + response.status);
    }
} finally {
    runBox('stop', ['server', 'stop', 'serverConfigFile=server.json']);
}
console.log('CommandBox candidate package checks passed: ' + root);
