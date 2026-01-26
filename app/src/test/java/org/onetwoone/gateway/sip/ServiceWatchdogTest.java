package org.onetwoone.gateway.sip;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.onetwoone.gateway.config.GatewayConfig;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import org.robolectric.shadows.ShadowLooper;

import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.Assert.*;

/**
 * Unit tests for ServiceWatchdog.
 * Tests periodic callback execution and state management.
 */
@RunWith(RobolectricTestRunner.class)
@Config(manifest = Config.NONE, sdk = 28)
public class ServiceWatchdogTest {

    private AtomicInteger checkCount;
    private ServiceWatchdog watchdog;

    @Before
    public void setUp() {
        // Initialize GatewayConfig for WATCHDOG_INTERVAL_MS constant
        GatewayConfig.init(RuntimeEnvironment.getApplication());

        checkCount = new AtomicInteger(0);
        watchdog = new ServiceWatchdog(checkCount::incrementAndGet);
    }

    @Test
    public void testInitialState() {
        assertFalse("Watchdog should not be running initially", watchdog.isRunning());
    }

    @Test
    public void testStartStop() {
        watchdog.start();
        assertTrue("Watchdog should be running after start", watchdog.isRunning());

        watchdog.stop();
        assertFalse("Watchdog should not be running after stop", watchdog.isRunning());
    }

    @Test
    public void testDoubleStartIgnored() {
        watchdog.start();
        watchdog.start(); // Should be ignored

        assertTrue("Watchdog should still be running", watchdog.isRunning());
    }

    @Test
    public void testDoubleStopSafe() {
        watchdog.stop();
        watchdog.stop(); // Should not throw

        assertFalse("Watchdog should not be running", watchdog.isRunning());
    }

    @Test
    public void testCallbackExecution() {
        watchdog.start();

        // Fast-forward time to trigger watchdog
        ShadowLooper.runUiThreadTasksIncludingDelayedTasks();

        assertTrue("Callback should be called at least once", checkCount.get() >= 1);
    }

    @Test
    public void testStopCancelsCallback() {
        watchdog.start();
        watchdog.stop();

        int countAfterStop = checkCount.get();
        ShadowLooper.runUiThreadTasksIncludingDelayedTasks();

        assertEquals("No callbacks should occur after stop", countAfterStop, checkCount.get());
    }

    @Test
    public void testCheckNow() {
        // checkNow should work even when not running
        watchdog.checkNow();
        ShadowLooper.runUiThreadTasksIncludingDelayedTasks();

        assertEquals("checkNow should execute callback once", 1, checkCount.get());
    }

    @Test
    public void testCallbackException() {
        // Test that exceptions in callback don't crash watchdog
        ServiceWatchdog badWatchdog = new ServiceWatchdog(() -> {
            throw new RuntimeException("Test exception");
        });

        badWatchdog.start();
        ShadowLooper.runUiThreadTasksIncludingDelayedTasks();

        // Should not throw, watchdog continues running
        assertTrue("Watchdog should continue after callback exception", badWatchdog.isRunning());
    }

    @Test
    public void testNullCallback() {
        // Null callback should be handled gracefully
        ServiceWatchdog nullWatchdog = new ServiceWatchdog(null);

        nullWatchdog.start();
        ShadowLooper.runUiThreadTasksIncludingDelayedTasks();

        // Should not throw
        assertTrue("Watchdog with null callback should still run", nullWatchdog.isRunning());
    }
}
