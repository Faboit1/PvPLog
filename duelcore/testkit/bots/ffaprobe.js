// Probe: three bots in a party FFA; logs each bot's height every 100 ms from "Match found" until 4 s after "Fight!".
const L = require('./lib')
const spawned = bot => new Promise(r => bot.once('spawn', r))
async function main () {
  const bots = ['dcbot_qa', 'dcbot_qb', 'dcbot_qc'].map(n => L.createBot(n))
  await Promise.all(bots.map(spawned))
  await L.sleep(4000)
  for (const b of bots) b.chat('/party leave')
  await L.sleep(1200)
  const [a, b, c] = bots
  a.chat('/party create'); await L.sleep(600)
  for (const o of [b, c]) { a.chat('/party invite ' + o.username); await L.sleep(500); o.chat('/party accept'); await L.sleep(600) }
  const marks = bots.map(L.mark)
  a.chat('/party ffa sword')
  await L.waitTitle(a, /Match found/, 15000, marks[0].titles)
  const series = bots.map(() => [])
  const t0 = Date.now()
  const iv = setInterval(() => bots.forEach((x, i) => x.entity && series[i].push([Date.now() - t0, +x.entity.position.y.toFixed(2)])), 100)
  bots.forEach(x => x._client.on('position', p => L.log(x.username, 'server-pos', { y: p.y, flags: p.flags, t: Date.now() - t0 })))
  await L.waitTitle(a, /Fight/, 40000, marks[0].titles)
  await L.sleep(4000)
  clearInterval(iv)
  bots.forEach((x, i) => {
    const s = series[i]
    const compact = s.filter((p, j) => j === 0 || p[1] !== s[j - 1][1])
    L.log('test', 'heights', { bot: x.username, changes: compact.slice(0, 40), final: s[s.length - 1] })
  })
  for (const x of bots) { x.chat('/party leave') }
  await L.sleep(1500)
  L.log('test', 'pass')
  process.exit(0)
}
main().catch(e => { L.log('test', 'fail', e.message); process.exit(1) })
setTimeout(() => { L.log('test', 'fail', 'global timeout'); process.exit(1) }, 90000)
