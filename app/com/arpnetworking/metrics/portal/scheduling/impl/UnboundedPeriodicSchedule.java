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
package com.arpnetworking.metrics.portal.scheduling.impl;

import com.arpnetworking.commons.builder.OvalBuilder;
import com.arpnetworking.logback.annotations.Loggable;
import com.arpnetworking.metrics.portal.scheduling.JobExecutorActor;
import com.arpnetworking.metrics.portal.scheduling.Schedule;
import com.arpnetworking.steno.Logger;
import com.arpnetworking.steno.LoggerFactory;
import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.MoreObjects;
import com.google.common.base.Objects;
import net.sf.oval.constraint.Min;
import net.sf.oval.constraint.NotNull;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Optional;
import java.util.function.Consumer;

/**
 * A schedule for a job that repeats periodically, without any bounds or regard
 * for the last completed time.
 * <p>
 * The next run for a unbounded periodic schedule is always the next period of the
 * schedule, edge-aligned as measured from the start of the epoch.
 * <p>
 * <b>WARNING:</b>
 * This behavior means that jobs with this schedule will ignore previously missed runs.
 * If you need backfills you should instead use a standard {@code PeriodicSchedule}.
 * <p>
 * If this behavior is still acceptable, you have to watch out for the potential for
 * skipping a period for a variety of reasons:
 * <ul>
 *     <li> The {@link JobExecutorActor} tied to this schedule is moved among hosts,
 *     and this operation takes longer than an interval.
 *     <li> The system clock jumps forward longer than an interval.
 *     <li> The execution pool becomes saturated, and evaluating all jobs at for a
 *     particular interval overruns that interval.
 *     <li> Any combination of the above resulting in a delay longer than a period.
 * </ul>
 *
 *
 * @see PeriodicSchedule
 * @author Christian Briones (cbriones at dropbox dot com)
 */
@Loggable
public final class UnboundedPeriodicSchedule implements Schedule {

    private static final Logger LOGGER = LoggerFactory.getLogger(UnboundedPeriodicSchedule.class);

    private final Duration _fullPeriod;
    private final Clock _clock;
    private final Consumer<Long> _overrunReporter;

    private UnboundedPeriodicSchedule(final Builder builder) {
        _fullPeriod = Duration.of(builder._periodCount, builder._period);
        _clock = builder._clock;
        _overrunReporter = builder._overrunReporter;
    }

    @Override
    public Optional<Instant> nextRun(final Optional<Instant> lastRun) {
        final Instant now = _clock.instant();
        final Instant nextRun = truncateToPeriod(now).plus(_fullPeriod);

        lastRun.ifPresent(run -> checkForSkippedPeriods(run, nextRun));

        return Optional.of(nextRun);
    }

    private void checkForSkippedPeriods(final Instant lastRun, final Instant nextRun) {
        final Instant lastRunTruncated = truncateToPeriod(lastRun);

        final Duration elapsedBetweenRuns = Duration.between(lastRunTruncated, nextRun);
        if (elapsedBetweenRuns.compareTo(_fullPeriod) <= 0) {
            return;
        }
        // Elapsed time is greater than a period, which means the caller of
        // Schedule#nextRun will skip all periods in between.

        // We don't need anything better than second precision.
        final long periodsSkipped = elapsedBetweenRuns.getSeconds() / _fullPeriod.getSeconds();
        LOGGER.warn()
            .setMessage("Possible job overrun detected")
            .addData("periodsSkipped", periodsSkipped)
            .setThrowable(new RuntimeException("Possible job overrun detected"))
            .log();
        _overrunReporter.accept(periodsSkipped - 1);
    }

    private Instant truncateToPeriod(final Instant instant) {
        final long excessMillis = instant.toEpochMilli() % _fullPeriod.toMillis();
        return instant.minusMillis(excessMillis);
    }

    @Override
    public <T> T accept(final Visitor<T> visitor) {
        return visitor.visitUnboundedPeriodic(this);
    }

    @Override
    public boolean equals(final Object o) {
        if (this == o) {
            return true;
        }
        if (o == null || getClass() != o.getClass()) {
            return false;
        }
        final UnboundedPeriodicSchedule that = (UnboundedPeriodicSchedule) o;
        return Objects.equal(_fullPeriod, that._fullPeriod)
                && Objects.equal(_clock, that._clock);
    }

    @Override
    public int hashCode() {
        return Objects.hashCode(_fullPeriod, _clock);
    }

    @Override
    public String toString() {
        return MoreObjects.toStringHelper(this)
                .add("_fullPeriod", _fullPeriod)
                .add("_clock", _clock)
                .toString();
    }

    /**
     * Implementation of builder pattern for {@link UnboundedPeriodicSchedule}.
     *
     * @author Christian Briones (cbriones at dropbox dot com)
     */
    public static final class Builder extends OvalBuilder<UnboundedPeriodicSchedule> {
        @Min(1)
        @NotNull
        private Long _periodCount = 1L; // This must be boxed or else codegen fails.
        @NotNull
        private ChronoUnit _period;
        @NotNull
        private Clock _clock = Clock.systemUTC();
        @NotNull
        private Consumer<Long> _overrunReporter = doNothing -> { };

        /**
         * Default constructor.
         */
        public Builder() {
            super(UnboundedPeriodicSchedule::new);
        }

        /**
         * The period with which the schedule fires. Required. Cannot be null.
         *
         * @param period The period.
         * @return This instance of Builder.
         */
        public Builder setPeriod(final ChronoUnit period) {
            _period = period;
            return this;
        }

        /**
         * The number of periods with which the schedule fires. Defaults to 1.
         *
         * @param periodCount The period count.
         * @return This instance of Builder.
         */
        public Builder setPeriodCount(final long periodCount) {
            _periodCount = periodCount;
            return this;
        }

        /**
         * Add a reporter for any interval overruns / skipped periods. Optional.
         * <p>
         * The reporter will receive the number of skipped periods if the caller has
         * given a lastRun time sufficiently far in the past. In order to avoid false
         * positives, the user of the schedule should only poll it once per interval.
         *
         * @param overrunReporter The reporter for any interval overruns.
         * @return This instance of Builder.
         */
        public Builder setOverrunReporter(final Consumer<Long> overrunReporter) {
            _overrunReporter = overrunReporter;
            return this;
        }

        /**
         * The clock to use. Defaults to {@link Clock#systemUTC()}.
         *
         * Should only be used for testing purposes.
         *
         * @param clock The clock to use.
         * @return This instance of Builder.
         */
        @VisibleForTesting
        protected Builder setClock(final Clock clock) {
            _clock = clock;
            return this;
        }
    }
}
