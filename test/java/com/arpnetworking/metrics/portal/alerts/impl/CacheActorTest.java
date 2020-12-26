package com.arpnetworking.metrics.portal.alerts.impl;

import java.util.Optional;

import akka.actor.ActorRef;
import akka.actor.ActorSystem;
import akka.cluster.Cluster;
import akka.testkit.javadsl.TestKit;
import com.arpnetworking.metrics.incubator.PeriodicMetrics;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.typesafe.config.ConfigFactory;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.mockito.Mockito;

import static org.hamcrest.Matchers.is;
import static org.junit.Assert.assertThat;

public class CacheActorTest {
    private ActorRef _cache;
    private ActorSystem _actorSystem;
    private PeriodicMetrics _metrics;

    @Before
    public void setUp() {
        _metrics = Mockito.mock(PeriodicMetrics.class);
        _actorSystem = ActorSystem.create("TestCacheSystem", ConfigFactory.parseString(
         "akka {\n" +
            "  loglevel = \"DEBUG\"\n" +
            "  actor {\n" +
            "    provider = \"cluster\"\n" +
            "  }\n" +
            "  remote {\n" +
            "    netty.tcp {\n" +
            "      hostname = \"127.0.0.1\"\n" +
            "      port = 0\n" +
            "    }\n" +
            "  }\n" +
            "\n" +
            "  cluster {\n" +
            "    seed-nodes = [\n" +
            "      \"akka.tcp://TestCacheSystem@127.0.0.1:2020\",\n" +
             "   ]\n" +
            "    auto-down-unreachable-after = 10s\n" +
            "  }\n" +
            "}"
        ));
        final Cluster cluster = Cluster.get(_actorSystem);
        cluster.joinSeedNodes(ImmutableList.of(cluster.selfAddress()));
        _cache = _actorSystem.actorOf(CacheActor.props("testCacheName", _metrics));
    }

    @After
    public void tearDown() {
        TestKit.shutdownActorSystem(_actorSystem);
    }

    @Test
    public void testCache() throws Exception {
        final Optional<Integer> noValue = CacheActor.<String, Integer>get(_cache, "foo").toCompletableFuture().get();
        assertThat(noValue, is(Optional.empty()));

        CacheActor.put(_cache, "foo", 42).toCompletableFuture().get();
        final Optional<Integer> fooValue = CacheActor.<String, Integer>get(_cache, "foo").toCompletableFuture().get();
        assertThat(fooValue, is(Optional.of(42)));

        CacheActor.put(_cache, "bar", 123).toCompletableFuture().get();
        Optional<Integer> barValue = CacheActor.<String, Integer>get(_cache, "bar").toCompletableFuture().get();
        assertThat(barValue, is(Optional.of(123)));

        CacheActor.put(_cache, "bar", 456).toCompletableFuture().get();
        barValue = CacheActor.<String, Integer>get(_cache, "bar").toCompletableFuture().get();
        assertThat(barValue, is(Optional.of(456)));

        final Optional<Integer> missingValue = CacheActor.<String, Integer>get(_cache, "missing-key").toCompletableFuture().get();
        assertThat(missingValue, is(Optional.empty()));
    }
}