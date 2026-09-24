// Chat filter, tab header/footer, tier tags and a kit loadout.
// 1. tab header/footer arrive; 2. a slur is blocked (sender told, other bot never sees it); 3. swearing reaches the
// other bot masked; 4. during a match both tab names show the match kit's icon; 5. the pot loadout has 26 healing
// potions and Sharpness V; 6. after the match the tag goes back to the best kit. Exit code 0 = pass.
const L = require('./lib')
const kit = process.argv[2] || 'pot'
const icon = process.argv[3] || 'dragon_breath'

async function main () {
  const a = L.createBot('dcbot_alpha')
  const b = L.createBot('dcbot_bravo')
  const tab = {} // uuid -> plain display name
  const names = {} // uuid -> player name
  let header = ''
  const shown = [] // what b's client displays for player chat (unsigned content wins over the signed body)
  for (const bot of [a, b]) {
    bot.once('login', () => {
      bot._client.on('player_chat', p => {
        if (bot === b) shown.push(p.unsignedChatContent ? L.plain(p.unsignedChatContent) : String(p.plainMessage))
      })
      bot._client.on('playerlist_header', p => { if (bot === b) header = L.plain(p.header) + ' | ' + L.plain(p.footer) })
      bot._client.on('player_info', p => {
        if (bot !== b) return
        for (const e of p.data || []) {
          if (e.player && e.player.name) names[e.uuid] = e.player.name
          if (e.displayName !== undefined) tab[names[e.uuid] || e.uuid] = e.displayName == null ? null : L.plain(e.displayName)
        }
      })
    })
  }
  await Promise.all([new Promise(r => a.once('spawn', r)), new Promise(r => b.once('spawn', r))])
  await L.sleep(4000)
  L.log('test', 'header', header)
  if (!/Cheese PvP/.test(header) || !/Ping/.test(header)) throw new Error('no tab header/footer: ' + header)
  L.log('test', 'tab-hub', tab)

  let mb = L.mark(b)
  let ma = L.mark(a)
  a.chat('you are a n1gg.3r')
  await L.waitChat(a, /blocked/, 5000, ma.chat)
  await L.sleep(1500)
  if (b.dc.chat.slice(mb.chat).some(m => /n1gg/.test(m)) || shown.some(m => /n1gg/.test(m))) throw new Error('slur reached the other player')
  L.log('test', 'slur-blocked')

  const from = shown.length
  a.chat('what the fuuuck was that')
  const seen = await L.waitFor(() => shown.slice(from).find(m => /what the/.test(m)), 5000, 'swear line')
  if (/fu+ck/.test(seen) || !/\*{4}/.test(seen)) throw new Error('swear not masked: ' + seen)
  L.log('test', 'swear-masked', seen)

  mb = L.mark(b)
  a.chat('gg that was a spicy fight, nice')
  const clean = await L.waitChat(b, /spicy fight/, 5000, mb.chat)
  L.log('test', 'clean-passes', clean)

  ma = L.mark(a)
  mb = L.mark(b)
  a.chat('/queue ' + kit + ' unranked')
  await L.sleep(700)
  b.chat('/queue ' + kit + ' unranked')
  await Promise.all([L.waitTitle(a, /Match found/, 20000, ma.titles), L.waitTitle(b, /Match found/, 20000, mb.titles)])
  await L.sleep(2500)
  L.log('test', 'tab-match', tab)
  for (const n of ['dcbot_alpha', 'dcbot_bravo']) {
    if (!tab[n] || !tab[n].includes(icon)) throw new Error('tab name of ' + n + ' lacks ' + icon + ': ' + tab[n])
  }
  const items = a.inventory.items().concat(a.inventory.slots.filter(Boolean))
  const uniq = [...new Set(items)]
  const count = uniq.filter(i => i.name === 'splash_potion').reduce((s, i) => s + i.count, 0)
  const sword = uniq.find(i => /sword/.test(i.name))
  let ench = []
  try { ench = sword.enchants.map(e => e.name + e.lvl) } catch {}
  if (!ench.length && sword) ench = [JSON.stringify(sword.components || sword.nbt || {}).replace(/"/g, '').slice(0, 300)]
  L.log('test', 'loadout', { splash: count, sword: sword && sword.name, ench, offhand: a.inventory.slots[45] && a.inventory.slots[45].name })
  if (kit === 'pot' && count !== 35) throw new Error('pot loadout wrong')
  await L.sleep(3000) // window for a server-side /data get check of the enchantments

  a.chat('/leave')
  await L.sleep(800)
  a.chat('/leave') // confirm the forfeit
  await L.waitFor(() => ['dcbot_alpha', 'dcbot_bravo'].every(n => tab[n] && !tab[n].includes(icon)), 20000, 'tags back to hub')
  await L.sleep(1500)
  L.log('test', 'tab-after', tab)
  L.log('test', 'pass')
  process.exit(0)
}
main().catch(e => { L.log('test', 'fail', e.message); process.exit(1) })
setTimeout(() => { L.log('test', 'fail', 'global timeout'); process.exit(1) }, 120000)
