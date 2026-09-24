// Walks forward (and jumps) from wherever the operator teleports it, logging physics state every 100 ms.
const L = require('./lib')
const name = process.argv[2] || 'dcbot_step'
const bot = L.createBot(name)
bot.once('spawn', () => {
  const t0 = Date.now()
  let walking = false
  bot.on('forcedMove', () => {
    L.log('test', 'forcedMove', { t: Date.now() - t0, pos: bot.entity.position })
    if (bot.entity.position.x > 9000 && !walking) {
      walking = true
      setTimeout(() => { bot.setControlState('forward', true); bot.setControlState('jump', true); L.log('test', 'go', {}) }, 2000)
    }
  })
  const t = setInterval(() => {
    const e = bot.entity
    L.log('test', 'st', { t: Date.now() - t0, y: +e.position.y.toFixed(3), z: +e.position.z.toFixed(3), g: e.onGround,
      cv: e.isCollidedVertically, ch: e.isCollidedHorizontally, vy: +e.velocity.y.toFixed(4), vz: +e.velocity.z.toFixed(4),
      phys: bot.physicsEnabled, yaw: +e.yaw.toFixed(2) })
    if (Date.now() - t0 > 20000) { clearInterval(t); bot.quit(); setTimeout(() => process.exit(0), 300) }
  }, 100)
})
