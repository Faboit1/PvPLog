// Logs the bot's position every 100 ms for N seconds (the operator throws it with /duelcore debug throw).
const L = require('./lib')
const name = process.argv[2] || 'dcbot_probe'
const secs = parseFloat(process.argv[3] || '25')
const bot = L.createBot(name)
bot._client.on('entity_velocity', p => { if (bot.entity && p.entityId === bot.entity.id) L.log('test', 'vel', p.velocity) })
bot.once('spawn', () => {
  const t0 = Date.now()
  const t = setInterval(() => {
    const p = bot.entity.position
    const info = {}
    for (const [k, dy] of [['feet', 0], ['head', 1], ['below', -1], ['above', 2]]) {
      const b = bot.blockAt(p.offset(0, dy, 0))
      info[k] = b ? b.name + ':' + b.stateId + ':' + b.boundingBox + ':' + JSON.stringify(b.shapes) : 'null'
    }
    L.log('test', 'pos', { t: Date.now() - t0, x: +p.x.toFixed(2), y: +p.y.toFixed(2), z: +p.z.toFixed(2), g: bot.entity.onGround,
      cv: bot.entity.isCollidedVertically, ch: bot.entity.isCollidedHorizontally, vel: bot.entity.velocity, info })
    if (Date.now() - t0 > secs * 1000) { clearInterval(t); bot.quit(); setTimeout(() => process.exit(0), 300) }
  }, 100)
})
