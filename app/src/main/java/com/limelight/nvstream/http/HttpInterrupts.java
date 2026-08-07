package com.limelight.nvstream.http;

import java.io.IOException;
import java.io.InterruptedIOException;

/**
 * Translates thread interruption escaping an OkHttp call into something our callers can handle.
 *
 * <p>OkHttp 5 enables fast fallback — its Happy Eyeballs implementation, RFC 8305 — by default.
 * Connection setup runs in background workers and the calling thread parks on a blocking queue
 * inside {@code FastFallbackExchangeFinder} instead of blocking on the socket itself. Interrupting
 * a caller mid-connect therefore raises a bare {@link InterruptedException}, where OkHttp 4 raised
 * an {@link IOException} subclass from the socket.
 *
 * <p>That distinction is what makes this dangerous. OkHttp is Kotlin, which has no checked
 * exceptions, so the {@code InterruptedException} travels straight through the {@code throws
 * IOException} contract of {@code Call.execute()} and past every {@code catch (IOException)} in
 * this codebase. Nothing catches it, so it reaches the thread's uncaught handler and takes the
 * process down. {@link com.limelight.computers.ComputerManagerService} stops its polling threads
 * by interrupting them, which makes this a routine path rather than an edge case: leaving the host
 * grid while an unreachable host was still being polled killed the app every time.
 *
 * <p>Upstream knows (square/okhttp#9014). The 5.2.0 changelog entry "recover gracefully when
 * worker threads are interrupted" covers the background connect workers, not the caller parked in
 * {@code connectResults.poll()}, and 5.4.0 still has no catch around it — so bumping the
 * dependency does not fix this and the translation has to happen on our side.
 *
 * <p>Latency: none. This sits on the HTTP path used for host discovery, pairing and launch, all of
 * which are off the streaming path entirely; the translation only runs on the way out of a failed
 * call.
 */
final class HttpInterrupts {

    private HttpInterrupts() {
    }

    /**
     * @param t an exception that escaped an OkHttp call
     * @return the {@link InterruptedIOException} to throw in its place, or null if {@code t} is not
     *         an interrupt and should be allowed to propagate unchanged
     */
    static InterruptedIOException translate(Throwable t) {
        if (!(t instanceof InterruptedException)) {
            return null;
        }

        // Throwing InterruptedException clears the interrupt status. The polling loops test
        // isInterrupted() and rely on the next Thread.sleep() throwing to break out, so the flag
        // has to go back before we hand control to a caller that only knows how to handle IOException.
        Thread.currentThread().interrupt();

        InterruptedIOException translated = new InterruptedIOException("Interrupted while connecting");
        translated.initCause(t);
        return translated;
    }
}
