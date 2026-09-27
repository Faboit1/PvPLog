// Pings the server list entry like a client would: through the proxy, with the forced host (MC_FAKE_HOST) in the
// handshake, and prints the player count and the MOTD as plain text lines.
const net = require('net')
const host = process.env.MC_HOST || '127.0.0.1'
const port = parseInt(process.env.MC_PORT || '25565', 10)
const fake = process.env.MC_FAKE_HOST || host
const varint = n => { const b = []; do { let x = n & 0x7f; n >>>= 7; if (n) x |= 0x80; b.push(x) } while (n); return Buffer.from(b) }
const str = s => Buffer.concat([varint(Buffer.byteLength(s)), Buffer.from(s)])
const packet = (id, body) => { const p = Buffer.concat([varint(id), body]); return Buffer.concat([varint(p.length), p]) }
const port16 = Buffer.alloc(2); port16.writeUInt16BE(port)
const sock = net.connect(port, host, () => {
  sock.write(packet(0, Buffer.concat([varint(776), str(fake), port16, varint(1)])))
  sock.write(packet(0, Buffer.alloc(0)))
})
let buf = Buffer.alloc(0)
const flat = c => typeof c === 'string' ? c : (c.text || '') + (c.extra || []).map(flat).join('')
sock.on('data', d => {
  buf = Buffer.concat([buf, d])
  const i = buf.indexOf('{')
  if (i < 0) return
  try {
    const res = JSON.parse(buf.slice(i).toString('utf8'))
    console.log(JSON.stringify({ event: 'motd', players: res.players && { online: res.players.online, max: res.players.max },
      lines: flat(res.description).split('\n'), raw: res.description }))
    console.log(JSON.stringify({ event: 'PASS' }))
    process.exit(0)
  } catch (e) { /* not complete yet */ }
})
sock.on('error', e => { console.log(JSON.stringify({ event: 'FAIL', error: String(e) })); process.exit(1) })
setTimeout(() => { console.log(JSON.stringify({ event: 'FAIL', error: 'timeout' })); process.exit(2) }, 15000)
