// Joins, says ":SE: hi :<own name>: :XX:" in chat and checks the line it gets back: a flag and a player head (object
// parts) with hover texts, ":XX:" left as typed. Also checks that flag heads in team prefixes carry a hover.
const L = require('./lib')
const name = process.argv[2] || 'dcbot_dlg'
const bot = L.createBot(name)
let line = null
let prefixHover = false
bot._client.on('teams', p => {
  if (p.prefix && /hover/i.test(JSON.stringify(p.prefix)) && /object|player/i.test(JSON.stringify(p.prefix))) prefixHover = true
})
const grab = (data) => {
  const s = JSON.stringify(data)
  if (s.includes('XX') && !line) line = s
}
bot._client.on('system_chat', grab)
bot._client.on('player_chat', grab)
bot.once('spawn', async () => {
  await L.sleep(3000)
  bot.chat(`:SE: hi :${bot.username}: :XX:`)
  await L.sleep(3000)
  const ok = !!line && /hover/i.test(line) && (line.match(/object|player/gi) || []).length >= 2 && line.includes(':XX:')
    && !line.includes(':SE:\\"') && line.includes('Sweden') && line.includes(bot.username)
  L.log('test', 'line', { line: line && line.slice(0, 3000) })
  L.log('test', ok && prefixHover ? 'PASS' : 'FAIL', { line: !!line, prefixHover })
  bot.quit()
  setTimeout(() => process.exit(ok ? 0 : 1), 300)
})
setTimeout(() => { L.log('test', 'FAIL', 'timeout'); process.exit(2) }, 30000)
