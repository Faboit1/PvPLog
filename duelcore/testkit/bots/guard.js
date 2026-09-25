// Login guard check (offline test mode). Args: [testers|open] (default testers).
// testers: operator prepares /tester on ; /tester add guardok1 ; allow.txt has "guardlock1 1.2.3.4".
//   Expect: randomguy (unknown) refused, dcbot_guard allowed, guardok1 allowed (and locked to 127.0.0.1),
//   guardlock1 (locked to another IP) refused.
// open: operator prepares /tester off ; /op guardop1 (never joined) ; allow.txt has "guardlock1 1.2.3.4".
//   Expect: a new guest name allowed (locked to 127.0.0.1 as a guest), dcbot_guard allowed, guardlock1 refused,
//   guardop1 (operator, not IP-locked) refused. Afterwards /tester on: the guest is refused again.
const L = require('./lib')
function attempt (name) {
  return new Promise(resolve => {
    const bot = L.createBot(name)
    let done = false
    const finish = r => { if (!done) { done = true; try { bot.quit() } catch (e) {} ; resolve(r) } }
    bot.once('spawn', () => finish({ name, joined: true }))
    bot.once('kicked', reason => finish({ name, joined: false, reason: L.plain(reason) }))
    bot.once('end', r => finish({ name, joined: false, reason: String(r) }))
    setTimeout(() => finish({ name, joined: false, reason: 'timeout' }), 15000)
  })
}
async function main () {
  const mode = process.argv[2] || 'testers'
  const guest = 'guest' + Math.floor(Math.random() * 1e6)
  const expect = mode === 'open'
    ? { [guest]: true, dcbot_guard: true, guardlock1: false, guardop1: false }
    : { randomguy: false, dcbot_guard: true, guardok1: true, guardlock1: false }
  let ok = true
  for (const [name, want] of Object.entries(expect)) {
    const r = await attempt(name)
    L.log('test', 'attempt', r)
    if (r.joined !== want) { ok = false; L.log('test', 'WRONG', { name, want }) }
    await L.sleep(1500)
  }
  L.log('test', ok ? 'PASS' : 'FAIL', { mode })
  process.exit(ok ? 0 : 1)
}
main()
