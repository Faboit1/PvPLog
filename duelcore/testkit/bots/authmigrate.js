// Tools for the proxy / login setup (see docs/TEST_REPORT.md). Credentials come from files on the server, never from here.
// AuthMe (SQLite) -> MySQL: a raw copy (table authme) and LimboAuth's AUTH table.
//   node authmigrate.js inspect | import
// DB settings from ../authmig.properties (host, port, user, password, database); the SQLite copy is ../authme-smp.db
const fs = require('fs')
const path = require('path')
const crypto = require('crypto')
const mode = process.argv[2] || 'inspect'
const dbFile = path.join(__dirname, '..', 'authme-smp.db')

const { DatabaseSync } = require('node:sqlite')
const lite = new DatabaseSync(dbFile, { readOnly: true })
const out = (k, v) => console.log(JSON.stringify({ [k]: v }))
const cols = lite.prepare("PRAGMA table_info('authme')").all().map(c => ({ name: c.name, type: c.type, notnull: c.notnull, pk: c.pk }))
out('columns', cols)
out('integrity', lite.prepare('PRAGMA integrity_check').get())
out('count', lite.prepare('SELECT COUNT(*) AS n FROM authme').get().n)
const sample = lite.prepare("SELECT username, realname, substr(password,1,5) AS pw, length(password) AS pwlen, regdate, lastlogin FROM authme ORDER BY id DESC LIMIT 3").all()
out('sample', sample)
out('hashPrefixes', lite.prepare("SELECT substr(password,1,4) AS p, COUNT(*) AS n FROM authme GROUP BY substr(password,1,4) ORDER BY n DESC LIMIT 8").all())
if (mode !== 'import') process.exit(0)

function offlineUuid (name) {
  const h = crypto.createHash('md5').update('OfflinePlayer:' + name, 'utf8').digest()
  h[6] = (h[6] & 0x0f) | 0x30
  h[8] = (h[8] & 0x3f) | 0x80
  const x = h.toString('hex')
  return `${x.slice(0, 8)}-${x.slice(8, 12)}-${x.slice(12, 16)}-${x.slice(16, 20)}-${x.slice(20)}`
}

;(async () => {
  const props = Object.fromEntries(fs.readFileSync(path.join(__dirname, '..', 'authmig.properties'), 'utf8')
    .split('\n').filter(l => l.includes('=')).map(l => [l.slice(0, l.indexOf('=')).trim(), l.slice(l.indexOf('=') + 1).trim()]))
  const mysql = require('mysql2/promise')
  const my = await mysql.createConnection({ host: props.host, port: +props.port, user: props.user, password: props.password, database: props.database })
  const typeOf = t => /INT|TIMESTAMP/i.test(t) ? 'BIGINT' : /REAL|FLOA|DOUB/i.test(t) ? 'DOUBLE' : 'TEXT'
  const names = cols.map(c => c.name)
  // raw copy, same columns plus SQLite's row number as the key (some old rows have no id); the SQLite file is untouched
  const [[stale]] = await my.query("SELECT COUNT(*) AS n FROM information_schema.tables WHERE table_schema = DATABASE() AND table_name = 'authme'")
  if (stale.n) {
    const [[n]] = await my.query('SELECT COUNT(*) AS n FROM `authme`')
    if (n.n === 0) await my.query('DROP TABLE `authme`') // the empty table of a failed first run
  }
  await my.query('CREATE TABLE IF NOT EXISTS `authme_smp` (`sqlite_rowid` BIGINT NOT NULL, ' + cols.map(c => '`' + c.name + '` ' + (c.name === 'id' ? 'BIGINT' : typeOf(c.type))).join(', ') +
    ', PRIMARY KEY (`sqlite_rowid`)) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4')
  // LimboAuth's table, exactly as its ORM creates it
  await my.query('CREATE TABLE IF NOT EXISTS `AUTH` (`NICKNAME` VARCHAR(255) NOT NULL, `LOWERCASENICKNAME` VARCHAR(255), `HASH` VARCHAR(255) NOT NULL, ' +
    '`IP` VARCHAR(255), `TOTPTOKEN` VARCHAR(255), `REGDATE` BIGINT, `UUID` VARCHAR(255), `PREMIUMUUID` VARCHAR(255), `LOGINIP` VARCHAR(255), ' +
    '`LOGINDATE` BIGINT, `ISSUEDTIME` BIGINT, PRIMARY KEY (`LOWERCASENICKNAME`)) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4')
  for (const idx of ['IP', 'PREMIUMUUID']) {
    try { await my.query('CREATE INDEX `AUTH_' + idx + '_idx` ON `AUTH` (`' + idx + '`)') } catch (e) { if (e.code !== 'ER_DUP_KEYNAME') throw e }
  }
  const rows = lite.prepare('SELECT rowid AS sqlite_rowid, * FROM authme').all()
  const now = Date.now()
  let raw = 0, auth = 0, skipped = 0
  for (let i = 0; i < rows.length; i += 500) {
    const chunk = rows.slice(i, i + 500)
    const rawNames = ['sqlite_rowid', ...names]
    await my.query('INSERT INTO `authme_smp` (' + rawNames.map(n => '`' + n + '`').join(',') + ') VALUES ? ON DUPLICATE KEY UPDATE ' +
      names.map(n => '`' + n + '`=VALUES(`' + n + '`)').join(','), [chunk.map(r => rawNames.map(n => r[n]))])
    raw += chunk.length
    const valid = chunk.filter(r => r.username && r.password && /^\.?[A-Za-z0-9_]{3,16}$/.test(r.realname || r.username))
    skipped += chunk.length - valid.length
    if (!valid.length) continue
    await my.query('INSERT INTO `AUTH` (`NICKNAME`,`LOWERCASENICKNAME`,`HASH`,`IP`,`TOTPTOKEN`,`REGDATE`,`UUID`,`PREMIUMUUID`,`LOGINIP`,`LOGINDATE`,`ISSUEDTIME`) VALUES ? ' +
      'ON DUPLICATE KEY UPDATE `NICKNAME`=VALUES(`NICKNAME`), `HASH`=VALUES(`HASH`), `TOTPTOKEN`=VALUES(`TOTPTOKEN`), `LOGINIP`=VALUES(`LOGINIP`), `LOGINDATE`=VALUES(`LOGINDATE`)',
    [valid.map(r => {
      const nick = r.realname && r.realname.toLowerCase() === String(r.username).toLowerCase() ? r.realname : r.username
      return [nick, nick.toLowerCase(), r.password, r.regip || r.ip || '', r.totp || null, Number(r.regdate) || now, offlineUuid(nick), null,
        r.ip || '', Number(r.lastlogin) || now, now]
    })])
    auth += valid.length
  }
  const [[c1]] = await my.query('SELECT COUNT(*) AS n FROM `authme_smp`')
  const [[c2]] = await my.query('SELECT COUNT(*) AS n FROM `AUTH`')
  out('imported', { raw, auth, skipped, mysqlAuthme: c1.n, mysqlAUTH: c2.n })
  await my.end()
})().catch(e => { out('error', e.message); process.exit(1) })
