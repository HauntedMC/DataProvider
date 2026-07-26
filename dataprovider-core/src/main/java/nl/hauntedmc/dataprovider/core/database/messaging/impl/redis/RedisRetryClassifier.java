package nl.hauntedmc.dataprovider.core.database.messaging.impl.redis;

import redis.clients.jedis.exceptions.JedisAccessControlException;
import redis.clients.jedis.exceptions.JedisBusyException;
import redis.clients.jedis.exceptions.JedisDataException;
import redis.clients.jedis.exceptions.JedisRedirectionException;
import redis.clients.jedis.exceptions.JedisValidationException;

import java.util.Locale;

/** Classifies Redis failures which cannot be repaired by reconnecting. */
final class RedisRetryClassifier {

    private RedisRetryClassifier() { }

    static boolean isTerminal(Throwable failure) {
        for (Throwable current = failure; current != null; current = current.getCause()) {
            if (current instanceof JedisAccessControlException || current instanceof JedisValidationException) {
                return true;
            }
            if (current instanceof JedisDataException dataFailure && !isTransient(dataFailure)) {
                return true;
            }
        }
        return false;
    }

    private static boolean isTransient(JedisDataException failure) {
        if (failure instanceof JedisBusyException || failure instanceof JedisRedirectionException) {
            return true;
        }
        String message = failure.getMessage();
        if (message == null) {
            return false;
        }
        String normalized = message.trim().toUpperCase(Locale.ROOT);
        // Redis can legitimately return these while starting, failing over, or while a group is
        // deleted and recreated between polling cycles. Other RESP errors are deterministic
        // command, ACL, capability, or key-layout failures and must not be retried forever.
        return normalized.startsWith("LOADING")
                || normalized.startsWith("TRYAGAIN")
                || normalized.startsWith("CLUSTERDOWN")
                || normalized.startsWith("MASTERDOWN")
                || normalized.startsWith("NOGROUP")
                || normalized.startsWith("BUSYGROUP");
    }
}
