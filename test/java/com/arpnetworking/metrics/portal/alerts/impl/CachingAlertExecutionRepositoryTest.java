package com.arpnetworking.metrics.portal.alerts.impl;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import akka.actor.ActorSystem;
import akka.cluster.Cluster;
import akka.testkit.javadsl.TestKit;
import com.arpnetworking.metrics.incubator.PeriodicMetrics;
import com.arpnetworking.metrics.portal.alerts.AlertExecutionRepository;
import com.arpnetworking.metrics.portal.scheduling.impl.MapJobExecutionRepository;
import com.google.common.collect.ImmutableList;
import com.typesafe.config.ConfigFactory;
import models.internal.Organization;
import models.internal.alerts.AlertEvaluationResult;
import models.internal.impl.DefaultAlertEvaluationResult;
import models.internal.impl.DefaultOrganization;
import models.internal.scheduling.JobExecution;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.mockito.Mockito;

import static org.hamcrest.Matchers.*;
import static org.junit.Assert.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;

public class CachingAlertExecutionRepositoryTest {

    private ActorSystem _actorSystem;
    private AlertExecutionRepository _repo;
    private Organization _organization;
    private TestAlertExecutionRepository _inner;

    @Before
    public void setUp() {
        PeriodicMetrics _metrics = Mockito.mock(PeriodicMetrics.class);
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
        _inner = Mockito.spy(new TestAlertExecutionRepository());
        _repo = new CachingAlertExecutionRepository(
            _inner,
            _metrics,
            _actorSystem
        );
        _repo.open();
        _organization = new DefaultOrganization.Builder()
            .setId(UUID.randomUUID())
            .build();
    }

    @After
    public void tearDown() {
        _repo.close();
        TestKit.shutdownActorSystem(_actorSystem);
    }

    @Test
    public void testGetLastSuccessWritesToCache() throws Exception {
        final UUID jobId = UUID.randomUUID();
        final Instant scheduled = Instant.now();
        final AlertEvaluationResult result = newResult();

        // Only call the inner repo
        _inner.jobStarted(jobId, _organization, scheduled);
        _inner.jobSucceeded(jobId, _organization, scheduled, result);

        // Outer should still read the execution
        final Optional<JobExecution.Success<AlertEvaluationResult>> outerResult =
            _repo.getLastSuccess(jobId, _organization)
                .toCompletableFuture()
                .get();

        Mockito.verify(_inner, times(1)).getLastSuccess(eq(jobId), eq(_organization));
        Mockito.reset(_inner);

        assertThat(outerResult.isPresent(), is(true));
        assertThat(outerResult.get().getJobId(), is(jobId));
        assertThat(outerResult.get().getScheduled(), is(scheduled));
        assertThat(outerResult.get().getResult(), is(result));

        final Optional<JobExecution.Success<AlertEvaluationResult>> cachedResult =
            _repo.getLastSuccess(jobId, _organization)
                .toCompletableFuture()
                .get();

        assertThat(cachedResult, is(equalTo(outerResult)));
        Mockito.verify(_inner, never()).getLastSuccess(eq(jobId), eq(_organization));
    }

    @Test
    public void testGetLastSuccessBatchUsesCache() throws Exception {
        final int numJobs = 10;
        final int numCachedJobs = 5;
        final ImmutableList.Builder<UUID> jobIdsBuilder = ImmutableList.builder();
        final ImmutableList.Builder<UUID> cachedIdsBuilder = ImmutableList.builder();
        final ImmutableList.Builder<UUID> notCachedIdsBuilder = ImmutableList.builder();
        for (int i = 0; i < numJobs; i++) {
            final UUID jobId = UUID.randomUUID();
            final Instant scheduled = Instant.now();
            final AlertEvaluationResult result = newResult();

            if (i < numCachedJobs) {
                _repo.jobStarted(jobId, _organization, scheduled);
                _repo.jobSucceeded(jobId, _organization, scheduled, result);
                cachedIdsBuilder.add(jobId);
            } else {
                // Only call the inner repo
                _inner.jobStarted(jobId, _organization, scheduled);
                _inner.jobSucceeded(jobId, _organization, scheduled, result);
                notCachedIdsBuilder.add(jobId);
            }
            jobIdsBuilder.add(jobId);
        }
        final List<UUID> jobIds = jobIdsBuilder.build();
        final List<UUID> cachedIds = cachedIdsBuilder.build();
        final List<UUID> notCachedIds = notCachedIdsBuilder.build();

        final Map<UUID, JobExecution.Success<AlertEvaluationResult>> outerResult =
            _repo.getLastSuccessBatch(jobIds, _organization, LocalDate.now())
                .toCompletableFuture()
                .get();
        assertThat(outerResult.keySet(), containsInAnyOrder(jobIds.toArray()));

        Mockito.verify(_inner, times(1).description("Should have read some results from cache"))
            .getLastSuccessBatch(eq(notCachedIds), eq(_organization), any());
        Mockito.reset(_inner);

        final Map<UUID, JobExecution.Success<AlertEvaluationResult>> cachedResults =
            _repo.getLastSuccessBatch(cachedIds, _organization, LocalDate.now())
                .toCompletableFuture()
                .get();
        assertThat(cachedResults.keySet(), containsInAnyOrder(cachedIds.toArray()));

        Mockito.verify(_inner, never().description("Should have read all results from cache"))
            .getLastSuccessBatch(any(), any(), any());
    }

    private AlertEvaluationResult newResult() {
        return new DefaultAlertEvaluationResult.Builder()
            .setFiringTags(ImmutableList.of())
            .setSeriesName("testSeries")
            .setQueryStartTime(Instant.now())
            .setQueryEndTime(Instant.now())
            .build();
    }

    private static class TestAlertExecutionRepository extends MapJobExecutionRepository<AlertEvaluationResult> implements AlertExecutionRepository {}
}