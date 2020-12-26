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
import scala.Option;

public class CacheActor<K, V> extends AbstractActor {
    private static final Logger LOGGER = LoggerFactory.getLogger(CacheActor.class);

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

    public static <K, V> CompletionStage<Void> put(final ActorRef ref, final K key, final V value) {
        return Patterns.askWithReplyTo(
            ref,
            replyTo -> new CachePut<>(key, value, replyTo),
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
                final Replicator.ReadConsistency readMajority = new Replicator.ReadMajority(Duration.ofSeconds(5));
                _replicator.tell(new Replicator.Get<>(key, readMajority, Optional.of(castMsg)), getSelf());
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
                final CachePut<K, V> castMsg = (CachePut<K, V>) msg;
                final Key<LWWMap<K, V>> key = LWWMapKey.create(_cacheName);
                final Replicator.WriteConsistency consistency = Replicator.writeLocal();

                _replicator.tell(
                    new Replicator.Update<>(
                        key,
                        LWWMap.create(),
                        consistency,
                        Optional.of(msg),
                        curr -> curr.put(node, castMsg.getKey(), castMsg.getValue())
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

        public CachePut(final K key, final V value, final ActorRef replyTo) {
            _key = key;
            _value = value;
            _replyTo = replyTo;
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
    }
}
