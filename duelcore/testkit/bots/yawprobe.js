// Logs every rotation/position packet the bot gets and its view once a second, for N seconds (an operator runs
// /duelcore debug throw <bot> <distance> <style> meanwhile). Checks where a respawn animation leaves the view.
const L = require('./lib')
const name = process.argv[2] || 'dcbot_yaw'
const secs = parseFloat(process.argv[3] || '60')
// Minecraft yaw to face first (0 south, 90 west, 180 north, -90 east), so a view forced to 0 shows
const face = parseFloat(process.argv[4] || '90')
const bot = L.createBot(name)
const deg = r => +(r * 180 / Math.PI).toFixed(1)
// mineflayer: yaw 0 = north (-Z), counter-clockwise; Minecraft: 0 = south (+Z), clockwise
const mcYaw = y => { let d = 180 - y * 180 / Math.PI; d = ((d + 180) % 360 + 360) % 360 - 180; return +d.toFixed(1) }
bot._client.on('position', p => L.log('test', 'position', { x: p.x, y: p.y, z: p.z, yaw: p.yaw, pitch: p.pitch, flags: p.flags }))
bot._client.on('player_rotation', p => L.log('test', 'rotation', p))
bot.once('spawn', async () => {
  await L.sleep(2000)
  await bot.look((180 - face) * Math.PI / 180, 0, true)
  const t0 = Date.now()
  const t = setInterval(() => {
    L.log('test', 'view', { t: Date.now() - t0, yaw: mcYaw(bot.entity.yaw), pitch: -deg(bot.entity.pitch),
      veh: !!bot.vehicle, x: +bot.entity.position.x.toFixed(1), z: +bot.entity.position.z.toFixed(1) })
    if (Date.now() - t0 > secs * 1000) { clearInterval(t); L.log('test', 'PASS'); bot.quit(); setTimeout(() => process.exit(0), 300) }
  }, 1000)
})
