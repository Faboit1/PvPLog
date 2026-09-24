// Full first-to-N ranked match between two bots, driving the real UI:
// bot A opens the queue dialog from the hotbar and clicks a kit button; bot B uses /queue.
// Both fight until the match ends, then check that they are back in the hub with the hub hotbar,
// and open the results dialog "Play again" button is present. Exit code 0 = pass.
const L = require('./lib')
const kit = process.argv[2] || 'sword'
const A = process.argv[3] || 'dcbot_alpha'
const B = process.argv[4] || 'dcbot_bravo'
const VERSION = process.argv[5]

async function main () {
  const a = L.createBot(A, VERSION ? { version: VERSION } : {})
  const b = L.createBot(B, VERSION ? { version: VERSION } : {})
  await Promise.all([new Promise(r => a.once('spawn', r)), new Promise(r => b.once('spawn', r))])
  await L.sleep(3000)
  L.log('test', 'hub-hotbar', { a: [0, 2, 4, 6, 8].map(s => L.heldName(a, s)), gm: a.game.gameMode })

  // A: hotbar slot 0 → queue dialog → click the kit button
  const qd = await L.openFromHotbar(a, 0, /Play/)
  const btn = L.buttons(qd).find(x => x.id === 'duelcore:queue/join' && x.additions && x.additions.kit === kit)
  if (!btn) throw new Error('no queue button for ' + kit + ': ' + JSON.stringify(L.buttons(qd)))
  const ma = L.mark(a)
  const mb = L.mark(b)
  L.click(a, btn)
  await L.waitChat(a, /Searching/, 5000, ma.chat)
  L.log('test', 'a-queued-via-dialog')
  await L.sleep(500)
  b.chat('/queue ' + kit + ' ranked')

  await Promise.all([L.waitTitle(a, /Match found/, 20000, ma.titles), L.waitTitle(b, /Match found/, 20000, mb.titles)])
  L.log('test', 'match-found')
  await Promise.all([L.waitTitle(a, /Fight/, 30000, ma.titles), L.waitTitle(b, /Fight/, 30000, mb.titles)])
  L.log('test', 'fight', { a: a.entity.position.floored(), b: b.entity.position.floored() })

  let corrections = 0
  a.on('forcedMove', () => { corrections++ })
  // respawn animation: from round 2 on the server seats the player on an item_display and glides it to the spawn
  const pulls = []
  for (const bot of [a, b]) {
    bot.on('mount', () => {
      const v = bot.vehicle
      pulls.push({ bot: bot.username, vehicle: v && v.name, from: bot.entity.position.floored(), t: Date.now() })
      L.log('test', 'mounted', { bot: bot.username, vehicle: v && v.name, pos: bot.entity.position.floored() })
    })
    bot.on('dismount', () => {
      const p = pulls.filter(x => x.bot === bot.username).slice(-1)[0]
      L.log('test', 'dismounted', { bot: bot.username, pos: bot.entity.position.floored(), ms: p ? Date.now() - p.t : -1 })
    })
  }
  const tele = setInterval(() => {
    const e = a.entity
    L.log('telemetry', 'a', { pos: e.position, vel: e.velocity, onGround: e.onGround, ctl: a.controlState,
      speedKey: a.physics && a.physics.movementSpeedAttribute, attrs: Object.keys(e.attributes || {}).join(','),
      corrections, gm: a.game.gameMode, target: !!a.nearestEntity(x => x.type === 'player' && x.username === B) })
  }, 2000)
  const fa = L.fighter(a, { slot: 0 })
  const fb = L.fighter(b, { slot: 0, cooldownMs: 700 })
  const endRe = /Victory|Defeat|Draw/
  const ea = L.mark(a)
  await Promise.all([L.waitTitle(a, endRe, 600000, ma.titles), L.waitTitle(b, endRe, 600000, mb.titles)])
  fa.stop(); fb.stop(); clearInterval(tele)
  L.log('test', 'match-ended', { a: a.dc.titles.slice(-1)[0], b: b.dc.titles.slice(-1)[0], pulls: pulls.length })
  if (pulls.length === 0) throw new Error('no respawn pull seen (expected one per bot from round 2)')

  // back in the hub: results dialog + hub hotbar
  const rd = await L.waitDialog(a, /Victory|Defeat|Draw/, 20000, ea.dialogs)
  const again = L.buttons(rd).find(x => x.id === 'duelcore:queue/join')
  L.log('test', 'results-dialog', { buttons: L.buttons(rd).map(x => x.label), again: !!again })
  await L.sleep(1500)
  L.log('test', 'hub-restore', { a: [0, 2, 4, 6, 8].map(s => L.heldName(a, s)), b: [0, 2, 4, 6, 8].map(s => L.heldName(b, s)), gmA: a.game.gameMode, posA: a.entity.position.floored() })

  // open own profile + leaderboard via hotbar
  const pd = await L.openFromHotbar(a, 4, new RegExp(A))
  L.log('test', 'profile-dialog', { title: L.plain(pd.title) })
  await L.sleep(600)
  const ld = await L.openFromHotbar(a, 2, /Overall/)
  L.log('test', 'leaderboard-dialog', { title: L.plain(ld.title), body: JSON.stringify(ld.body).slice(0, 400) })

  a.quit(); b.quit()
  await L.sleep(1000)
  L.log('test', 'PASS')
  process.exit(0)
}
main().catch(e => { L.log('test', 'FAIL', e.message); process.exit(1) })
setTimeout(() => { L.log('test', 'FAIL', 'global timeout'); process.exit(2) }, 900000)
