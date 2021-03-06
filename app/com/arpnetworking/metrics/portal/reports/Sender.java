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

import com.google.common.collect.ImmutableCollection;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import models.internal.Problem;
import models.internal.reports.Recipient;
import models.internal.reports.ReportFormat;

import java.util.concurrent.CompletionStage;

/**
 * Mechanism for sending reports.
 *
 * @author Spencer Pearson (spencerpearson at dropbox dot com)
 */
public interface Sender {
    /**
     * Send some {@link RenderedReport} documents to a {@link Recipient}.
     *
     * @param recipient The recipient to notify.
     * @param formatsToSend The reports to send. Must be non-empty.
     * @return A CompletionStage that completes when the transmission has completed.
     */
    CompletionStage<Void> send(
            Recipient recipient,
            ImmutableMap<ReportFormat, RenderedReport> formatsToSend
    );

    /**
     * Check whether the sender can send the given report formats to a given recipient.
     * Accepts all valid recipient/format inputs, rejects <i>some</i> invalid ones.
     *
     * @param recipient The recipient.
     * @param formatsToSend The rendered report formats to be sent.
     * @return A list of errors that are sure to occur if we try to send those formats to that recipient.
     */
    ImmutableList<Problem> validateSend(
            Recipient recipient,
            ImmutableCollection<ReportFormat> formatsToSend
    );
}
