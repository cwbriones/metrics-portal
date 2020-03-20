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

import com.arpnetworking.commons.builder.ThreadLocalBuilder;
import com.arpnetworking.kairos.client.models.Aggregator;
import com.arpnetworking.kairos.client.models.Metric;
import com.arpnetworking.kairos.client.models.MetricNamesResponse;
import com.arpnetworking.kairos.client.models.MetricTags;
import com.arpnetworking.kairos.client.models.MetricsQuery;
import com.arpnetworking.kairos.client.models.MetricsQueryResponse;
import com.arpnetworking.kairos.client.models.Sampling;
import com.arpnetworking.kairos.client.models.SamplingUnit;
import com.arpnetworking.kairos.client.models.TagNamesResponse;
import com.arpnetworking.kairos.client.models.TagsQuery;
import com.arpnetworking.kairos.config.MetricsQueryConfig;
import com.arpnetworking.metrics.Metrics;
import com.arpnetworking.metrics.MetricsFactory;
import com.google.common.collect.ImmutableList;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.function.Predicate;
import java.util.stream.Collectors;

/**
 * Defines a service provider that augments calls to a KairosDB backend server.
 * <p>
 * This class applies logic to KairosDB requests to modify the behavior of KairosDB
 * in a way that provides specific behaviors that wouldn't be acceptable for a more
 * generic service like KairosDB.
 *
 * @author Gilligan Markham (gmarkham at dropbox dot com)
 */
public final class RollupKairosDbService implements KairosDbService {

    private static final String ROLLUP_OVERRIDE = "_!";
    private static final Predicate<String> IS_PT1M = s -> s.startsWith("PT1M/");
    private static final Predicate<String> IS_ROLLUP = s -> s.endsWith("_1h") || s.endsWith("_1d");
    private final BasicKairosDbService _service;
    private final MetricsQueryConfig _metricsQueryConfig;
    private final MetricsFactory _metricsFactory;

    public RollupKairosDbService(
            final BasicKairosDbService service,
            final MetricsQueryConfig config,
            final MetricsFactory metricsFactory
    ) {
        this._service = service;
        this._metricsQueryConfig = config;
        this._metricsFactory = metricsFactory;
    }

    private static MetricsQuery useAvailableRollups(
            final List<String> metricNames,
            final MetricsQuery originalQuery,
            final MetricsQueryConfig queryConfig,
            final Metrics metrics) {
        return ThreadLocalBuilder.clone(
                originalQuery,
                MetricsQuery.Builder.class,
                newQueryBuilder -> newQueryBuilder.setMetrics(originalQuery.getMetrics().stream().map(metric -> {
                    // Check to see if there are any rollups for this metrics
                    final String metricName = metric.getName();
                    if (metricName.endsWith(ROLLUP_OVERRIDE)) {
                        metrics.incrementCounter("kairosService/useRollups/bypass", 1);
                        // Special case a _! suffix to not apply rollup selection
                        // Drop the suffix and forward the request
                        return Metric.Builder.<Metric, Metric.Builder>clone(metric)
                                .setName(metricName.substring(0, metricName.length() - 2))
                                .build();
                    }
                    metrics.incrementCounter("kairosService/useRollups/bypass", 0);
                    final ImmutableList<String> filteredMetrics = filterMetricNames(
                            metricNames,
                            Optional.of(metricName),
                            Optional.empty(),
                            false
                    );
                    final List<String> rollupMetrics = filteredMetrics
                            .stream()
                            .filter(IS_ROLLUP)
                            .filter(s -> s.length() == metricName.length() + 3)
                            .collect(Collectors.toList());

                    if (rollupMetrics.isEmpty()) {
                        metrics.incrementCounter("kairosService/useRollups/noRollups", 1);
                        // No rollups so execute what we received
                        return metric;
                    }

                    // There are rollups and queries are enabled, now determine the appropriate one
                    // based on the max sampling period in the aggregators.
                    //
                    // For any given query, we can at best use the rollup based on the smallest
                    // sampling unit, if any.
                    final Optional<SamplingUnit> maxUnit = metric.getAggregators().stream()
                            .filter(agg -> agg.getAlignSampling().orElse(Boolean.FALSE)) // Filter out non-sampling aligned
                            .map(Aggregator::getSampling)
                            .map(sampling -> sampling.map(Sampling::getUnit).orElse(SamplingUnit.MILLISECONDS))
                            .min(SamplingUnit::compareTo);


                    if (maxUnit.isPresent()) {
                        final SamplingUnit unit = maxUnit.get();
                        final Set<SamplingUnit> enabledRollups = queryConfig.getQueryEnabledRollups(metricName);

                        final TreeMap<SamplingUnit, String> orderedRollups = new TreeMap<>();
                        rollupMetrics.forEach(name -> {
                            final Optional<SamplingUnit> rollupUnit =
                                    rollupSuffixToSamplingUnit(name.substring(metricName.length() + 1));
                            rollupUnit.ifPresent(samplingUnit -> {
                                if (enabledRollups.contains(samplingUnit)) {
                                    orderedRollups.put(samplingUnit, name);
                                }
                            });
                        });

                        final Map.Entry<SamplingUnit, String> floorEntry = orderedRollups.floorEntry(unit);
                        metrics.incrementCounter("kairosService/useRollups/noMatchingRollup", floorEntry != null ? 1 : 0);
                        final String rollupName = floorEntry != null ? floorEntry.getValue() : metricName;
                        final Metric.Builder metricBuilder = Metric.Builder.<Metric, Metric.Builder>clone(metric)
                                .setName(rollupName);

                        return metricBuilder.build();
                    } else {
                        // No aggregators are sampling aligned so skip as rollups are always aligned
                        metrics.incrementCounter("kairosService/useRollups/notEligible", 1);
                        return metric;
                    }
                }).collect(ImmutableList.toImmutableList())));
    }

    private static Optional<SamplingUnit> rollupSuffixToSamplingUnit(final String suffix) {
        // Assuming we only rollup to a single sampling unit (e.g. 1 hour or 1 day) and not multiples
        switch (suffix.charAt(suffix.length() - 1)) {
            case 'h':
                return Optional.of(SamplingUnit.HOURS);
            case 'd':
                return Optional.of(SamplingUnit.DAYS);
            case 'w':
                return Optional.of(SamplingUnit.WEEKS);
            case 'm':
                return Optional.of(SamplingUnit.MONTHS);
            case 'y':
                return Optional.of(SamplingUnit.YEARS);
            default:
                return Optional.empty();
        }
    }

    @Override
    public CompletionStage<MetricsQueryResponse> queryMetricTags(final TagsQuery tagsQuery) {
        return filterRollupOverrides(tagsQuery).thenCompose(_service::queryMetricTags);
    }

    @Override
    public CompletionStage<MetricsQueryResponse> queryMetrics(final MetricsQuery metricsQuery) {
        final Metrics metrics = _metricsFactory.create();

        _service.queryMetricNames(Optional.empty(), Optional.empty(), false)
                .thenApply(res -> useAvailableRollups(res.getResults(), metricsQuery, _metricsQueryConfig, metrics))
                .thenCompose(_service::queryMetrics);
    }

    @Override
    public CompletionStage<MetricNamesResponse> queryMetricNames(
            final Optional<String> containing,
            final Optional<String> prefix,
            final boolean filterRollups) {

        final Predicate<String> baseFilter;
        if (filterRollups) {
            baseFilter = IS_ROLLUP.negate();
        } else {
            baseFilter = s -> true;
        }

        return _service.queryMetricNames(containing, prefix, filterRollups)
                .thenApply(res -> {
                    final ImmutableList<String> filtered =
                            res.getResults()
                                    .stream()
                                    .filter(baseFilter)
                                    .filter(IS_PT1M.negate())
                                    .collect(ImmutableList.toImmutableList());

                    return ThreadLocalBuilder.<MetricNamesResponse, MetricNamesResponse.Builder>clone(res)
                            .setResults(filtered)
                            .build();
                });
    }

    @Override
    public CompletionStage<TagNamesResponse> listTagNames() {
        return _service.listTagNames();
    }

    private CompletionStage<TagsQuery> filterRollupOverrides(final TagsQuery originalQuery) {
        return CompletableFuture.completedFuture(
                ThreadLocalBuilder.clone(
                        originalQuery,
                        TagsQuery.Builder.class,
                        newQueryBuilder -> newQueryBuilder.setMetrics(originalQuery.getMetrics().stream().map(metric -> {
                            // Check to see if there are any rollups for this metrics
                            final String metricName = metric.getName();
                            if (metricName.endsWith(ROLLUP_OVERRIDE)) {
                                // Special case a _! suffix to not apply rollup selection
                                // Drop the suffix and forward the request
                                return ThreadLocalBuilder.clone(
                                        metric,
                                        MetricTags.Builder.class, b -> b.setName(metricName.substring(0, metricName.length() - 2)));
                            } else {
                                return metric;
                            }
                        }).collect(ImmutableList.toImmutableList()))));
    }
}
