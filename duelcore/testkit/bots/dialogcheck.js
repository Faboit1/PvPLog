// One bot joins, opens the queue, leaderboard, profile, settings and spectate dialogs from the hotbar. Exit 0 = pass.
const L = require('./lib')
const bot = L.createBot(process.argv[2] || 'dcbot_dlg')
bot.once('spawn', async () => {
  try {
    await L.sleep(2500)
    for (const [slot, re] of [[0, /Play/], [2, /Overall/], [4, /dcbot_dlg/], [6, /Settings/]]) {
      const d = await L.openFromHotbar(bot, slot, re)
      L.log('test', 'dialog', { slot, title: L.plain(d.title), buttons: L.buttons(d).length })
      await L.sleep(700)
    }
    L.log('test', 'PASS')
    bot.quit()
    setTimeout(() => process.exit(0), 500)
  } catch (e) { L.log('test', 'FAIL', e.message); process.exit(1) }
})
setTimeout(() => { L.log('test', 'FAIL', 'timeout'); process.exit(2) }, 40000)
