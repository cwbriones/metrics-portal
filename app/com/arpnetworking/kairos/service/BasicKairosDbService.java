/*
 * Copyright 2019 Dropbox Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.arpnetworking.kairos.service;

import com.arpnetworking.commons.builder.OvalBuilder;
import com.arpnetworking.kairos.client.KairosDbClient;
import com.arpnetworking.kairos.client.models.MetricNamesResponse;
import com.arpnetworking.kairos.client.models.MetricsQuery;
import com.arpnetworking.kairos.client.models.MetricsQueryResponse;
import com.arpnetworking.kairos.client.models.TagNamesResponse;
import com.arpnetworking.kairos.client.models.TagsQuery;
import com.arpnetworking.metrics.Metrics;
import com.arpnetworking.metrics.MetricsFactory;
import com.arpnetworking.metrics.Timer;
import com.google.common.cache.Cache;
import com.google.common.cache.CacheBuilder;
import com.google.common.collect.ImmutableList;
import net.sf.oval.constraint.NotNull;

import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Predicate;

/**
 * Defines a service provider that augments calls to a KairosDB backend server.
 * <p>
 * This class applies logic to KairosDB requests to modify the behavior of KairosDB
 * in a way that provides specific behaviors that wouldn't be acceptable for a more
 * generic service like KairosDB.
 *
 * @author Gilligan Markham (gmarkham at dropbox dot com)
 */
public final class BasicKairosDbService implements KairosDbService {

    @Override
    public CompletionStage<MetricsQueryResponse> queryMetricTags(final TagsQuery tagsQuery) {
        final Metrics metrics = _metricsFactory.create();
        final Timer timer = metrics.createTimer("kairosService/queryMetricTags/request");
        // Filter out rollup metric overrides and forward the query
        return _kairosDbClient.queryMetricTags(tagsQuery)
                .whenComplete((result, error) -> {
                    timer.stop();
                    metrics.incrementCounter("kairosService/queryMetricTags/success", error == null ? 1 : 0);
                    metrics.close();
                });
    }

    @Override
    public CompletionStage<MetricsQueryResponse> queryMetrics(final MetricsQuery metricsQuery) {
        final Metrics metrics = _metricsFactory.create();
        final Timer timer = metrics.createTimer("kairosService/queryMetrics/request");
        return _kairosDbClient.queryMetrics(metricsQuery)
                .whenComplete((result, error) -> {
                    timer.stop();
                    metrics.incrementCounter("kairosService/queryMetrics/success", error == null ? 1 : 0);
                    metrics.close();
                });
    }

    @Override
    public CompletionStage<MetricNamesResponse> queryMetricNames(
            final Optional<String> containing,
            final Optional<String> prefix,
            final boolean filterRollups) {
        final Metrics metrics = _metricsFactory.create();
        final Timer timer = metrics.createTimer("kairosService/queryMetricNames/request");

        return getMetricNames(metrics)
                .thenApply(list -> filterMetricNames(list, containing, prefix))
                .thenApply(list -> new MetricNamesResponse.Builder().setResults(list).build())
                .whenComplete((result, error) -> {
                    timer.stop();
                    metrics.incrementCounter("kairosService/queryMetricNames/success", error == null ? 1 : 0);
                    metrics.addAnnotation("containing", containing.isPresent() ? "true" : "false");
                    if (result != null) {
                        metrics.incrementCounter("kairosService/queryMetricNames/count", result.getResults().size());
                    }
                    metrics.close();
                });
    }

    @Override
    public CompletionStage<TagNamesResponse> listTagNames() {
        final Metrics metrics = _metricsFactory.create();
        final Timer timer = metrics.createTimer("kairosService/listTagNames/request");

        return _kairosDbClient.listTagNames()
                .whenComplete((result, error) -> {
                    timer.stop();
                    metrics.incrementCounter("kairosService/listTagNames/success", error == null ? 1 : 0);
                    if (result != null) {
                        metrics.incrementCounter("kairosService/listTagNames/count", result.getResults().size());
                    }
                    metrics.close();
                });
    }

    private static ImmutableList<String> filterMetricNames(
            final List<String> metricNames,
            final Optional<String> containing,
            final Optional<String> prefix) {

        final Predicate<String> containsFilter;
        if (containing.isPresent() && !containing.get().isEmpty()) {
            final String lowerContaining = containing.get().toLowerCase(Locale.getDefault());
            containsFilter = s -> s.toLowerCase(Locale.getDefault()).contains(lowerContaining);
        } else {
            containsFilter = s -> true;
        }

        final Predicate<String> prefixFilter;
        if (prefix.isPresent() && !prefix.get().isEmpty()) {
            final String lowerPrefix = prefix.get().toLowerCase(Locale.getDefault());
            prefixFilter = s -> s.toLowerCase(Locale.getDefault()).startsWith(lowerPrefix);
        } else {
            prefixFilter = s -> true;
        }

        return metricNames.stream()
                .filter(IS_PT1M.negate())
                .filter(containsFilter)
                .filter(prefixFilter)
                .collect(ImmutableList.toImmutableList());
    }


    CompletionStage<List<String>> getMetricNames(final Metrics metrics) {
        final List<String> metricsNames = _cache.getIfPresent(METRICS_KEY);

        final CompletionStage<List<String>> response;
        if (metricsNames != null) {
            metrics.incrementCounter("kairosService/metricNames/cache", 1);
            response = CompletableFuture.completedFuture(metricsNames);
        } else {
            metrics.incrementCounter("kairosService/metricNames/cache", 0);
            // TODO(brandon): Investigate refreshing eagerly or in the background
            // Refresh the cache
            final Timer timer = metrics.createTimer("kairosService/metricNames/request");
            final CompletionStage<List<String>> queryResponse = _kairosDbClient.queryMetricNames()
                    .whenComplete((result, error) -> {
                        timer.stop();
                        metrics.incrementCounter("kairosService/metricNames/success", error == null ? 1 : 0);
                    })
                    .thenApply(MetricNamesResponse::getResults)
                    .thenApply(list -> {
                        _cache.put(METRICS_KEY, list);
                        _metricsList.set(list);
                        return list;
                    });

            final List<String> metricsList = _metricsList.get();
            if (metricsList != null) {
                response = CompletableFuture.completedFuture(metricsList);
            } else {
                response = queryResponse;
            }
        }

        return response;
    }


    private BasicKairosDbService(final Builder builder) {
        this._kairosDbClient = builder._kairosDbClient;
        this._metricsFactory = builder._metricsFactory;
    }

    private final KairosDbClient _kairosDbClient;
    private final MetricsFactory _metricsFactory;
    private final Cache<String, List<String>> _cache = CacheBuilder.newBuilder().expireAfterWrite(1, TimeUnit.MINUTES).build();
    private final AtomicReference<List<String>> _metricsList = new AtomicReference<>(null);
    private static final String METRICS_KEY = "METRICNAMES";
    private static final Predicate<String> IS_PT1M = s -> s.startsWith("PT1M/");

    /**
     * Implementation of the builder pattern for {@link BasicKairosDbService}.
     *
     * @author Ville Koskela (ville dot koskela at inscopemetrics dot io)
     */
    public static final class Builder extends OvalBuilder<BasicKairosDbService> {
        /**
         * Public constructor.
         */
        public Builder() {
            super(BasicKairosDbService::new);
        }

        /**
         * Sets the {@link KairosDbClient} to use. Cannot be null.
         *
         * @param value the {@link KairosDbClient} to use
         * @return this {@link Builder}
         */
        public Builder setKairosDbClient(final KairosDbClient value) {
            _kairosDbClient = value;
            return this;
        }

        /**
         * Sets the {@link MetricsFactory} to use. Cannot be null.
         *
         * @param value the {@link MetricsFactory} to use
         * @return this {@link Builder}
         */
        public Builder setMetricsFactory(final MetricsFactory value) {
            _metricsFactory = value;
            return this;
        }

        @NotNull
        private KairosDbClient _kairosDbClient;
        @NotNull
        private MetricsFactory _metricsFactory;
    }
}
