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

package com.arpnetworking.metrics.portal.integration.repositories;

import com.arpnetworking.metrics.incubator.PeriodicMetrics;
import com.arpnetworking.metrics.portal.TestBeanFactory;
import com.arpnetworking.metrics.portal.alerts.impl.DatabaseAlertExecutionRepository;
import com.arpnetworking.metrics.portal.integration.test.EbeanServerHelper;
import com.arpnetworking.metrics.portal.scheduling.JobExecutionRepository;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import io.ebean.EbeanServer;
import models.internal.Organization;
import models.internal.alerts.AlertEvaluationResult;
import models.internal.impl.DefaultAlertEvaluationResult;
import org.mockito.Mockito;

import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

/**
 * Integration tests for {@link DatabaseAlertExecutionRepository}.
 *
 * @author Christian Briones (cbriones at dropbox dot com)
 */
public class DatabaseAlertExecutionRepositoryIT extends JobExecutionRepositoryIT<AlertEvaluationResult> {
    @Override
    public JobExecutionRepository<AlertEvaluationResult> setUpRepository(final Organization organization) {
        final EbeanServer server = EbeanServerHelper.getMetricsDatabase();
        final EbeanServer adminServer = EbeanServerHelper.getAdminMetricsDatabase();

        final models.ebean.Organization ebeanOrganization = TestBeanFactory.createEbeanOrganization();
        ebeanOrganization.setUuid(organization.getId());
        server.save(ebeanOrganization);

        final PeriodicMetrics metricsMock = Mockito.mock(PeriodicMetrics.class);

        return new DatabaseAlertExecutionRepository(
                server,
                adminServer,
                getActorSystem(),
                metricsMock,
                Duration.ZERO,
                5 // Arbitrary, but helps distinguish logs
        );
    }

    @Override
    public void ensureJobExists(final Organization organization, final UUID jobId) {
        // DatabaseAlertExecutionRepository does not validate that the JobID is a valid AlertID since those
        // references are not constrained in the underlying execution table.
    }

    @Override
    AlertEvaluationResult newResult() {
        final Instant queryEnd = Instant.now();
        return new DefaultAlertEvaluationResult.Builder()
                .setSeriesName("example-series")
                .setFiringTags(ImmutableList.of(ImmutableMap.of("tag-name", UUID.randomUUID().toString())))
                .setGroupBys(ImmutableList.of("tag-name"))
                .setQueryStartTime(queryEnd.minus(Duration.ofMinutes(1)))
                .setQueryEndTime(queryEnd)
                .build();
    }
}
