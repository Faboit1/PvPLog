// Anti-spam check: a normal message goes through, the same message again is blocked, an obfuscated ad is blocked,
// "gg" twice a few seconds apart goes through. Prints what the bot sees; PASS when it matches.
const L = require('./lib')
const bot = L.createBot(process.argv[2] || 'dcbot_dlg')
const seen = []
bot.on('messagestr', m => seen.push(m))
const said = t => seen.some(m => m.includes(t))
bot.once('spawn', async () => {
  await L.sleep(3000)
  bot.chat('hello there my good friends'); await L.sleep(1500)
  bot.chat('hello there my good friends'); await L.sleep(1500)
  bot.chat('join evil-server d o t net now'); await L.sleep(1500)
  bot.chat('gg'); await L.sleep(3500)
  bot.chat('gg'); await L.sleep(2000)
  const first = seen.filter(m => m.includes('hello there my good friends')).length
  const ad = said('evil-server')
  const gg = seen.filter(m => /(^|:\s*)gg$/.test(m)).length
  L.log('test', 'seen', seen.slice(-12))
  const ok = first === 1 && !ad && gg === 2
  L.log('test', ok ? 'PASS' : 'FAIL', { first, ad, gg })
  bot.quit(); setTimeout(() => process.exit(ok ? 0 : 1), 300)
})
setTimeout(() => { L.log('test', 'FAIL', 'timeout'); process.exit(2) }, 40000)
