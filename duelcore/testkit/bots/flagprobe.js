// Joins and prints the nametag/tab team prefixes the bot receives: flag heads show up as "object" (player head)
// parts in them. Exit 0 after a few seconds.
const L = require('./lib')
const bot = L.createBot(process.argv[2] || 'dcbot_dlg')
let seen = 0
const has = (x, re) => re.test(JSON.stringify(x))
bot._client.on('teams', p => {
  if (!p.prefix || seen >= 6) return
  const s = JSON.stringify(p.prefix)
  if (!/player|object|texture/i.test(s)) return
  seen++
  L.log('test', 'team', { team: p.team, players: p.players, prefix: s.slice(0, 700) })
})
bot.once('spawn', async () => {
  await L.sleep(6000)
  L.log('test', seen > 0 ? 'PASS' : 'FAIL', { teamsWithHeads: seen })
  bot.quit()
  setTimeout(() => process.exit(seen > 0 ? 0 : 1), 300)
})
setTimeout(() => { L.log('test', 'FAIL', 'timeout'); process.exit(2) }, 30000)
