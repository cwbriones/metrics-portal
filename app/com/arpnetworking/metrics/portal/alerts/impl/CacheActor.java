package com.arpnetworking.metrics.portal.alerts.impl;

import java.time.Duration;
import java.util.Optional;
import java.util.concurrent.CompletionStage;

import akka.actor.AbstractActor;
import akka.actor.ActorRef;
import akka.actor.Props;
import akka.actor.Status;
import akka.cluster.ddata.DistributedData;
import akka.cluster.ddata.Key;
import akka.cluster.ddata.LWWMap;
import akka.cluster.ddata.LWWMapKey;
import akka.cluster.ddata.Replicator;
import akka.cluster.ddata.SelfUniqueAddress;
import akka.pattern.Patterns;
import com.arpnetworking.metrics.incubator.PeriodicMetrics;
import com.arpnetworking.steno.Logger;
import com.arpnetworking.steno.LoggerFactory;
import models.internal.scheduling.JobExecution;
import scala.Option;

public class CacheActor<K, V> extends AbstractActor {
    private static final Logger LOGGER = LoggerFactory.getLogger(CacheActor.class);
    private static final Replicator.ReadConsistency DEFAULT_READ_CONSISTENCY =
        new Replicator.ReadMajority(Duration.ofSeconds(5));

    private final ActorRef _replicator = DistributedData.get(getContext().getSystem()).replicator();
    private final PeriodicMetrics _metrics;
    private final String _cacheName;

    private final SelfUniqueAddress node =
        DistributedData.get(getContext().getSystem()).selfUniqueAddress();

    private CacheActor(final String cacheName, final PeriodicMetrics metrics) {
        _cacheName = cacheName;
        _metrics = metrics;
    }

    public static Props props(final String cacheName, final PeriodicMetrics metrics) {
        return Props.create(CacheActor.class, () -> new CacheActor<>(cacheName, metrics));
    }

    @SuppressWarnings("unchecked")
    public static <K, V> CompletionStage<Optional<V>> get(final ActorRef ref, final K key) {
        return Patterns.askWithReplyTo(
            ref,
            replyTo -> new CacheGet<>(key, replyTo),
            Duration.ofSeconds(5)
        ).thenApply(resp -> ((Optional<V>) resp));
    }

    /**
     * Put a Key-Value pair into the cache.
     * <br>
     * This will write a value into the local cache, letting replication happen
     * at some future time. If you want to block on replication, use {@link CacheActor#putAll}
     * instead.
     *
     * @param ref The cache actor ref.
     * @param key The key.
     * @param value The associated value.
     * @param <K> The type of key.
     * @param <V> The type of value.
     * @return A completion stage to await for the write to complete.
     */
    public static <K, V> CompletionStage<Void> put(final ActorRef ref, final K key, final V value) {
        return Patterns.askWithReplyTo(
            ref,
            replyTo -> new CachePut<>(key, value, replyTo, Replicator.writeLocal()),
            Duration.ofSeconds(5)
        ).thenApply(resp -> null);
    }

    /**
     * Put a Key-Value pair into the cache, including all replicas.
     * <br>
     * This will write a value into cache, blocking on replication. This means that
     * this method could timeout if all replicas do not respond in time.
     * <br>
     * This method should be used for situations where it's the value being cached
     * was just computed on this node, and is likely not available yet elsewhere
     * (e.g. a cluster-sharded operation computed this value).
     *
     * If you're caching a value read from a fallback, you should use put instead.
     *
     * @param ref The cache actor ref.
     * @param key The key.
     * @param value The associated value.
     * @param <K> The type of key.
     * @param <V> The type of value.
     * @return A completion stage to await for the write to complete.
     */
    public static <K, V> CompletionStage<Void> putAll(final ActorRef ref, final K key, final V value) {
        return Patterns.askWithReplyTo(
            ref,
            replyTo -> new CachePut<>(key, value, replyTo, new Replicator.WriteAll(Duration.ofSeconds(5))),
            Duration.ofSeconds(5)
        ).thenApply(resp -> null);
    }

    @Override
    @SuppressWarnings("unchecked")
    public Receive createReceive() {
        return receiveBuilder()
            .match(CacheGet.class, msg -> {
                final CacheGet<K> castMsg = (CacheGet<K>) msg;

                _metrics.recordCounter("cache/get", 1);
                final Key<LWWMap<K, V>> key = LWWMapKey.create(_cacheName);
                _replicator.tell(new Replicator.Get<>(key, DEFAULT_READ_CONSISTENCY, Optional.of(castMsg)), getSelf());
            })
            .match(Replicator.GetResponse.class, msg -> {
                final Optional<CacheGet<K>> request = (Optional<CacheGet<K>>) msg.getRequest();
                if (!request.isPresent()) {
                    throw new IllegalStateException("response from replicator is missing context");
                }
                final ActorRef self = getSelf();
                request.ifPresent(ctx -> {
                    if (msg instanceof Replicator.NotFound) {
                        _metrics.recordCounter("cache/get/hit", 0);
                        ctx.getReplyTo().tell(new Status.Success(Optional.empty()), self);
                    } else if (msg instanceof Replicator.GetSuccess) {
                        final LWWMap<K, V> map = (LWWMap<K, V>) ((Replicator.GetSuccess<?>) msg).dataValue();
                        final Option<V> scalaVal = map.get(ctx.getKey());
                        final Optional<V> val = scalaVal.isEmpty() ? Optional.empty() : Optional.of(scalaVal.get());
                        _metrics.recordCounter("cache/get/hit", 1);
                        ctx.getReplyTo().tell(new Status.Success(val), self);
                    } else if (msg instanceof Replicator.GetFailure) {
                        LOGGER.error()
                            .setMessage("Update failed to replicate within the allotted time.")
                            .addData("request", msg.getRequest())
                            .addData("response", msg)
                            .log();
                        _metrics.recordCounter("cache/get/hit", 0);
                        _metrics.recordCounter("cache/get/errors", 1);
                        ctx.getReplyTo().tell(new Status.Failure(new Throwable("Something went wrong")), self);
                    }
                });
            })
            .match(CachePut.class, msg -> {
                _metrics.recordCounter("cache/put", 1);
                final CachePut<K, V> putMsg = (CachePut<K, V>) msg;
                final Key<LWWMap<K, V>> key = LWWMapKey.create(_cacheName);

                _replicator.tell(
                    new Replicator.Update<>(
                        key,
                        LWWMap.create(),
                        putMsg.getWriteConsistency(),
                        Optional.of(msg),
                        curr -> takeLatestTimestamp(curr, putMsg.getKey(), putMsg.getValue())
                    ),
                    getSelf()
                );
            })
            .match(Replicator.UpdateResponse.class, msg -> {
                final Optional<CachePut<K, V>> request = (Optional<CachePut<K, V>>) msg.getRequest();
                final ActorRef self = getSelf();
                if (!request.isPresent()) {
                    throw new IllegalStateException("response from replicator is missing context");
                }
                request.ifPresent(req -> {
                    if (msg instanceof Replicator.UpdateTimeout) {
                        _metrics.recordCounter("cache/put/success", 0);
                        _metrics.recordCounter("cache/put/timeout", 1);
                        req.getReplyTo().tell(new Status.Failure(new Throwable("Something went wrong")), self);
                    } else if (msg instanceof Replicator.UpdateFailure) {
                        _metrics.recordCounter("cache/put/success", 0);
                        req.getReplyTo().tell(new Status.Success(null), self);
                    } else if (msg instanceof Replicator.UpdateSuccess) {
                        _metrics.recordCounter("cache/put/success", 1);
                        req.getReplyTo().tell(new Status.Success(null), self);
                    }
                });
            })
            .build();
    }

    private LWWMap<K, V> takeLatestTimestamp(final LWWMap<K, V> current, final K key, final V value) {
        return current.put(node, key, value);
    }

    private <T> LWWMap<K, JobExecution.Success<T>> takeLatestTimestamp(
        final LWWMap<K, JobExecution.Success<T>> current,
        final K key,
        final JobExecution.Success<T> value
    ) {
        Option<JobExecution.Success<T>> currentValue = current.get(key);
        if (currentValue.isEmpty() || currentValue.get().getCompletedAt().compareTo(value.getCompletedAt()) < 0) {
            return current.put(node, key, value);
        }
        return current;
    }

    public static final class CacheGet<K> {
        private final K _key;
        private final ActorRef _replyTo;

        public CacheGet(final K key, final ActorRef replyTo) {
            _key = key;
            _replyTo = replyTo;
        }

        public K getKey() {
            return _key;
        }

        public ActorRef getReplyTo() {
            return _replyTo;
        }
    }

    public static final class CachePut<K, V> {
        private final K _key;
        private final V _value;
        private final ActorRef _replyTo;
        private final Replicator.WriteConsistency _writeConsistency;

        public CachePut(final K key, final V value, final ActorRef replyTo, final Replicator.WriteConsistency writeConsistency) {
            _key = key;
            _value = value;
            _replyTo = replyTo;
            _writeConsistency = writeConsistency;
        }

        public K getKey() {
            return _key;
        }

        public V getValue() {
            return _value;
        }

        public ActorRef getReplyTo() {
            return _replyTo;
        }

        public Replicator.WriteConsistency getWriteConsistency() {
            return _writeConsistency;
        }
    }
}
