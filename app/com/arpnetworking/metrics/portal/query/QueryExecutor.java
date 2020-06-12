/*
 * Copyright 2018 Smartsheet
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
package com.arpnetworking.metrics.portal.query;

import models.internal.MetricsQuery;
import models.internal.MetricsQueryResult;

import java.util.concurrent.CompletionStage;

/**
 * Interface to describe classes that execute time series queries.
 *
 * @see MetricsQuery
 * @see MetricsQueryResult
 *
 * @author Brandon Arp (brandon dot arp at smartsheet dot com)
 */
public interface QueryExecutor {
    /**
     * Executes a query.
     *
     * @param query query to execute
     * @return {@link CompletionStage} of the result
     */
    CompletionStage<MetricsQueryResult> executeQuery(MetricsQuery query);
}
