// Arena world chunk diagnostics: stays connected; after being teleported by the console, reports what it sees.
const L = require('./lib')
const version = process.argv[2]
const bot = L.createBot('dcbot_diag' + (version ? version.replace(/\./g, '') : ''), version ? { version } : {})
let chunkLoads = 0
bot.on('chunkColumnLoad', () => { chunkLoads++ })
bot._client.on('packet', (d, meta) => { if (meta.name === 'map_chunk' || meta.name === 'level_chunk_with_light') chunkLoads += 0 })
bot.on('forcedMove', () => {
  setTimeout(() => {
    const pos = bot.entity.position
    const col = bot.world.getColumnAt(pos)
    const below = bot.blockAt(pos.offset(0, -1, 0))
    const around = []
    for (let dy = -4; dy <= 0; dy++) { const b = bot.blockAt(pos.offset(0, dy, 0)); around.push(b ? b.name : null) }
    L.log('diag2', 'at', { pos: pos.floored(), column: !!col, below: below && below.name, column_down: around, onGround: bot.entity.onGround, chunkLoads, dim: bot.game.dimension })
  }, 2500)
})
setTimeout(() => { bot.quit(); process.exit(0) }, 25000)
