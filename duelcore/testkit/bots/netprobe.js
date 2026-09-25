// TCP reachability from this container: node netprobe.js
const net = require('net')
const targets = ['172.18.0.1:25569', '172.17.0.1:25569', '37.27.67.240:25569']
let left = targets.length
for (const t of targets) {
  const [host, port] = t.split(':')
  const s = net.connect({ host, port: +port, timeout: 4000 })
  const done = r => { console.log(JSON.stringify({ t, r })); s.destroy(); if (--left === 0) process.exit(0) }
  s.on('connect', () => done('open'))
  s.on('timeout', () => done('timeout'))
  s.on('error', e => done(e.code))
}
