// Movement diagnostics: is the chunk column loaded, what block is below, can the bot walk?
const L = require('./lib')
const bot = L.createBot('dcbot_diag' + (process.argv[2] || '').replace(/\./g, ''), process.argv[2] ? { version: process.argv[2] } : {})
let chunkErrors = 0
bot._client.on('error', e => { chunkErrors++; L.log('diag', 'client-error', e.message) })
bot.once('spawn', async () => {
  await L.sleep(3000)
  const pos = bot.entity.position
  const col = bot.world.getColumnAt ? bot.world.getColumnAt(pos) : null
  const below = bot.blockAt(pos.offset(0, -1, 0))
  const ring = [bot.blockAt(pos.offset(0, -1, 3)), bot.blockAt(pos.offset(9, -1, 0)), bot.blockAt(pos.offset(2, -1, 1))].map(b => b && b.name)
  L.log('diag', 'state', { pos, column: !!col, below: below && below.name, ring, version: bot.version, speedAttr: JSON.stringify(bot.entity.attributes || {}).slice(0, 300), physicsEnabled: bot.physicsEnabled, chunkErrors })
  bot.setControlState('forward', true)
  for (let i = 0; i < 10; i++) { await L.sleep(300); L.log('diag', 'walk', bot.entity.position) }
  bot.setControlState('forward', false)
  bot.quit()
  setTimeout(() => process.exit(0), 500)
})
setTimeout(() => process.exit(3), 30000)
