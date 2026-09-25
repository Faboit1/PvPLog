// Forfeit on disconnect: two bots start a ranked match; after the fight starts bot B disconnects.
// Expect: A gets Victory + "left the match" chat + rating change; B rejoins into the hub with the hub hotbar.
const L = require('./lib')
const kit = process.argv[2] || 'sword'
const A = 'dcbot_ffa'
const B = 'dcbot_ffb'

async function main () {
  const a = L.createBot(A)
  let b = L.createBot(B)
  await Promise.all([new Promise(r => a.once('spawn', r)), new Promise(r => b.once('spawn', r))])
  await L.sleep(2500)
  const ma = L.mark(a)
  a.chat('/queue ' + kit + ' ranked')
  await L.sleep(400)
  b.chat('/queue ' + kit + ' ranked')
  await L.waitTitle(a, /Fight/i, 60000, ma.titles)
  L.log('test', 'fight-started')
  await L.sleep(2000)
  b.quit('forfeit test')
  L.log('test', 'b-disconnected')
  await L.waitTitle(a, /Victory/, 15000, ma.titles)
  const left = await L.waitChat(a, /left the match/, 5000, ma.chat)
  const rating = await L.waitChat(a, /Rating/, 5000, ma.chat)
  L.log('test', 'a-result', { left, rating })
  await L.sleep(1500)
  b = L.createBot(B)
  await new Promise(r => b.once('spawn', r))
  await L.sleep(3000)
  L.log('test', 'b-rejoined', { pos: b.entity.position.floored(), gm: b.game.gameMode, hotbar: [0, 2, 4, 6, 8].map(s => L.heldName(b, s)) })
  const inHub = b.game.gameMode === 'adventure' && L.heldName(b, 0) === 'netherite_sword'
  a.quit(); b.quit()
  await L.sleep(800)
  L.log('test', inHub ? 'PASS' : 'FAIL', { inHub })
  process.exit(inHub ? 0 : 1)
}
main().catch(e => { L.log('test', 'FAIL', e.message); process.exit(1) })
setTimeout(() => { L.log('test', 'FAIL', 'global timeout'); process.exit(2) }, 180000)
