package org.onetwoone.gateway.sip;

import android.app.Application;

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
 * Unit tests for ReconnectionStrategy.
 * Tests exponential backoff behavior and state management.
 */
@RunWith(RobolectricTestRunner.class)
@Config(manifest = Config.NONE, sdk = 28)
public class ReconnectionStrategyTest {

    private AtomicInteger reconnectCount;
    private ReconnectionStrategy strategy;

    @Before
    public void setUp() {
        // Initialize GatewayConfig with Robolectric context
        Application app = RuntimeEnvironment.getApplication();
        GatewayConfig.init(app);

        reconnectCount = new AtomicInteger(0);
        strategy = new ReconnectionStrategy(reconnectCount::incrementAndGet);
    }

    @Test
    public void testInitialState() {
        assertTrue("Should start enabled", strategy.isEnabled());
        assertFalse("Should have no pending reconnect", strategy.isPending());
        assertEquals("Initial delay should be 5000ms", 5000, strategy.getCurrentDelay());
    }

    @Test
    public void testEnableDisable() {
        strategy.setEnabled(false);
        assertFalse("Should be disabled", strategy.isEnabled());

        strategy.setEnabled(true);
        assertTrue("Should be enabled", strategy.isEnabled());
    }

    @Test
    public void testScheduleReconnect() {
        strategy.scheduleReconnect();
        assertTrue("Should have pending reconnect", strategy.isPending());

        // Fast-forward time
        ShadowLooper.runUiThreadTasksIncludingDelayedTasks();

        assertEquals("Reconnect callback should be called once", 1, reconnectCount.get());
        assertFalse("Should no longer be pending", strategy.isPending());
    }

    @Test
    public void testExponentialBackoff() {
        assertEquals("Initial delay should be 5000", 5000, strategy.getCurrentDelay());

        strategy.scheduleReconnect();
        assertEquals("Delay should double after schedule", 10000, strategy.getCurrentDelay());

        strategy.scheduleReconnect(); // Won't schedule (pending)
        ShadowLooper.runUiThreadTasksIncludingDelayedTasks();

        strategy.scheduleReconnect();
        assertEquals("Delay should be 20000", 20000, strategy.getCurrentDelay());
    }

    @Test
    public void testMaxDelay() {
        // Schedule multiple times to reach max
        for (int i = 0; i < 10; i++) {
            strategy.scheduleReconnect();
            ShadowLooper.runUiThreadTasksIncludingDelayedTasks();
        }

        assertTrue("Delay should be capped at max", strategy.getCurrentDelay() <= 60000);
    }

    @Test
    public void testSuccessResetsDelay() {
        // Increase delay
        strategy.scheduleReconnect();
        ShadowLooper.runUiThreadTasksIncludingDelayedTasks();
        strategy.scheduleReconnect();
        ShadowLooper.runUiThreadTasksIncludingDelayedTasks();

        assertTrue("Delay should be increased", strategy.getCurrentDelay() > 5000);

        // Reset via success
        strategy.onSuccess();
        assertEquals("Success should reset delay to initial", 5000, strategy.getCurrentDelay());
        assertFalse("Should clear pending flag", strategy.isPending());
    }

    @Test
    public void testCancel() {
        strategy.scheduleReconnect();
        assertTrue("Should be pending", strategy.isPending());

        strategy.cancel();
        assertFalse("Cancel should clear pending", strategy.isPending());

        ShadowLooper.runUiThreadTasksIncludingDelayedTasks();
        assertEquals("Callback should not be called after cancel", 0, reconnectCount.get());
    }

    @Test
    public void testDisabledDoesNotSchedule() {
        strategy.setEnabled(false);
        strategy.scheduleReconnect();

        assertFalse("Should not be pending when disabled", strategy.isPending());

        ShadowLooper.runUiThreadTasksIncludingDelayedTasks();
        assertEquals("Callback should not be called when disabled", 0, reconnectCount.get());
    }

    @Test
    public void testResetDelay() {
        strategy.scheduleReconnect();
        ShadowLooper.runUiThreadTasksIncludingDelayedTasks();

        assertTrue("Delay should be increased", strategy.getCurrentDelay() > 5000);

        strategy.resetDelay();
        assertEquals("resetDelay should set to initial", 5000, strategy.getCurrentDelay());
    }

    @Test
    public void testDuplicateScheduleIgnored() {
        strategy.scheduleReconnect();
        int delayAfterFirst = strategy.getCurrentDelay();

        strategy.scheduleReconnect(); // Should be ignored
        assertEquals("Second schedule should not change delay", delayAfterFirst, strategy.getCurrentDelay());
    }
}
