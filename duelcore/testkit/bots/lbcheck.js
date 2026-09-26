// One bot joins and opens the leaderboard with the /leaderboard command (overall, then a kit). Exit 0 = pass.
const L = require('./lib')
const bot = L.createBot(process.argv[2] || 'dcbot_dlg')
bot.once('spawn', async () => {
  try {
    await L.sleep(4000)
    for (const cmd of ['leaderboard', 'leaderboard mace']) {
      const before = bot.dc.dialogs.length
      bot.chat('/' + cmd)
      const t0 = Date.now()
      while (bot.dc.dialogs.length === before && Date.now() - t0 < 8000) await L.sleep(100)
      if (bot.dc.dialogs.length === before) throw new Error('no dialog for /' + cmd)
      L.log('test', 'dialog', { cmd, title: L.plain(bot.dc.dialogs[bot.dc.dialogs.length - 1].title), ms: Date.now() - t0 })
      await L.sleep(1000)
    }
    L.log('test', 'PASS')
    bot.quit()
    setTimeout(() => process.exit(0), 500)
  } catch (e) { L.log('test', 'FAIL', e.message); process.exit(1) }
})
setTimeout(() => { L.log('test', 'FAIL', 'timeout'); process.exit(2) }, 40000)
