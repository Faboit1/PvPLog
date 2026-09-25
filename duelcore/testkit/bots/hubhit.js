// Does a bot's melee attack reach the server? A hits B in the hub for a few seconds (damage is cancelled
// there, but EntityDamageByEntityEvent must still fire; watch /duelcore debug trace).
const L = require('./lib')
const a = L.createBot('dcbot_hita')
const b = L.createBot('dcbot_hitb')
let swings = 0
Promise.all([new Promise(r => a.once('spawn', r)), new Promise(r => b.once('spawn', r))]).then(async () => {
  await L.sleep(4000)
  const f = L.fighter(a, { slot: 0 })
  const orig = a.attack.bind(a)
  a.attack = (e) => { swings++; return orig(e) }
  await L.sleep(8000)
  f.stop()
  const tb = a.players.dcbot_hitb && a.players.dcbot_hitb.entity
  L.log('hubhit', 'done', { swings, dist: tb ? tb.position.distanceTo(a.entity.position) : null, entityId: tb && tb.id })
  a.quit(); b.quit()
  setTimeout(() => process.exit(0), 500)
})
setTimeout(() => process.exit(2), 30000)
