package com.arpnetworking.kairos.service;

import com.arpnetworking.commons.builder.ThreadLocalBuilder;
import com.arpnetworking.kairos.client.models.MetricNamesResponse;
import com.arpnetworking.kairos.client.models.MetricsQuery;
import com.arpnetworking.kairos.client.models.MetricsQueryResponse;
import com.arpnetworking.kairos.client.models.TagNamesResponse;
import com.arpnetworking.kairos.client.models.TagsQuery;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableListMultimap;
import com.google.common.collect.ImmutableSet;
import com.google.inject.Inject;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletionStage;

public class TagFilteringKairosDbService implements KairosDbService {
    @Inject
    public TagFilteringKairosDbService(final KairosDbService service, final List<String> excludedTagNames) {
        _service = service;
        _excludedTagNames = excludedTagNames;
    }

    @Override
    public CompletionStage<MetricsQueryResponse> queryMetrics(final MetricsQuery metricsQuery) {
        final ImmutableSet<String> requestedTags = metricsQuery.getMetrics()
                .stream()
                .flatMap(m -> m.getTags().keySet().stream())
                .collect(ImmutableSet.toImmutableSet());
        return _service.queryMetrics(metricsQuery)
                .thenApply(res -> filterExcludedTags(res, requestedTags));
    }

    @Override
    public CompletionStage<MetricNamesResponse> queryMetricNames(final Optional<String> containing, final Optional<String> prefix, final boolean filterRollups) {
        return _service.queryMetricNames(containing, prefix, filterRollups);
    }

    @Override
    public CompletionStage<MetricsQueryResponse> queryMetricTags(final TagsQuery tagsQuery) {
        final ImmutableSet<String> requestedTags = tagsQuery.getMetrics()
                .stream()
                .flatMap(m -> m.getTags().keySet().stream())
                .collect(ImmutableSet.toImmutableSet());
        return _service.queryMetricTags(tagsQuery).thenApply(res -> filterExcludedTags(res, requestedTags));
    }

    @Override
    public CompletionStage<TagNamesResponse> listTagNames() {
        return _service.listTagNames()
                .thenApply(response -> ThreadLocalBuilder.<TagNamesResponse, TagNamesResponse.Builder>clone(response)
                        .setResults(
                                response.getResults()
                                        .stream()
                                        .filter(e -> !_excludedTagNames.contains(e))
                                        .collect(ImmutableSet.toImmutableSet()))
                        .build());
    }

    private MetricsQueryResponse filterExcludedTags(
            final MetricsQueryResponse originalResponse,
            final ImmutableSet<String> retainedTags) {
        return ThreadLocalBuilder.clone(
                originalResponse,
                MetricsQueryResponse.Builder.class,
                responseBuilder -> responseBuilder.setQueries(
                        originalResponse.getQueries()
                                .stream()
                                .map(originalQuery -> ThreadLocalBuilder.clone(
                                        originalQuery,
                                        MetricsQueryResponse.Query.Builder.class,
                                        queryBuilder -> queryBuilder.setResults(
                                                originalQuery.getResults()
                                                        .stream()
                                                        .map(result -> filterQueryResultTags(result, retainedTags))
                                                        .collect(ImmutableList.toImmutableList()))))
                                .collect(ImmutableList.toImmutableList())));
    }

    private MetricsQueryResponse.QueryResult filterQueryResultTags(
            final MetricsQueryResponse.QueryResult originalResult,
            final ImmutableSet<String> retainedTags) {
        return ThreadLocalBuilder.clone(
                originalResult,
                MetricsQueryResponse.QueryResult.Builder.class,
                resultBuilder -> resultBuilder.setTags(
                        originalResult.getTags()
                                .entries()
                                .stream()
                                .filter(e -> !_excludedTagNames.contains(e.getKey()) || retainedTags.contains(e.getKey()))
                                .collect(ImmutableListMultimap.toImmutableListMultimap(
                                        e -> e.getKey(),
                                        e -> e.getValue()))));
    }

    private final KairosDbService _service;
    private final List<String> _excludedTagNames;
}
