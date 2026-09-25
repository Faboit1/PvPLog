// Captures the clientbound position packets a bot gets during a fight (flags, coordinates, teleport ids, timing) to
// find what keeps teleporting bots in place. Exit code 0 always; read the log.
//   node posprobe.js [kit]
const L = require('./lib')
const kit = process.argv[2] || 'sword'

async function main () {
  const a = L.createBot('dcbot_qa')
  const b = L.createBot('dcbot_qb')
  const rec = []
  let fightAt = 0
  for (const bot of [a, b]) {
    bot.once('login', () => {
      let dumped = 0
      bot._client.on('position', p => {
        if (!fightAt) return
        if (dumped < 2 && bot.entity) {
          // what the client sees where the server keeps putting us back: the cells the bot's box would enter
          dumped++
          const at = new (require('vec3'))(p.x, p.y, p.z)
          const yaw = bot.entity.yaw
          const cells = []
          for (let dy = -1; dy <= 2; dy++) {
            for (let dx = -1; dx <= 1; dx++) {
              for (let dz = -1; dz <= 1; dz++) {
                const blk = bot.blockAt(at.offset(dx, dy, dz))
                if (!blk || blk.name === 'air' || blk.name === 'cave_air') continue
                cells.push([dx, dy, dz, blk.name, JSON.stringify(blk.shapes)])
              }
            }
          }
          L.log('test', 'setback-' + bot.username, { at: p, yaw, cells })
        }
        rec.push({ who: bot.username, t: ((Date.now() - fightAt) / 1000).toFixed(2), p: JSON.parse(JSON.stringify(p, (k, v) => typeof v === 'bigint' ? v.toString() : v)) })
      })
    })
  }
  await Promise.all([a, b].map(x => new Promise(r => x.once('spawn', r))))
  await L.sleep(3000)
  const ma = L.mark(a)
  const mb = L.mark(b)
  a.chat('/queue ' + kit)
  await L.sleep(500)
  b.chat('/queue ' + kit)
  await Promise.all([L.waitTitle(a, /Fight/i, 60000, ma.titles), L.waitTitle(b, /Fight/i, 60000, mb.titles)])
  fightAt = Date.now()
  L.log('test', 'fight', { a: a.entity.position, b: b.entity.position })
  for (const bot of [a, b]) {
    const pos = bot.entity.position
    const cells = []
    for (let dy = -1; dy <= 1; dy++) {
      for (const [dx, dz] of [[0, 0], [1, 0], [-1, 0], [0, 1], [0, -1]]) {
        const blk = bot.blockAt(pos.offset(dx, dy, dz))
        if (!blk || blk.name === 'air') continue
        cells.push({ at: [dx, dy, dz], name: blk.name, props: blk.getProperties && blk.getProperties(), shapes: blk.shapes })
      }
    }
    L.log('test', 'around-' + bot.username, { pos, cells })
  }
  // stand still for 3 s (no fighter), then walk forward for 3 s
  await L.sleep(3000)
  L.log('test', 'idle-positions', { n: rec.length, first: rec.slice(0, 6) })
  a.setControlState('forward', true)
  b.setControlState('forward', true)
  const before = rec.length
  for (let i = 0; i < 4; i++) {
    // wander: a new heading every 2 s, jumping when stalled like the fighter does
    for (const bot of [a, b]) bot.look(Math.random() * Math.PI * 2, 0, true)
    await L.sleep(2000)
  }
  a.clearControlStates(); b.clearControlStates()
  L.log('test', 'walk-positions', { n: rec.length - before, sample: rec.slice(before, before + 8), a: a.entity.position, b: b.entity.position })
  L.log('test', 'phys', { a: a.dc.phys, b: b.dc.phys })
  a.chat('/leave'); b.chat('/leave')
  await L.sleep(2000)
  L.log('test', 'pass')
  process.exit(0)
}
main().catch(e => { L.log('test', 'fail', e.message); process.exit(1) })
setTimeout(() => { L.log('test', 'fail', 'global timeout'); process.exit(1) }, 120000)
