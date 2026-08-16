package com.limelight.nvstream.http;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.EOFException;
import java.io.IOException;
import java.io.InterruptedIOException;
import java.net.SocketTimeoutException;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests for the OkHttp interrupt translation.
 *
 * <p>The behaviour under test is what stands between a routine "stop polling" interrupt and a dead
 * process: an untranslated {@link InterruptedException} escapes every {@code catch (IOException)}
 * in the HTTP callers and reaches the thread's uncaught handler. The restored interrupt flag
 * matters just as much as the translated type — the polling loops use it to decide to stop, so
 * dropping it would trade a crash for a thread that keeps polling a host nobody is watching.
 *
 * <p>Nothing here covers the log line that accompanies a translated interrupt, because it is no
 * longer emitted from this class — {@code NvHTTP} logs it at the one call site, inside the branch
 * that runs only for interrupts. "Stays quiet for ordinary IO failures" is therefore guaranteed by
 * the shape of that code rather than asserted here.
 */
class HttpInterruptsTest {

    /**
     * JUnit reuses the calling thread across tests, so a flag left set here would leak into
     * unrelated tests. Clear it on both sides of every case.
     */
    @BeforeEach
    @AfterEach
    void clearInterruptStatus() {
        Thread.interrupted();
    }

    @Test
    @DisplayName("translates InterruptedException into an IOException callers can catch")
    void translatesInterruptedException() {
        InterruptedException cause = new InterruptedException();

        InterruptedIOException translated = HttpInterrupts.translate(cause);

        assertNotNull(translated, "an interrupt must be translated, not passed through");
        assertTrue(translated instanceof IOException,
                "callers only catch IOException, so the translation must be one");
        assertSame(cause, translated.getCause(), "the original interrupt is kept as the cause");
    }

    @Test
    @DisplayName("restores the interrupt flag that InterruptedException cleared")
    void restoresInterruptFlag() {
        assertFalse(Thread.currentThread().isInterrupted(), "precondition: flag starts clear");

        HttpInterrupts.translate(new InterruptedException());

        assertTrue(Thread.currentThread().isInterrupted(),
                "polling loops check isInterrupted() to know they should stop");
    }

    @Test
    @DisplayName("passes IOException through untouched")
    void passesIoExceptionThrough() {
        assertNull(HttpInterrupts.translate(new IOException("connection reset")));
        assertNull(HttpInterrupts.translate(new EOFException()));
        assertNull(HttpInterrupts.translate(new SocketTimeoutException()));

        assertFalse(Thread.currentThread().isInterrupted(),
                "a plain IO failure must not be mistaken for an interrupt");
    }

    @Test
    @DisplayName("passes an already-translated InterruptedIOException through untouched")
    void passesInterruptedIoExceptionThrough() {
        // This one is a genuine IOException from OkHttp's own timeout handling, not a thread
        // interrupt, and must not have the interrupt flag set on its behalf.
        assertNull(HttpInterrupts.translate(new InterruptedIOException("timeout")));

        assertFalse(Thread.currentThread().isInterrupted());
    }

    @Test
    @DisplayName("passes unchecked exceptions through untouched")
    void passesUncheckedThrough() {
        assertNull(HttpInterrupts.translate(new IllegalStateException("closed")));
        assertNull(HttpInterrupts.translate(new NullPointerException()));

        assertFalse(Thread.currentThread().isInterrupted());
    }

}
