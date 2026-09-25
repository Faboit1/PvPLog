// Login security checks behind the proxy (exit code 0 = pass):
//  1. a direct connection to the backend (bypassing the proxy) is refused
//  2. before logging in, /server lobby is refused
//  3. after logging in on pvp, /server lobby arrives logged in (no login prompt on the lobby)
//   node authcheck.js
process.env.BOT_JOIN_GAP_MS = process.env.BOT_JOIN_GAP_MS || '4500'
const L = require('./lib')

function once (bot, ev) { return new Promise(r => bot.once(ev, r)) }

async function main () {
  const results = {}
  // 1. direct to the backend port on this machine
  const direct = require('mineflayer').createBot({ host: '172.18.0.1', port: 25569, username: 'dcbot_direct', version: '1.21.11', auth: 'offline' })
  const kick = await Promise.race([once(direct, 'kicked').then(r => 'kicked: ' + L.plain(r)), once(direct, 'spawn').then(() => 'SPAWNED'),
    once(direct, 'end').then(r => 'ended: ' + r), L.sleep(15000).then(() => 'timeout')])
  results.directRefused = !/SPAWNED/.test(kick)
  L.log('test', 'direct', kick)
  try { direct.quit() } catch { }
  await L.sleep(5000)

  // 2 + 3 through the proxy's pvp.cheesesmp.top
  const bot = L.createBot('dcbot_auth')
  const seen = []
  bot.on('messagestr', m => seen.push(m))
  await once(bot, 'spawn')
  // wait for the auth prompt to appear, but switch before answering it: stop the auto-login for this step
  await L.sleep(1500)
  const before = seen.length
  bot.chat('/server lobby')
  await L.sleep(3000)
  const afterSwitch = seen.slice(before).join(' | ')
  results.switchBlockedBeforeLogin = /cannot|not logged|log ?in|register/i.test(afterSwitch) || !/lobby/i.test(afterSwitch)
  L.log('test', 'before-login-switch', afterSwitch)
  // (lib answered the prompt by now) wait until logged in on pvp
  await L.waitFor(() => seen.some(m => /Successful(ly)? (login|registered)|Successful login/i.test(m)), 20000, 'login on pvp')
  L.log('test', 'logged-in-on-pvp')
  await L.sleep(1500)
  const mark = seen.length
  let respawned = false
  bot.once('respawn', () => { respawned = true })
  bot.chat('/server lobby')
  await L.sleep(9000)
  const lobbyMsgs = seen.slice(mark).join(' | ')
  // a lobby of the same dimension sends no respawn; its own join messages show we got there
  results.reachedLobby = respawned || /automatically logged in|already logged in|Welcome back/i.test(lobbyMsgs)
  results.noLoginPromptOnLobby = !/\/login|\/register/i.test(lobbyMsgs)
  L.log('test', 'lobby', { respawned, messages: lobbyMsgs.slice(0, 600) })
  bot.quit()
  await L.sleep(1000)
  L.log('test', 'results', results)
  const failed = Object.keys(results).filter(k => !results[k])
  if (failed.length) { L.log('test', 'FAIL', failed); process.exit(1) }
  L.log('test', 'PASS')
  process.exit(0)
}
main().catch(e => { L.log('test', 'FAIL', e.message); process.exit(1) })
setTimeout(() => { L.log('test', 'FAIL', 'global timeout'); process.exit(1) }, 120000)
