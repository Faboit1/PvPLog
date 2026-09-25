// Parties: create, invite + accept, the party menu (hotbar slot 1) for leader and member, party chat ("@", /pc and
// the Party Chat toggle) reaching only the members and passing the chat filter, a Party FFA with three bots, leader
// succession on leave, persistence across a rejoin, disband. Exit code 0 = pass.
//   node party.js [kit]
const L = require('./lib')
const kit = process.argv[2] || 'sword'

function spawned (bot) {
  return new Promise(r => bot.once('spawn', r))
}

function label (menu, text) {
  return L.buttons(menu).find(x => x.label.includes(text))
}

async function main () {
  const a = L.createBot('dcbot_pa') // leader
  let b = L.createBot('dcbot_pb')
  const c = L.createBot('dcbot_pc')
  const d = L.createBot('dcbot_pd') // never in the party
  await Promise.all([a, b, c, d].map(spawned))
  await L.sleep(4000)

  // parties persist: leave whatever an earlier run left behind
  for (const bot of [a, b, c, d]) bot.chat('/party leave')
  await L.sleep(1500)

  // 1. create, invite, accept (by command and by the inviter's name)
  let ma = L.mark(a)
  a.chat('/party create')
  await L.waitChat(a, /Party created/, 5000, ma.chat)
  let mb = L.mark(b)
  a.chat('/party invite dcbot_pb')
  await L.waitChat(b, /invited you to their party/, 5000, mb.chat)
  b.chat('/party accept')
  await L.waitChat(a, /dcbot_pb joined the party/, 5000, ma.chat)
  let mc = L.mark(c)
  a.chat('/party invite dcbot_pc')
  await L.waitChat(c, /invited you to their party/, 5000, mc.chat)
  c.chat('/party accept dcbot_pa')
  await L.waitChat(a, /dcbot_pc joined the party/, 5000, ma.chat)
  L.log('test', 'party-formed')

  // 2. the party menu: the leader has every button, a member gets Leave and disabled (no action) leader buttons
  const menu = await L.openFromHotbar(a, 1, /Party/)
  for (const want of ['Invite Player', 'Party Chat', 'Party FFA', 'Party Duel', 'Party vs Party', 'Privacy', 'Disband']) {
    if (!label(menu, want)) throw new Error('leader menu has no ' + want + ': ' + L.buttons(menu).map(x => x.label))
  }
  if (!label(menu, 'Party FFA').id) throw new Error('Party FFA should be usable with 3 online members')
  L.click(a, { id: 'duelcore:party/close' })
  const memberMenu = await L.openFromHotbar(b, 1, /Party/)
  if (!label(memberMenu, 'Leave') || label(memberMenu, 'Disband')) throw new Error('member menu: ' + L.buttons(memberMenu).map(x => x.label))
  if (label(memberMenu, 'Party FFA').id || label(memberMenu, 'Invite Player').id) throw new Error('member can use leader buttons')
  L.click(b, { id: 'duelcore:party/close' })
  L.log('test', 'menus-ok')

  // 3. party chat: members only, through the chat filter
  let md = L.mark(d)
  mb = L.mark(b)
  mc = L.mark(c)
  a.chat('@hello team')
  await L.waitChat(b, /\[Party\].*dcbot_pa.*hello team/, 5000, mb.chat)
  await L.waitChat(c, /\[Party\].*hello team/, 5000, mc.chat)
  await L.sleep(1000)
  if (d.dc.chat.slice(md.chat).some(m => /hello team/.test(m))) throw new Error('party chat reached a non-member')
  ma = L.mark(a)
  b.chat('/pc what the fuuuck')
  const masked = await L.waitChat(a, /\[Party\].*what the/, 5000, ma.chat)
  if (/fu+ck/.test(masked) || !/\*{4}/.test(masked)) throw new Error('party chat not masked: ' + masked)
  mb = L.mark(b)
  mc = L.mark(c)
  b.chat('@you are a n1gg.3r')
  await L.waitChat(b, /blocked/, 5000, mb.chat)
  await L.sleep(1000)
  if (c.dc.chat.slice(mc.chat).some(m => /n1gg/.test(m))) throw new Error('a blocked message reached the party')
  mc = L.mark(c)
  c.chat('/party chat')
  await L.waitChat(c, /Party chat on/, 5000, mc.chat)
  ma = L.mark(a)
  md = L.mark(d)
  c.chat('only for the party')
  await L.waitChat(a, /\[Party\].*dcbot_pc.*only for the party/, 5000, ma.chat)
  await L.sleep(1000)
  if (d.dc.chat.slice(md.chat).some(m => /only for the party/.test(m))) throw new Error('toggled party chat reached a non-member')
  c.chat('/party chat')
  L.log('test', 'party-chat-ok')

  // 4. Party FFA: one round, last one standing, the party is told the result and stays together
  ma = L.mark(a)
  mb = L.mark(b)
  mc = L.mark(c)
  a.chat('/party ffa ' + kit)
  await Promise.all([[a, ma], [b, mb], [c, mc]].map(([bot, m]) => L.waitTitle(bot, /Fight/i, 60000, m.titles)))
  L.log('test', 'ffa-started')
  const fighters = [a, b, c].map(bot => L.fighter(bot))
  const over = await L.waitChat(a, /Party FFA over|Party match over/, 240000, ma.chat)
  fighters.forEach(f => f.stop())
  L.log('test', 'ffa-over', over)
  await L.sleep(3000)
  ma = L.mark(a)
  a.chat('/party list')
  await L.waitChat(a, /Party · 3\/20/, 5000, ma.chat)

  // 5. the leader leaves: the longest-standing member (b joined before c) leads
  mb = L.mark(b)
  a.chat('/party leave')
  await L.waitChat(b, /dcbot_pb is now the party leader/, 5000, mb.chat)

  // 6. persistence: b logs out and back in and is still in (and leading) the party
  mc = L.mark(c)
  b.quit('party test')
  await L.waitChat(c, /dcbot_pb went offline/, 5000, mc.chat)
  await L.sleep(1500)
  b = L.createBot('dcbot_pb')
  await spawned(b)
  await L.waitChat(b, /You're in dcbot_pb's party/, 10000, 0)
  await L.waitChat(c, /dcbot_pb is online/, 5000, mc.chat)

  // 7. disband
  mc = L.mark(c)
  b.chat('/party disband')
  await L.waitChat(c, /disbanded the party/, 5000, mc.chat)
  L.log('test', 'PASS')
  for (const bot of [a, b, c, d]) bot.quit()
  await L.sleep(800)
  process.exit(0)
}
main().catch(e => { L.log('test', 'FAIL', e.message); process.exit(1) })
setTimeout(() => { L.log('test', 'FAIL', 'global timeout'); process.exit(2) }, 420000)
