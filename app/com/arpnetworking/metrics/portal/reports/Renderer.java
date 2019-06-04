/*
 * Copyright 2019 Dropbox, Inc.
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

package com.arpnetworking.metrics.portal.reports;

import models.internal.reports.ReportFormat;
import models.internal.reports.ReportSource;

import java.time.Instant;
import java.util.concurrent.CompletionStage;

/**
 * A mechanism for rendering a particular kind of {@link ReportSource} into a particular kind of {@link ReportFormat}.
 *
 * @param <S> The type of {@link ReportSource} to render.
 * @param <F> The type of {@link ReportFormat} to render into.
 *
 * @author Spencer Pearson (spencerpearson at dropbox dot com)
 */
public interface Renderer<S extends ReportSource, F extends ReportFormat> {
    /**
     * Render a ReportSource.
     *
     * @param source The source to render.
     * @param format The format to render into.
     * @param scheduled The instant that the report-job was scheduled for.
     * @return A CompletionStage that completes when the report has been rendered.
     */
    CompletionStage<RenderedReport> render(S source, F format, Instant scheduled);
}