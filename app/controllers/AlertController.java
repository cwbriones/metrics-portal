/*
 * Copyright 2020 Dropbox, Inc.
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
package controllers;

import com.arpnetworking.metrics.incubator.PeriodicMetrics;
import com.arpnetworking.metrics.portal.alerts.AlertExecutionRepository;
import com.arpnetworking.metrics.portal.alerts.AlertRepository;
import com.arpnetworking.metrics.portal.organizations.OrganizationRepository;
import com.arpnetworking.play.metrics.ProblemHelper;
import com.arpnetworking.steno.Logger;
import com.arpnetworking.steno.LoggerFactory;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.Lists;
import com.google.common.collect.Maps;
import com.google.inject.Inject;
import com.typesafe.config.Config;
import models.internal.Organization;
import models.internal.Problem;
import models.internal.QueryResult;
import models.internal.alerts.Alert;
import models.internal.alerts.AlertEvaluationResult;
import models.internal.scheduling.JobExecution;
import models.view.PagedContainer;
import models.view.Pagination;
import play.libs.Json;
import play.mvc.Controller;
import play.mvc.Result;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZonedDateTime;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import javax.annotation.Nullable;

/**
 * Metrics portal alert controller. Exposes APIs to query alerts.
 *
 * @author Christian Briones (cbriones at dropbox dot com).
 */
public class AlertController extends Controller {

    private static final String CONFIG_LIMIT = "alerts.limit";
    private static final String CONFIG_EXECUTIONS_BATCH_SIZE = "alerts.executions.batchSize";
    private static final String CONFIG_EXECUTIONS_LOOKBACK_DAYS = "alerts.executions.lookbackDays";

    private static final int DEFAULT_EXECUTIONS_BATCH_SIZE = 500;
    private static final int DEFAULT_EXECUTIONS_LOOKBACK_DAYS = 7;

    private static final Logger LOGGER = LoggerFactory.getLogger(AlertController.class);

    // The maximum page size. Any requests larger than this amount will be truncated.
    private final int _maxPageSize;
    // The maximum number of executions to fetch per batch.
    private final int _executionsBatchSize;
    // The number of days back to check for executions.
    private final int _executionsLookbackDays;
    private final AlertRepository _alertRepository;
    private final AlertExecutionRepository _executionRepository;
    private final OrganizationRepository _organizationRepository;
    private final PeriodicMetrics _periodicMetrics;

    /**
     * Public constructor.
     *
     * @param config Configuration instance.
     * @param alertRepository Repository for alerts.
     * @param executionRepository Repository for associated alert executions.
     * @param organizationRepository Repository for organizations.
     * @param periodicMetrics Metrics instance for instrumentation.
     */
    @Inject
    public AlertController(
            final Config config,
            final AlertRepository alertRepository,
            final AlertExecutionRepository executionRepository,
            final OrganizationRepository organizationRepository,
            final PeriodicMetrics periodicMetrics
    ) {
        _alertRepository = alertRepository;
        _executionRepository = executionRepository;
        _organizationRepository = organizationRepository;

        _maxPageSize = config.getInt(CONFIG_LIMIT);
        if (config.hasPath(CONFIG_EXECUTIONS_BATCH_SIZE)) {
            _executionsBatchSize = config.getInt(CONFIG_EXECUTIONS_BATCH_SIZE);
        } else {
            _executionsBatchSize = DEFAULT_EXECUTIONS_BATCH_SIZE;
        }
        if (config.hasPath(CONFIG_EXECUTIONS_LOOKBACK_DAYS)) {
            _executionsLookbackDays = config.getInt(CONFIG_EXECUTIONS_LOOKBACK_DAYS);
        } else {
            _executionsLookbackDays = DEFAULT_EXECUTIONS_LOOKBACK_DAYS;
        }
        _periodicMetrics = periodicMetrics;
    }

    /**
     * Get a specific alert.
     *
     * @param id The identifier of the alert.
     * @return The alert, if any, otherwise notFound.
     */
    public Result get(final UUID id) {
        final Organization organization;
        try {
            organization = _organizationRepository.get(request());
        } catch (final NoSuchElementException e) {
            return internalServerError();
        }
        final Optional<Alert> alert = _alertRepository.getAlert(id, organization);
        return alert
                .map(a -> ok(Json.toJson(fromInternal(a, organization))))
                .orElseGet(() -> notFound(ProblemHelper.createErrorJson(new Problem.Builder()
                        .setProblemCode("alert_problem.NOT_FOUND")
                        .build()
                )));
    }

    /**
     * Query for alerts.
     *
     * @param limit The maximum number of results to return. Optional.
     * @param offset The number of results to skip. Optional.
     * @return {@link Result} paginated matching alerts.
     */
    public Result query(
            @Nullable final Integer limit,
            @Nullable final Integer offset
    ) {
        final Organization organization;
        try {
            organization = _organizationRepository.get(request());
        } catch (final NoSuchElementException e) {
            return internalServerError();
        }

        // Convert and validate parameters
        final int argLimit = Optional.ofNullable(limit)
                .map(l -> Math.min(l, _maxPageSize))
                .orElse(_maxPageSize);
        if (argLimit < 0) {
            return badRequest("Invalid limit; must be greater than or equal to 0");
        }

        final Optional<Integer> argOffset = Optional.ofNullable(offset);
        if (argOffset.isPresent() && argOffset.get() < 0) {
            return badRequest("Invalid offset; must be greater than or equal to 0");
        }

        final Instant queryStart = Instant.now();
        final QueryResult<Alert> queryResult =
            _alertRepository.createAlertQuery(organization)
                .limit(argLimit)
                .offset(argOffset.orElse(0))
                .execute();
        _periodicMetrics.recordTimer(
                "alerts/controller/alert_query_latency",
                ChronoUnit.MILLIS.between(queryStart, Instant.now()),
                Optional.of(TimeUnit.MILLISECONDS)
        );

        return ok(Json.toJson(new PagedContainer<>(
                fromInternal(queryResult.values(), organization),
                new Pagination(
                        request().path(),
                        queryResult.total(),
                        queryResult.values().size(),
                        argLimit,
                        argOffset,
                        ImmutableMap.of()))));
    }

    private models.view.alerts.Alert fromInternal(final Alert alert, final Organization organization) {
        final Optional<JobExecution.Success<AlertEvaluationResult>> mostRecentEvaluation =
                _executionRepository.getLastSuccess(alert.getId(), organization);

        return models.view.alerts.Alert.fromInternal(
                alert,
                mostRecentEvaluation
        );
    }

    private ImmutableList<models.view.alerts.Alert> fromInternal(final List<? extends Alert> alerts, final Organization organization) {

        final LocalDate maxLookback = ZonedDateTime.now().minusDays(_executionsLookbackDays).toLocalDate();
        final ImmutableList<UUID> jobIds = alerts.stream().map(Alert::getId).collect(ImmutableList.toImmutableList());

        final Instant lookupStart = Instant.now();
        final Map<UUID, JobExecution.Success<AlertEvaluationResult>> executions = Maps.newHashMapWithExpectedSize(alerts.size());
        for (final List<UUID> jobIdBatch : Lists.partition(jobIds, _executionsBatchSize)) {
            final Instant batchLookupStart = Instant.now();
            final ImmutableMap<UUID, JobExecution.Success<AlertEvaluationResult>> executionsBatch =
                    _executionRepository.getLastSuccessBatch(jobIdBatch, organization, maxLookback);
            _periodicMetrics.recordTimer(
                    "alerts/controller/from_internal_latency/batch",
                    ChronoUnit.MILLIS.between(batchLookupStart, Instant.now()),
                    Optional.of(TimeUnit.MILLISECONDS)
            );
            executions.putAll(executionsBatch);
        }
        _periodicMetrics.recordTimer(
                "alerts/controller/from_internal_latency/total",
                ChronoUnit.MILLIS.between(lookupStart, Instant.now()),
                Optional.of(TimeUnit.MILLISECONDS)
        );

        final ImmutableList.Builder<models.view.alerts.Alert> results = new ImmutableList.Builder<>();
        for (final Alert alert : alerts) {
            final Optional<JobExecution.Success<AlertEvaluationResult>> execution = Optional.ofNullable(executions.get(alert.getId()));
            results.add(models.view.alerts.Alert.fromInternal(alert, execution));
        }
        return results.build();
    }
}
