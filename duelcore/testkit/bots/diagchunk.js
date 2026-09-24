// Logs whether the bot has the chunk it stands in and whether physics runs (falls/lands). The operator teleports
// it around with /execute in duelcore:arenas run tp <name> x y z. args: <name> <seconds>
const L = require('./lib')
const name = process.argv[2] || 'dcbot_diag'
const secs = parseInt(process.argv[3] || '40')
const bot = L.createBot(name)
bot.once('spawn', () => {
  let n = 0
  const t = setInterval(() => {
    const p = bot.entity.position
    const col = bot.world.getColumnAt ? bot.world.getColumnAt(p) : null
    let below = null
    try { const b = bot.blockAt(p.offset(0, -1, 0)); below = b && b.name } catch (e) { below = 'ERR ' + e.message }
    L.log('test', 'probe', { pos: p.floored(), y: p.y.toFixed(2), onGround: bot.entity.onGround, column: !!col,
      below, dim: bot.game.dimension, chunks: Object.keys(bot.world.async ? {} : {}).length })
    if (++n >= secs) { clearInterval(t); bot.quit(); setTimeout(() => process.exit(0), 500) }
  }, 1000)
})
bot._client.on('error', e => L.log('test', 'client-error', e.message))
bot._client.on('packet', (d, meta) => { if (meta.name === 'map_chunk' && !d.chunkData) L.log('test', 'chunk-no-data', meta) })
