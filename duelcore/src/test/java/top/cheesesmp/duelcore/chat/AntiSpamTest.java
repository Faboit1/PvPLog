package top.cheesesmp.duelcore.chat;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import top.cheesesmp.duelcore.chat.AntiSpam.Reason;
import top.cheesesmp.duelcore.chat.AntiSpam.Result;
import top.cheesesmp.duelcore.chat.AntiSpam.Settings;

class AntiSpamTest {

    private static final Settings DEFAULTS = Settings.defaults();
    private static final List<String> WHITELIST = DEFAULTS.whitelist();

    private final AtomicLong clock = new AtomicLong(1_000_000);
    private AntiSpam spam;
    private final UUID alice = UUID.randomUUID();
    private final UUID bob = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        spam = new AntiSpam(() -> DEFAULTS, clock::get);
    }

    private void tick(long ms) {
        clock.addAndGet(ms);
    }

    private Result say(UUID who, String text) {
        return spam.chat(who, text, "chat");
    }

    /** Says it after a comfortable pause (so only the check under test can block it). */
    private Result sayLater(UUID who, String text) {
        tick(2000);
        return say(who, text);
    }

    private static String ad(String text) {
        return AntiSpam.advert(text, WHITELIST);
    }

    // ---------------------------------------------------------------------------------------------------------
    // config

    @Test
    void bundledConfigMatchesTheBuiltInDefaults() {
        YamlConfiguration yml = YamlConfiguration.loadConfiguration(new InputStreamReader(
            AntiSpamTest.class.getResourceAsStream("/config.yml"), StandardCharsets.UTF_8));
        assertNotNull(yml.getConfigurationSection("chat.anti-spam"));
        assertEquals(DEFAULTS, Settings.from(yml.getConfigurationSection("chat.anti-spam")));
    }

    @Test
    void durations() {
        assertEquals(30_000, AntiSpam.parseDuration("30s"));
        assertEquals(120_000, AntiSpam.parseDuration("2m"));
        assertEquals(3_600_000, AntiSpam.parseDuration("1h"));
        assertEquals(45_000, AntiSpam.parseDuration("45"));
        assertEquals(0, AntiSpam.parseDuration("soon"));
        assertEquals(0, AntiSpam.parseDuration("-5m"));
        assertEquals("30s", AntiSpam.formatDuration(30_000));
        assertEquals("2m", AntiSpam.formatDuration(120_000));
        assertEquals("1m 5s", AntiSpam.formatDuration(64_200));
        assertEquals("1s", AntiSpam.formatDuration(1));
    }

    // ---------------------------------------------------------------------------------------------------------
    // normal chat

    @Test
    void normalFastPvpChatPasses() {
        // after a fight: several short lines within a few seconds, typed like a human (~1 s apart)
        String[] lines = {"gg", "wp", "1v1?", "rematch", "lol"};
        for (int round = 0; round < 3; round++) {
            for (String line : lines) {
                tick(1300);
                Result r = say(alice, line);
                assertTrue(r.allowed(), round + ": " + line + " -> " + r.reason());
            }
            tick(5000);
        }
    }

    @Test
    void conversationPasses() {
        String[] lines = {"hey", "anyone want to duel?", "ok", "sword or axe", "axe pls", "gg", "ez", "gg wp",
            "that was close", "lol", "one more?", "sure", "ok", "gg", "Nice fight", "what kit next", "nodebuff",
            "Where is the arena", "k", "ty", "?", "np", "LOL", "GG WP EZ", "??", ":)", "brb", "back", "gg"};
        for (String line : lines) {
            tick(1500);
            Result r = say(alice, line);
            assertTrue(r.allowed(), line + " -> " + r.reason());
        }
    }

    // ---------------------------------------------------------------------------------------------------------
    // rate limit

    @Test
    void minimumDelayBetweenMessages() {
        assertTrue(say(alice, "hello there").allowed());
        tick(300);
        Result r = say(alice, "how are you");
        assertFalse(r.allowed());
        assertEquals(Reason.TOO_FAST, r.reason());
        tick(400);
        assertTrue(say(alice, "how are you").allowed());
    }

    @Test
    void slidingWindowAllowsFourPerFiveSeconds() {
        String[] lines = {"one thing", "another thing", "third thing", "fourth thing", "fifth thing"};
        for (int i = 0; i < 4; i++) {
            assertTrue(say(alice, lines[i]).allowed(), lines[i]);
            tick(700);
        }
        Result r = say(alice, lines[4]);
        assertEquals(Reason.RATE, r.reason());
        // the first message leaves the window 5 s after it was sent
        tick(5000 - 4 * 700 + 1);
        assertTrue(say(alice, lines[4]).allowed());
    }

    @Test
    void blockedAttemptsDontUseUpTheWindow() {
        String[] lines = {"where are you", "come to spawn", "i will wait here", "hurry up please"};
        for (int i = 0; i < 4; i++) {
            assertTrue(say(alice, lines[i]).allowed(), lines[i]);
            tick(700);
        }
        for (int i = 0; i < 3; i++) {
            assertFalse(say(alice, "spam " + i).allowed());
            tick(100);
        }
        tick(2500);
        assertTrue(say(alice, "finally something new").allowed());
    }

    @Test
    void rateLimitIsSharedAcrossChannels() {
        assertTrue(spam.chat(alice, "hello", "chat").allowed());
        tick(100);
        assertEquals(Reason.TOO_FAST, spam.chat(alice, "psst", "pm:bob").reason());
    }

    // ---------------------------------------------------------------------------------------------------------
    // duplicates

    @Test
    void exactDuplicateIsBlocked() {
        assertTrue(sayLater(alice, "anyone want to duel me").allowed());
        Result r = sayLater(alice, "anyone want to duel me");
        assertEquals(Reason.DUPLICATE, r.reason());
    }

    @Test
    void duplicateEvasionsAreCaught() {
        String base = "join my team for free stuff";
        String[] evasions = {"JOIN MY TEAM FOR FREE STUFF", "join  my  team  for  free  stuff!!!", "j0in my t3am f0r fr33 stuff",
            "join my teeeeam for free stuuuuff", "jоin my tеam for frее stuff" /* Cyrillic о, е */, "join​my​team for free stuff",
            "j.o.i.n my team for free stuff", "joïn my téam för free stüff", "ｊｏｉｎ ｍｙ ｔｅａｍ ｆｏｒ ｆｒｅｅ ｓｔｕｆｆ",
            "j o i n m y t e a m f o r f r e e s t u f f", "free stuff join my team for"};
        for (String e : evasions) {
            AntiSpam fresh = new AntiSpam(() -> DEFAULTS, clock::get);
            tick(2000);
            assertTrue(fresh.chat(alice, base, "chat").allowed());
            tick(2000);
            Result r = fresh.chat(alice, e, "chat");
            assertFalse(r.allowed(), e);
            assertTrue(r.reason() == Reason.DUPLICATE || r.reason() == Reason.SIMILAR, e + " -> " + r.reason());
        }
    }

    @Test
    void nearDuplicatesAllowOneCorrectionThenBlock() {
        assertTrue(sayLater(alice, "wher is the nodebuff arena").allowed());
        assertTrue(sayLater(alice, "where is the nodebuff arena").allowed(), "a typo fix is fine");
        assertEquals(Reason.SIMILAR, sayLater(alice, "where's the nodebuff arena").reason());
    }

    @Test
    void suffixCounterEvasionIsCaught() {
        assertTrue(sayLater(alice, "buy ranks at my shop now 1").allowed());
        assertTrue(sayLater(alice, "buy ranks at my shop now 2").allowed());
        Result r = sayLater(alice, "buy ranks at my shop now 3");
        assertEquals(Reason.SIMILAR, r.reason());
        r = sayLater(alice, "buy ranks at my shop now!!");
        assertEquals(Reason.SIMILAR, r.reason());
    }

    @Test
    void duplicatesExpireAfterTheWindow() {
        assertTrue(sayLater(alice, "looking for a party").allowed());
        tick(31_000);
        assertTrue(say(alice, "looking for a party").allowed());
    }

    @Test
    void differentMessagesArentDuplicates() {
        assertTrue(sayLater(alice, "who wants to fight").allowed());
        assertTrue(sayLater(alice, "who wants to spectate").allowed());
        assertTrue(sayLater(alice, "who is on the leaderboard").allowed());
        assertTrue(sayLater(alice, "what is the best kit").allowed());
    }

    @Test
    void shortCommonPhrasesAreLenient() {
        for (String phrase : new String[] {"gg", "GG", "gg wp", "ggwp", "gf", "ez", "lol", "ty", "np", "rematch", "1v1",
            "1v1?", "ok", "k"}) {
            AntiSpam fresh = new AntiSpam(() -> DEFAULTS, clock::get);
            tick(1000);
            assertTrue(fresh.chat(alice, phrase, "chat").allowed(), phrase);
            tick(1000);
            assertEquals(Reason.DUPLICATE, fresh.chat(alice, phrase, "chat").reason(), phrase + " twice in a second");
            tick(3000);
            assertTrue(fresh.chat(alice, phrase, "chat").allowed(), phrase + " again after a few seconds");
        }
        // "gggg" and "gg" are the same phrase
        tick(5000);
        assertTrue(say(alice, "gggg").allowed());
        tick(1000);
        assertEquals(Reason.DUPLICATE, say(alice, "gg").reason());
    }

    @Test
    void lenientPhrasesCantBeRepeatedForever() {
        for (int i = 0; i < 4; i++) {
            assertTrue(say(alice, "gg").allowed(), "gg #" + (i + 1));
            tick(3500);
        }
        assertEquals(Reason.DUPLICATE, say(alice, "gg").reason(), "5th gg in 30 s");
        tick(30_000);
        assertTrue(say(alice, "gg").allowed());
    }

    @Test
    void lenientPhrasesAreStillRateLimited() {
        String[] phrases = {"gg", "ez", "lol", "gf", "wp", "np"};
        int rate = 0;
        for (int i = 0; i < 12; i++) {
            Result r = say(alice, phrases[i % phrases.length]);
            if (r.reason() == Reason.RATE) rate++;
            else assertTrue(r.allowed(), i + " " + r.reason());
            tick(700);
        }
        assertTrue(rate >= 4, "rate-limited " + rate);
    }

    @Test
    void privateMessagesToDifferentPlayersHaveTheirOwnHistory() {
        tick(2000);
        assertTrue(spam.chat(alice, "want to 1v1 me in sumo", "pm:bob").allowed());
        tick(2000);
        assertTrue(spam.chat(alice, "want to 1v1 me in sumo", "pm:carol").allowed());
        tick(2000);
        assertEquals(Reason.DUPLICATE, spam.chat(alice, "want to 1v1 me in sumo", "pm:bob").reason());
    }

    // ---------------------------------------------------------------------------------------------------------
    // flood / clean-up

    @Test
    void capsAreLowered() {
        assertEquals("where is everyone going", sayLater(alice, "WHERE IS EVERYONE GOING").text());
        assertEquals("GG WP EZ", sayLater(alice, "GG WP EZ").text(), "short shouting is left alone");
        assertEquals("I love the NoDebuff kit", sayLater(alice, "I love the NoDebuff kit").text());
        assertEquals("this is sick omg", sayLater(alice, "THIS IS SICK omg").text());
        assertEquals("HELLO Everyone", sayLater(alice, "HELLO Everyone").text(), "70% or less caps stays");
    }

    @Test
    void repeatedCharactersAreCollapsed() {
        assertEquals("noooo", sayLater(alice, "nooooooooooooooooooo").text());
        assertEquals("what!!!!", sayLater(alice, "what!!!!!!!!!!!!!!!!!!!!").text());
        assertEquals("hahahaha", sayLater(alice, "hahahahahahahahahahahaha").text());
        assertEquals("i have 1000000 coins", sayLater(alice, "i have 1000000 coins").text(), "numbers stay");
        assertEquals("good", sayLater(alice, "good").text());
        assertEquals("lololol", AntiSpam.collapseRepeats("lololol", 4));
        assertEquals("😂😂😂😂", AntiSpam.collapseRepeats("😂😂😂😂😂😂😂😂😂", 4));
    }

    @Test
    void zalgoAndInvisibleCharactersAreStripped() {
        String zalgo = "h̸̡̢̛̖̗̘̙̜̝̞̟̠̤̥̦̩̪̫̬̭̮̯̰̱̲̳̹̺̻̼ͅi̵̢̡̛̛̖̗̘̙̜̝̞̟̠̤̥̦̩̪̫̬̭̮̯̰̱̲̳̹̺̻̼ͅ there";
        String cleaned = sayLater(alice, zalgo).text();
        assertTrue(cleaned.length() <= "hi there".length() + 4, cleaned);
        assertTrue(cleaned.startsWith("h") && cleaned.endsWith(" there"), cleaned);
        assertEquals("hello world", sayLater(alice, "he​ll‌o‍ w⁠o﻿rld").text());
        assertEquals("admin says hi", sayLater(alice, "‮admin says hi‬").text(), "bidi overrides");
        assertEquals("fake rank", sayLater(alice, " fake rank").text(), "private-use glyphs");
        assertEquals("spaced out", sayLater(alice, "spacedㅤㅤㅤout").text(), "hangul filler");
        assertEquals("café résumé", sayLater(alice, "café résumé").text(), "real accents stay");
        assertEquals("na\u00efve", sayLater(alice, "nai\u0308ve").text(), "one combining mark stays");
    }

    @Test
    void invisibleOnlyMessagesAreBlocked() {
        Result r = sayLater(alice, "​‌‍⁠ㅤ⠀");
        assertEquals(Reason.EMPTY, r.reason());
    }

    @Test
    void symbolJunkIsBlocked() {
        assertEquals(Reason.JUNK, sayLater(alice, "#$%^&*()_+{}|:<>?~`-=[]\\;',./").reason());
        assertEquals(Reason.JUNK, sayLater(alice, "▓▒░▓▒░█▓▒░▓▒░█▓▒░").reason());
        assertTrue(sayLater(alice, "??").allowed());
        assertTrue(sayLater(alice, ":) :D <3 xD").allowed());
        assertTrue(sayLater(alice, "o/").allowed());
        assertTrue(sayLater(alice, "!!! what a clutch !!!").allowed());
    }

    // ---------------------------------------------------------------------------------------------------------
    // advertising

    @Test
    void plainAdsAreFound() {
        assertEquals("evilpvp.net", ad("join evilpvp.net now"));
        assertEquals("play.evil.gg", ad("come to play.evil.gg"));
        assertNotNull(ad("https://www.coolserver.com/vote"));
        assertEquals("123.45.67.89", ad("ip is 123.45.67.89"));
        assertNotNull(ad("evil.co.uk"));
        assertNotNull(ad("cheesesmp.top.evil.com"), "whitelisted prefix doesn't whitelist another domain");
        assertNotNull(ad("evilcheesesmp.top"));
    }

    @Test
    void obfuscatedAdsAreFound() {
        String[] ads = {"evilpvp dot net", "evilpvp (dot) net", "evilpvp [.] net", "evilpvp(.)net", "evilpvp{dot}net",
            "evilpvp . net", "evilpvp .net", "evilpvp. net", "evilpvpdotnet", "evilpvp d0t n3t", "evilpvp,net",
            "e v i l p v p . n e t", "evilpvp.n e t", "evilpvp. n e t", "evіlpvp.nеt" /* Cyrillic */, "evilpvp。net",
            "evilpvp．ｎｅｔ", "ｅｖｉｌｐｖｐ．ｎｅｔ", "e_v_i_l_p_v_p.net", "evil-pvp.net", "evil​pvp.n​et",
            "𝐞𝐯𝐢𝐥𝐩𝐯𝐩.𝐧𝐞𝐭", "evilpvp.ｃｏｍ", "evilpvp•net"};
        for (String s : ads) assertNotNull(ad(s), s);
    }

    @Test
    void obfuscatedIpsAreFound() {
        String[] ips = {"1 2 3 . 4 5 . 6 7 . 8 9", "123 . 45 . 67 . 89", "123 dot 45 dot 67 dot 89", "123(.)45(.)67(.)89",
            "l92.l68.O.l", "１２３．４５．６７．８９", "123[.]45[.]67[.]89", "join 12 3.4 5.6 7.8 9"};
        for (String s : ips) assertNotNull(ad(s), s);
        assertNull(ad("999.999.999.999"));
    }

    @Test
    void discordInvitesAreFound() {
        assertNotNull(ad("discord.gg/abc123"));
        assertNotNull(ad("discord gg/abc123"));
        assertNotNull(ad("discord . gg / abc123"));
        assertNotNull(ad("discord.com/invite/abc123"));
        assertNotNull(ad("dsc.gg/evil"));
        assertNotNull(ad("discord.gg/cheesesmp2"), "similar to the whitelisted invite");
        assertNotNull(ad("discord.gg/cheese5mp"), "leet version of the whitelisted invite");
    }

    @Test
    void whitelistedDomainsPass() {
        assertNull(ad("cheesesmp.top"));
        assertNull(ad("join pvp.cheesesmp.top with friends"));
        assertNull(ad("our discord: discord.gg/cheesesmp"));
        assertNull(ad("https://discord.gg/CheeseSMP"));
        assertNull(ad("cheesesmp dot top"));
        assertNull(ad("store.cheesesmp.top"), "subdomains of a whitelisted domain");
        assertNotNull(ad("ch33sesmp.top"), "only the real spelling is whitelisted");
    }

    @Test
    void normalChatIsNotAnAd() {
        String[] clean = {"gg", "nice.gg", "ez.gg", "gg.ez", "ok. me next", "i.e. the kit",
            "e.g. sumo", "1.8.9 pvp", "version 1.21.4", "1, 2, 3, 4 go", "3 2 1 go", "i got 12 kills. 5 deaths",
            "u.s. players", "node.js", "wait... com on", "polka dot dress", "the dot on the map", "anecdote",
            "12.5 hearts", "is it lag? no", "a.m.", "brb 5 min", "my ping is 45 ms", "1v1 me at 5.30",
            "you are so good. me? not so much", "that was ez. gg", "so. what now"};
        for (String s : clean) assertNull(ad(s), s);
    }

    @Test
    void adsAreBlockedWithDetail() {
        Result r = sayLater(alice, "come play at evilpvp dot net");
        assertEquals(Reason.ADVERTISING, r.reason());
        assertEquals("evilpvp.net", r.detail());
        assertTrue(r.tell());
        assertTrue(sayLater(bob, "come play at pvp.cheesesmp.top").allowed());
    }

    // ---------------------------------------------------------------------------------------------------------
    // waves

    @Test
    void crossPlayerWaveBlocksTheFourth() {
        UUID[] players = {UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID()};
        String msg = "this server is so bad lmao";
        for (int i = 0; i < 3; i++) {
            tick(500);
            assertTrue(say(players[i], msg).allowed(), "player " + i);
        }
        tick(500);
        assertEquals(Reason.WAVE, say(players[3], msg).reason());
        tick(500);
        assertEquals(Reason.WAVE, say(players[4], "This server is soooo bad LMAO!!").reason(), "near-duplicate joins the wave");
        tick(11_000);
        assertTrue(say(players[3], msg).allowed(), "wave is over");
    }

    @Test
    void wavesIgnoreCommonPhrases() {
        for (int i = 0; i < 10; i++) {
            tick(200);
            assertTrue(say(UUID.randomUUID(), "gg wp").allowed());
            assertTrue(say(UUID.randomUUID(), "gg").allowed());
            assertTrue(say(UUID.randomUUID(), "rematch?").allowed());
        }
    }

    // ---------------------------------------------------------------------------------------------------------
    // escalation

    @Test
    void heatDecays() {
        say(alice, "first message");
        for (int i = 0; i < 3; i++) {
            tick(50);
            say(alice, "spam spam " + i);
        }
        double heat = spam.status(alice).heat();
        assertEquals(3, heat, 0.02);
        tick(10_000);
        assertEquals(2, spam.status(alice).heat(), 0.02);
        tick(60_000);
        assertEquals(0, spam.status(alice).heat(), 0.001);
    }

    @Test
    void warningBeforeMute() {
        say(alice, "first message");
        boolean warned = false;
        for (int i = 0; i < 5; i++) {
            tick(50);
            Result r = say(alice, "spam " + i);
            assertFalse(r.allowed());
            assertEquals(0, r.mutedMillis(), "no mute yet at " + i);
            warned |= r.warn();
        }
        assertTrue(warned);
    }

    private long flood(UUID who) {
        for (int i = 0; i < 50; i++) {
            tick(50);
            Result r = say(who, "flood message " + i);
            if (r.mutedMillis() > 0) return r.mutedMillis();
        }
        return 0;
    }

    @Test
    void muteEscalates() {
        assertEquals(30_000, flood(alice));
        Result r = sayLater(alice, "am i muted");
        assertEquals(Reason.MUTED, r.reason());
        assertTrue(r.muteLeftMillis() > 25_000 && r.muteLeftMillis() <= 28_000, "" + r.muteLeftMillis());
        tick(30_000);
        assertTrue(sayLater(alice, "sorry").allowed());
        assertEquals(120_000, flood(alice));
        tick(121_000);
        assertEquals(600_000, flood(alice));
        tick(601_000);
        assertEquals(600_000, flood(alice), "stays at the longest");
        assertEquals(4, spam.status(alice).strikes());
    }

    @Test
    void strikesResetAfterGoodBehaviour() {
        assertEquals(30_000, flood(alice));
        tick(31 * 60_000);
        assertEquals(0, spam.status(alice).strikes());
        assertEquals(30_000, flood(alice));
    }

    @Test
    void mutedPlayersCantSendPrivateMessagesEither() {
        flood(alice);
        tick(2000);
        assertEquals(Reason.MUTED, spam.chat(alice, "hey", "pm:bob").reason());
    }

    @Test
    void muteSurvivesCleanupAndRejoin() {
        flood(alice);
        tick(20_000); // left and came back
        spam.cleanup();
        assertEquals(Reason.MUTED, say(alice, "am i still muted").reason());
        tick(11 * 60_000); // mute over, idle for longer than the cleanup waits: the strike still counts
        spam.cleanup();
        assertEquals(1, spam.status(alice).strikes());
        assertEquals(120_000, flood(alice));
    }

    @Test
    void mutedStateIsNotCleanedUp() {
        flood(alice);
        tick(1000);
        spam.cleanup();
        assertEquals(Reason.MUTED, sayLater(alice, "hi").reason());
    }

    @Test
    void idlePlayersAreForgotten() {
        for (int i = 0; i < 100; i++) spam.chat(UUID.randomUUID(), "hello " + i, "chat");
        assertEquals(100, spam.trackedPlayers());
        tick(11 * 60_000);
        spam.cleanup();
        assertEquals(0, spam.trackedPlayers());
    }

    @Test
    void unmuteClearsEverything() {
        flood(alice);
        assertTrue(spam.status(alice).muteLeftMillis() > 0);
        assertTrue(spam.unmute(alice));
        assertEquals(0, spam.status(alice).muteLeftMillis());
        assertEquals(0, spam.status(alice).strikes());
        assertTrue(sayLater(alice, "thanks").allowed());
        assertFalse(spam.unmute(bob));
    }

    @Test
    void noticesAreThrottled() {
        say(alice, "first");
        int told = 0;
        for (int i = 0; i < 5; i++) {
            tick(20);
            if (say(alice, "x" + i).tell()) told++;
        }
        assertEquals(1, told);
    }

    // ---------------------------------------------------------------------------------------------------------
    // commands

    @Test
    void commandFlood() {
        for (int i = 0; i < 8; i++) {
            tick(100);
            assertTrue(spam.command(alice).allowed());
        }
        tick(100);
        Result r = spam.command(alice);
        assertEquals(Reason.COMMANDS, r.reason());
        assertEquals(0, spam.status(alice).heat(), 0.001, "command floods don't add chat heat");
        tick(3000);
        assertTrue(spam.command(alice).allowed());
    }

    // ---------------------------------------------------------------------------------------------------------
    // toggles, safety

    @Test
    void togglesTurnChecksOff() {
        YamlConfiguration c = new YamlConfiguration();
        c.set("rate-limit.enabled", false);
        c.set("duplicates.enabled", false);
        c.set("advertising.enabled", false);
        Settings s = Settings.from(c);
        AntiSpam loose = new AntiSpam(() -> s, clock::get);
        for (int i = 0; i < 20; i++) assertTrue(loose.chat(alice, "evilpvp.net", "chat").allowed());
        YamlConfiguration off = new YamlConfiguration();
        off.set("enabled", false);
        Settings none = Settings.from(off);
        AntiSpam disabled = new AntiSpam(() -> none, clock::get);
        assertEquals("NOOOOOOOOOOOOOO", disabled.chat(alice, "NOOOOOOOOOOOOOO", "chat").text());
    }

    @Test
    void hugeAndHostileInputIsFast() {
        StringBuilder b = new StringBuilder();
        for (int i = 0; i < 20_000; i++) b.append(i % 3 == 0 ? "a." : i % 3 == 1 ? "1 " : "(dot)");
        String hostile = b.toString();
        String dots = "a.".repeat(10_000);
        String marks = "e" + "́".repeat(10_000);
        long start = System.nanoTime();
        for (int i = 0; i < 20; i++) {
            tick(2000);
            spam.chat(UUID.randomUUID(), hostile, "chat");
            spam.chat(UUID.randomUUID(), dots, "chat");
            spam.chat(UUID.randomUUID(), marks, "chat");
            AntiSpam.advert(hostile, WHITELIST);
        }
        assertTrue(System.nanoTime() - start < TimeUnit.SECONDS.toNanos(5), "took too long");
    }

    @Test
    void concurrentMessagesRespectTheRateLimit() throws Exception {
        // many threads chatting as one player at the same instant: only one gets through the minimum delay
        ExecutorService pool = Executors.newFixedThreadPool(8);
        CountDownLatch go = new CountDownLatch(1);
        AtomicInteger allowed = new AtomicInteger();
        List<java.util.concurrent.Future<?>> futures = new ArrayList<>();
        for (int i = 0; i < 64; i++) {
            int n = i;
            futures.add(pool.submit(() -> {
                go.await();
                if (spam.chat(alice, "message " + n + " " + UUID.randomUUID(), "chat").allowed()) allowed.incrementAndGet();
                return null;
            }));
        }
        go.countDown();
        for (var f : futures) f.get(10, TimeUnit.SECONDS);
        pool.shutdown();
        assertEquals(1, allowed.get());
    }
}
