package com.mandonothing.ops.comm.aware

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.Closeable
import java.util.concurrent.atomic.AtomicInteger

class TrackedResourceRegistryTest {

    private class FakeRegistrationDriver(
        private val failNextRequest: Boolean = false,
    ) : NetworkRegistrationDriver<String, Any> {
        val active = mutableSetOf<Any>()
        val registerCalls = AtomicInteger(0)
        val unregisterCalls = AtomicInteger(0)
        var shouldFail = failNextRequest

        override fun requestNetwork(request: String, callback: Any, timeoutMs: Long) {
            registerCalls.incrementAndGet()
            if (shouldFail) {
                throw IllegalStateException("requestNetwork failed")
            }
            active.add(callback)
        }

        override fun unregisterNetworkCallback(callback: Any) {
            unregisterCalls.incrementAndGet()
            active.remove(callback)
        }
    }

    private class TrackedCloseable : Closeable {
        val closes = AtomicInteger(0)
        override fun close() {
            closes.incrementAndGet()
        }
    }

    @Test
    fun networkCallbackRegistryIsIdempotentAndZeroLeakOnReplaceFailureAndClose() {
        val driver = FakeRegistrationDriver()
        val registry = TrackedNetworkCallbackRegistry<String, String, Any>(driver)

        val cb1 = Any()
        val cb2 = Any()
        val cb3 = Any()

        assertTrue(registry.register("peer-a", "req-a1", cb1, 12_000L))
        assertEquals(1, registry.activeCount())
        assertEquals(1, driver.active.size)

        // Replacing same peer key unregisters cb1 immediately.
        assertTrue(registry.register("peer-a", "req-a2", cb2, 12_000L))
        assertEquals(1, registry.activeCount())
        assertEquals(1, driver.active.size)
        assertTrue(cb2 in driver.active)
        assertFalse(cb1 in driver.active)

        // Idempotent duplicate unregister calls driver only once for cb2.
        assertTrue(registry.unregister("peer-a"))
        assertFalse(registry.unregister("peer-a"))
        assertEquals(0, registry.activeCount())
        assertEquals(0, driver.active.size)
        assertEquals(2, driver.unregisterCalls.get())

        // Failed requestNetwork immediately cleans up without leaking.
        driver.shouldFail = true
        assertFalse(registry.register("peer-b", "req-b", cb3, 12_000L))
        assertEquals(0, registry.activeCount())
        assertEquals(0, driver.active.size)

        // Permanent close unregisters everything and rejects new registrations.
        driver.shouldFail = false
        val cb4 = Any()
        assertTrue(registry.register("peer-c", "req-c", cb4, 12_000L))
        registry.unregisterAll(permanent = true)
        assertEquals(0, registry.activeCount())
        assertEquals(0, driver.active.size)
        assertFalse(registry.register("peer-d", "req-d", Any(), 12_000L))

        val (active, totals) = registry.snapshot()
        assertEquals(0, active)
        assertEquals(totals.first, totals.second)
    }

    @Test
    fun socketRegistryClosesDisplacedAndLateAcceptedSocketsImmediately() {
        val registry = TrackedSocketRegistry<String>()
        val s1 = TrackedCloseable()
        val s2 = TrackedCloseable()
        val unkeyed = TrackedCloseable()

        assertTrue(registry.register("peer-1", s1))
        assertTrue(registry.replace("peer-1", s2))
        assertEquals(1, s1.closes.get())
        assertEquals(0, s2.closes.get())

        assertTrue(registry.registerUnkeyed(unkeyed))
        assertEquals(2, registry.activeCount())

        registry.closeAll(permanent = true)
        assertEquals(1, s2.closes.get())
        assertEquals(1, unkeyed.closes.get())
        assertEquals(0, registry.activeCount())

        // Late background socket arriving after closeAll is closed synchronously and rejected.
        val lateSocket = TrackedCloseable()
        assertFalse(registry.register("peer-2", lateSocket))
        assertEquals(1, lateSocket.closes.get())
        assertEquals(0, registry.activeCount())
    }
}
