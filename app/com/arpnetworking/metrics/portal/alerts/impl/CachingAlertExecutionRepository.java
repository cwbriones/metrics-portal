package com.arpnetworking.metrics.portal.alerts.impl;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ExecutionException;
import java.util.function.Function;
import java.util.stream.Stream;

import akka.actor.ActorRef;
import akka.actor.ActorSystem;
import com.arpnetworking.commons.java.util.concurrent.CompletableFutures;
import com.arpnetworking.metrics.incubator.PeriodicMetrics;
import com.arpnetworking.metrics.portal.alerts.AlertExecutionRepository;
import com.google.common.base.Predicates;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.Streams;
import models.internal.Organization;
import models.internal.alerts.AlertEvaluationResult;
import models.internal.scheduling.JobExecution;

public class CachingAlertExecutionRepository implements AlertExecutionRepository {
    private final AlertExecutionRepository _inner;
    private Optional<ActorRef> _successCache;
    private final ActorSystem _actorSystem;
    private final PeriodicMetrics _metrics;

    public CachingAlertExecutionRepository(
        final AlertExecutionRepository inner,
        final PeriodicMetrics metrics,
        final ActorSystem actorSystem
    ) {
        _inner = inner;
        _successCache = Optional.empty();
        _metrics = metrics;
        _actorSystem = actorSystem;
    }

    public CachingAlertExecutionRepository(final Builder builder) {
        _inner = builder._inner;
        _successCache = Optional.empty();
        _metrics = builder._periodicMetrics;
        _actorSystem = builder._actorSystem;
    }

    @Override
    public void open() {
        _inner.open();
        _successCache = Optional.of(_actorSystem.actorOf(
            CacheActor.props("alertExecutionCache", _metrics)
        ));
    }

    @Override
    public void close() {
        _inner.close();
        _successCache.ifPresent(_actorSystem::stop);
    }

    @Override
    public CompletionStage<Optional<JobExecution<AlertEvaluationResult>>> getLastScheduled(
        final UUID jobId, final Organization organization
    ) {
        return _inner.getLastScheduled(jobId, organization);
    }

    @Override
    public CompletionStage<Optional<JobExecution.Success<AlertEvaluationResult>>> getLastSuccess(
        final UUID jobId, final Organization organization
    ) throws NoSuchElementException {
        if (!_successCache.isPresent()) {
            throw new IllegalStateException("cache not started, was open called?");
        }
        final ActorRef ref = _successCache.get();
        final String key = cacheKey(jobId, organization.getId());
        return
            CacheActor.<String, JobExecution.Success<AlertEvaluationResult>>get(ref, key)
                .thenCompose(res -> {
                    if (res.isPresent()) {
                        return CompletableFuture.completedFuture(res);
                    }
                    return _inner.getLastSuccess(jobId, organization)
                        .thenCompose(res2 -> {
                            if (!res2.isPresent()) {
                                return CompletableFuture.completedFuture(res2);
                            }
                            return CacheActor.put(ref, key, res2.get()).thenApply(ignore -> res2);
                        });
                });
    }

    @Override
    public CompletionStage<ImmutableMap<UUID, JobExecution.Success<AlertEvaluationResult>>> getLastSuccessBatch(
        final List<UUID> jobIds,
        final Organization organization,
        final LocalDate maxLookback
    ) throws NoSuchElementException {
        if (!_successCache.isPresent()) {
            throw new IllegalStateException("cache not started, was open called?");
        }
        final ActorRef ref = _successCache.get();
        // Attempt to get all ids from cache
        final List<CompletionStage<Optional<JobExecution.Success<AlertEvaluationResult>>>> futures =
            jobIds.stream()
                .map(id -> cacheKey(id, organization.getId()))
                .map(key -> CacheActor.<String, JobExecution.Success<AlertEvaluationResult>>get(ref, key))
                .collect(ImmutableList.toImmutableList());
        // Accumulate the cache responses into a map.
        final CompletableFuture<ImmutableMap<UUID, JobExecution.Success<AlertEvaluationResult>>> cached =
            CompletableFutures.allOf(futures)
                .thenApply(ignored ->
                    futures.stream().map(f -> {
                        try {
                            return f.toCompletableFuture().get();
                        } catch (InterruptedException | ExecutionException e) {
                            throw new CompletionException(e);
                        }
                    })
                    .flatMap(Streams::stream)
                    .collect(ImmutableMap.toImmutableMap(
                        JobExecution::getJobId,
                        Function.identity()
                    ))
                );
        return cached.thenCompose(hits -> {
            // Check for any cache misses and fetch those from the inner
            // repository.
            final List<UUID> misses =
                jobIds.stream()
                    .filter(Predicates.in(hits.keySet()).negate())
                    .collect(ImmutableList.toImmutableList());
            if (misses.isEmpty()) {
                return CompletableFuture.completedFuture(hits);
            }
            return _inner
                .getLastSuccessBatch(misses, organization, maxLookback)
                .thenCompose(rest -> writeBatchToCache(rest, organization).thenApply(ignore -> rest))
                .thenApply(rest -> {
                    // Merge the cache hits / misses into a single map.
                    return Stream.concat(
                        hits.entrySet().stream(),
                        rest.entrySet().stream()
                    ).collect(ImmutableMap.toImmutableMap(Map.Entry::getKey, Map.Entry::getValue));
                });
        });
    }

    private CompletableFuture<Void> writeBatchToCache(
        final Map<UUID, JobExecution.Success<AlertEvaluationResult>> batch,
        final Organization organization
    ) {
        if (!_successCache.isPresent()) {
            throw new IllegalStateException("cache not started, was open called?");
        }
        final ActorRef ref = _successCache.get();
        ImmutableList<CompletionStage<Void>> writeFutures = batch.values()
            .stream()
            .map(e -> {
                final String key = cacheKey(e.getJobId(), organization.getId());
                return CacheActor.put(ref, key, e);
            })
            .collect(ImmutableList.toImmutableList());
        return CompletableFutures.allOf(writeFutures).thenApply(ignore -> null);
    }

    @Override
    public CompletionStage<Optional<JobExecution<AlertEvaluationResult>>> getLastCompleted(
        final UUID jobId, final Organization organization
    ) throws NoSuchElementException {
        return _inner.getLastCompleted(jobId, organization);
    }

    @Override
    public CompletionStage<Void> jobStarted(
        final UUID jobId,
        final Organization organization,
        final Instant scheduled
    ) {
        return _inner.jobStarted(jobId, organization, scheduled);
    }

    @Override
    public CompletionStage<JobExecution.Success<AlertEvaluationResult>> jobSucceeded(
        final UUID jobId, final Organization organization, final Instant scheduled, final AlertEvaluationResult result
    ) {
        if (!_successCache.isPresent()) {
            throw new IllegalStateException("cache not started, was open called?");
        }
        final ActorRef ref = _successCache.get();
        final String key = cacheKey(jobId, organization.getId());
        return _inner.jobSucceeded(jobId, organization, scheduled, result)
            .thenCompose(e -> CacheActor.putAll(ref, key, e).thenApply(ignore -> e));
    }

    @Override
    public CompletionStage<Void> jobFailed(
        final UUID jobId, final Organization organization, final Instant scheduled, final Throwable error
    ) {
        return _inner.jobFailed(jobId, organization, scheduled, error);
    }

    private String cacheKey(final UUID jobId, final UUID organizationId) {
        return String.format("%s-%s", organizationId, jobId);
    }

    public static final class Builder {
        private AlertExecutionRepository _inner;
        private ActorSystem _actorSystem;
        private PeriodicMetrics _periodicMetrics;

        public CachingAlertExecutionRepository build() {
            return new CachingAlertExecutionRepository(this);
        }

        public Builder setActorSystem(final ActorSystem actorSystem) {
            this._actorSystem = actorSystem;
            return this;
        }

        public Builder setPeriodicMetrics(final PeriodicMetrics periodicMetrics) {
            this._periodicMetrics = periodicMetrics;
            return this;
        }

        public Builder setInner(final AlertExecutionRepository inner) {
            this._inner = inner;
            return this;
        }
    }
}
