package com.slopus.happy.home

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

class LiveBotFaceWearerTest {
    @Test fun paintsUploadsAndWearsPngWithPlaintextSize() = runTest {
        val calls = mutableListOf<String>()
        val wearer = LiveBotFaceWearer(
            paint = { calls += "paint:$it"; byteArrayOf(1, 2, 3) },
            upload = { id, data -> calls += "upload:$id:${data.size}"; "ref" },
            setAvatar = { id, ref, size, mime -> calls += "wear:$id:$ref:$size:$mime" }, isCurrent = { true })
        assertNull(wearer.wear("bot", "seed"))
        assertEquals(listOf("paint:seed", "upload:bot:3", "wear:bot:ref:3:image/png"), calls)
    }

    @Test fun eachFailureStopsAtItsOwnLeg() = runTest {
        for (failed in BotFaceLeg.entries) {
            val calls = mutableListOf<BotFaceLeg>()
            fun step(leg: BotFaceLeg) { calls += leg; if (failed == leg) error("no") }
            val wearer = LiveBotFaceWearer(
                paint = { step(BotFaceLeg.Painting); byteArrayOf(1) },
                upload = { _, _ -> step(BotFaceLeg.Uploading); "ref" },
                setAvatar = { _, _, _, _ -> step(BotFaceLeg.Wearing) }, isCurrent = { true })
            assertEquals(failed, wearer.wear("bot", "seed")?.leg)
            assertEquals(BotFaceLeg.entries.take(failed.ordinal + 1), calls)
        }
    }

    @Test fun ownerChangeAtEveryBoundaryCancelsInsteadOfCallingNextLeg() = runTest {
        for (boundary in 0..3) {
            var step = 0
            val wearer = LiveBotFaceWearer(
                paint = { step++; byteArrayOf(1) }, upload = { _, _ -> step++; "ref" },
                setAvatar = { _, _, _, _ -> step++ }, isCurrent = { step < boundary })
            try { wearer.wear("bot", "seed"); fail("Owner change must cancel") } catch (_: CancellationException) {}
            assertEquals(boundary, step)
        }
    }

    @Test fun cancellationNeverTurnsIntoPictureFailure() = runTest {
        val wearer = LiveBotFaceWearer(paint = { throw CancellationException("stop") },
            upload = { _, _ -> error("No upload") }, setAvatar = { _, _, _, _ -> error("No avatar") }, isCurrent = { true })
        try { wearer.wear("bot", "seed"); fail("Must cancel") } catch (_: CancellationException) {}
    }

    @Test fun retryUsesOnlyOriginalSessionAndSeedAndRefusesNewAccount() = runTest {
        var current = true
        val calls = mutableListOf<Pair<String, String>>()
        val retry = BotFaceRetry("original", "seed", BotFaceWearer { id, seed -> calls += id to seed; null }, { current })
        assertNull(retry.attempt())
        assertNull(retry.attempt())
        current = false
        try { retry.attempt(); fail("Must cancel") } catch (_: CancellationException) {}
        assertEquals(listOf("original" to "seed", "original" to "seed"), calls)
    }
}