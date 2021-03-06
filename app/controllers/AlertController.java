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

import com.arpnetworking.metrics.portal.alerts.AlertExecutionRepository;
import com.arpnetworking.metrics.portal.alerts.AlertRepository;
import com.arpnetworking.metrics.portal.organizations.OrganizationRepository;
import com.arpnetworking.play.metrics.ProblemHelper;
import com.arpnetworking.steno.Logger;
import com.arpnetworking.steno.LoggerFactory;
import com.google.common.collect.ImmutableMap;
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

import java.util.NoSuchElementException;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Collectors;
import javax.annotation.Nullable;

/**
 * Metrics portal alert controller. Exposes APIs to query alerts.
 *
 * @author Christian Briones (cbriones at dropbox dot com).
 */
public class AlertController extends Controller {

    private static final Logger LOGGER = LoggerFactory.getLogger(AlertController.class);

    private final int _maxPageSize;
    private final AlertRepository _alertRepository;
    private final AlertExecutionRepository _executionRepository;
    private final OrganizationRepository _organizationRepository;

    /**
     * Public constructor.
     *
     * @param config Configuration instance.
     * @param alertRepository Repository for alerts.
     * @param executionRepository Repository for associated alert executions.
     * @param organizationRepository Repository for organizations.
     */
    @Inject
    public AlertController(
            final Config config,
            final AlertRepository alertRepository,
            final AlertExecutionRepository executionRepository,
            final OrganizationRepository organizationRepository
    ) {
        _alertRepository = alertRepository;
        _executionRepository = executionRepository;
        _organizationRepository = organizationRepository;
        _maxPageSize = config.getInt("alerts.limit");
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

        final QueryResult<Alert> queryResult =
            _alertRepository.createAlertQuery(organization)
                .limit(argLimit)
                .offset(argOffset.orElse(0))
                .execute();

        return ok(Json.toJson(new PagedContainer<>(
                queryResult.values()
                        .stream()
                        .map(alert -> fromInternal(alert, organization))
                        .collect(Collectors.toList()),
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
}
