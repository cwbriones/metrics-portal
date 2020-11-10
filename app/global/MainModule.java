/*
 * Copyright 2014 Groupon.com
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
package global;

import actors.JvmMetricsCollector;
import actors.NoopActor;
import akka.actor.ActorRef;
import akka.actor.ActorSystem;
import akka.actor.PoisonPill;
import akka.actor.Props;
import akka.cluster.Cluster;
import akka.cluster.sharding.ClusterSharding;
import akka.cluster.sharding.ClusterShardingSettings;
import akka.cluster.singleton.ClusterSingletonManager;
import akka.cluster.singleton.ClusterSingletonManagerSettings;
import akka.cluster.singleton.ClusterSingletonProxy;
import akka.cluster.singleton.ClusterSingletonProxySettings;
import com.arpnetworking.commons.akka.GuiceActorCreator;
import com.arpnetworking.commons.akka.ParallelLeastShardAllocationStrategy;
import com.arpnetworking.commons.jackson.databind.EnumerationDeserializer;
import com.arpnetworking.commons.jackson.databind.EnumerationDeserializerStrategyUsingToUpperCase;
import com.arpnetworking.commons.jackson.databind.ObjectMapperFactory;
import com.arpnetworking.commons.jackson.databind.module.akka.AkkaModule;
import com.arpnetworking.commons.java.time.TimeAdapters;
import com.arpnetworking.commons.tagger.Tagger;
import com.arpnetworking.kairos.client.KairosDbClient;
import com.arpnetworking.kairos.client.KairosDbClientImpl;
import com.arpnetworking.kairos.client.models.Metric;
import com.arpnetworking.kairos.client.models.MetricsQuery;
import com.arpnetworking.kairos.client.models.SamplingUnit;
import com.arpnetworking.kairos.config.MetricsQueryConfig;
import com.arpnetworking.kairos.config.MetricsQueryConfigImpl;
import com.arpnetworking.kairos.service.KairosDbService;
import com.arpnetworking.kairos.service.KairosDbServiceImpl;
import com.arpnetworking.kairos.service.QueryOrigin;
import com.arpnetworking.metrics.MetricsFactory;
import com.arpnetworking.metrics.Sink;
import com.arpnetworking.metrics.impl.ApacheHttpSink;
import com.arpnetworking.metrics.impl.TsdMetricsFactory;
import com.arpnetworking.metrics.incubator.PeriodicMetrics;
import com.arpnetworking.metrics.incubator.impl.TsdPeriodicMetrics;
import com.arpnetworking.metrics.portal.alerts.AlertExecutionRepository;
import com.arpnetworking.metrics.portal.alerts.AlertRepository;
import com.arpnetworking.metrics.portal.alerts.impl.DatabaseAlertExecutionRepository;
import com.arpnetworking.metrics.portal.alerts.scheduling.AlertExecutionContext;
import com.arpnetworking.metrics.portal.alerts.scheduling.AlertJobRepository;
import com.arpnetworking.metrics.portal.health.ClusterStatusCacheActor;
import com.arpnetworking.metrics.portal.health.HealthProvider;
import com.arpnetworking.metrics.portal.health.StatusActor;
import com.arpnetworking.metrics.portal.hosts.HostRepository;
import com.arpnetworking.metrics.portal.hosts.impl.HostProviderFactory;
import com.arpnetworking.metrics.portal.organizations.OrganizationRepository;
import com.arpnetworking.metrics.portal.query.QueryExecutor;
import com.arpnetworking.metrics.portal.query.QueryExecutorRegistry;
import com.arpnetworking.metrics.portal.query.impl.DelegatingQueryExecutor;
import com.arpnetworking.metrics.portal.reports.ReportExecutionContext;
import com.arpnetworking.metrics.portal.reports.ReportExecutionRepository;
import com.arpnetworking.metrics.portal.reports.ReportRepository;
import com.arpnetworking.metrics.portal.reports.impl.chrome.DefaultDevToolsFactory;
import com.arpnetworking.metrics.portal.reports.impl.chrome.DevToolsFactory;
import com.arpnetworking.metrics.portal.scheduling.DefaultJobRefSerializer;
import com.arpnetworking.metrics.portal.scheduling.JobCoordinator;
import com.arpnetworking.metrics.portal.scheduling.JobExecutorActor;
import com.arpnetworking.metrics.portal.scheduling.JobMessageExtractor;
import com.arpnetworking.metrics.portal.scheduling.JobRefSerializer;
import com.arpnetworking.metrics.portal.scheduling.Schedule;
import com.arpnetworking.metrics.portal.scheduling.impl.UnboundedPeriodicSchedule;
import com.arpnetworking.play.configuration.ConfigurationHelper;
import com.arpnetworking.rollups.ConsistencyChecker;
import com.arpnetworking.rollups.MetricsDiscovery;
import com.arpnetworking.rollups.QueryConsistencyTaskCreator;
import com.arpnetworking.rollups.RollupExecutor;
import com.arpnetworking.rollups.RollupGenerator;
import com.arpnetworking.rollups.RollupManager;
import com.arpnetworking.rollups.RollupPartitioner;
import com.arpnetworking.utility.ConfigTypedProvider;
import com.datastax.driver.core.CodecRegistry;
import com.datastax.driver.extras.codecs.jdk8.InstantCodec;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.module.SimpleModule;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import com.google.inject.AbstractModule;
import com.google.inject.Injector;
import com.google.inject.Provider;
import com.google.inject.Provides;
import com.google.inject.Scopes;
import com.google.inject.name.Names;
import com.typesafe.config.Config;
import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import io.ebean.Ebean;
import io.ebean.EbeanServer;
import models.internal.Features;
import models.internal.MetricsQueryFormat;
import models.internal.impl.DefaultFeatures;
import org.flywaydb.play.PlayInitializer;
import play.Environment;
import play.api.Configuration;
import play.api.db.evolutions.DynamicEvolutions;
import play.api.libs.json.JsonParserSettings;
import play.api.libs.json.jackson.PlayJsonModule;
import play.db.ebean.EbeanConfig;
import play.inject.ApplicationLifecycle;
import play.libs.Json;
import scala.concurrent.duration.Duration;
import scala.concurrent.duration.FiniteDuration;

import java.net.URI;
import java.time.Clock;
import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import javax.inject.Inject;
import javax.inject.Named;
import javax.inject.Singleton;

/**
 * Module that defines the main bindings.
 *
 * @author Brandon Arp (brandon dot arp at inscopemetrics dot io)
 */
public class MainModule extends AbstractModule {

    @Override
    protected void configure() {
        bind(Global.class).asEagerSingleton();
        bind(HealthProvider.class)
                .toProvider(ConfigTypedProvider.provider("http.healthProvider.type"))
                .in(Scopes.SINGLETON);

        // Databases
        // NOTE: These are not singletons because the lifecycle is controlled by
        // Ebean itself and we are just binding the instances by name through Guice
        bind(EbeanServer.class)
                .annotatedWith(Names.named("metrics_portal"))
                .toProvider(MetricsPortalEbeanServerProvider.class);

        bind(EbeanServer.class)
                .annotatedWith(Names.named("metrics_portal_ddl"))
                .toProvider(MetricsPortalDDLEbeanServerProvider.class);

        // Ebean initializes the ServerConfig from outside of Play/Guice so we can't hook in any dependencies without
        // statically injecting them. Construction still happens at inject time, however.
        requestStaticInjection(MetricsPortalServerConfigStartup.class);

        // Repositories
        bind(OrganizationRepository.class)
                .toProvider(OrganizationRepositoryProvider.class)
                .asEagerSingleton();
        bind(HostRepository.class)
                .toProvider(HostRepositoryProvider.class)
                .asEagerSingleton();
        bind(AlertRepository.class)
                .toProvider(AlertRepositoryProvider.class)
                .asEagerSingleton();
        bind(AlertExecutionRepository.class)
                .toProvider(AlertExecutionRepositoryProvider.class)
                .asEagerSingleton();
        bind(ReportRepository.class)
                .toProvider(ReportRepositoryProvider.class)
                .asEagerSingleton();
        bind(ReportExecutionRepository.class)
                .toProvider(ReportExecutionRepositoryProvider.class)
                .asEagerSingleton();

        // Background tasks
        bind(ActorRef.class)
                .annotatedWith(Names.named("JvmMetricsCollector"))
                .toProvider(JvmMetricsCollectorProvider.class)
                .asEagerSingleton();
        bind(ActorRef.class)
                .annotatedWith(Names.named("HostProviderScheduler"))
                .toProvider(HostProviderProvider.class)
                .asEagerSingleton();
        bind(ActorRef.class)
                .annotatedWith(Names.named("ReportJobCoordinator"))
                .toProvider(ReportRepositoryJobCoordinatorProvider.class)
                .asEagerSingleton();
        bind(ActorRef.class)
                .annotatedWith(Names.named("AlertJobCoordinator"))
                .toProvider(AlertRepositoryJobCoordinatorProvider.class)
                .asEagerSingleton();
        bind(ActorRef.class)
                .annotatedWith(Names.named("RollupMetricsDiscovery"))
                .toProvider(RollupMetricsDiscoveryProvider.class)
                .asEagerSingleton();
        bind(ActorRef.class)
                .annotatedWith(Names.named("RollupGenerator"))
                .toProvider(RollupGeneratorProvider.class)
                .asEagerSingleton();
        bind(ActorRef.class)
                .annotatedWith(Names.named("RollupManager"))
                .toProvider(RollupManagerProvider.class)
                .asEagerSingleton();
        bind(RollupPartitioner.class)
                .toInstance(new RollupPartitioner());
        bind(ActorRef.class)
                .annotatedWith(Names.named("RollupExecutor"))
                .toProvider(RollupExecutorProvider.class)
                .asEagerSingleton();
        bind(JobRefSerializer.class).to(DefaultJobRefSerializer.class);

        // Reporting
        bind(ReportExecutionContext.class).asEagerSingleton();

        // Rollups
        bind(MetricsQueryConfig.class).to(MetricsQueryConfigImpl.class).asEagerSingleton();
        bind(ActorRef.class)
                .annotatedWith(Names.named("RollupConsistencyChecker"))
                .toProvider(RollupConsistencyCheckerProvider.class)
                .asEagerSingleton();

        bind(QueryExecutor.class).to(DelegatingQueryExecutor.class).asEagerSingleton();
    }

    @Singleton
    @Provides
    @SuppressFBWarnings("UPM_UNCALLED_PRIVATE_METHOD") // Invoked reflectively by Guice
    private AlertExecutionContext provideAlertExecutionContext(
            final Config config,
            final QueryExecutor executor,
            final PeriodicMetrics metrics
    ) {
        final FiniteDuration interval = ConfigurationHelper.getFiniteDuration(config, "alerting.execution.defaultInterval");
        final java.time.Duration queryOffset = ConfigurationHelper.getJavaDuration(config, "alerting.execution.queryOffset");

        final Schedule defaultAlertSchedule = new UnboundedPeriodicSchedule.Builder()
                .setPeriod(TimeAdapters.toChronoUnit(interval.unit()))
                .setPeriodCount(interval.length())
                .setOverrunReporter(overrunPeriodCount -> {
                    metrics.recordCounter("jobs/executor/overrunPeriods", overrunPeriodCount);
                })
                .build();
        return new AlertExecutionContext(defaultAlertSchedule, executor, queryOffset);
    }

    @Provides
    @Named("RollupReadQueryConsistencyChecker")
    @SuppressFBWarnings("UPM_UNCALLED_PRIVATE_METHOD") // Invoked reflectively by Guice
    private Consumer<MetricsQuery> provideRollupReadQueryConsistencyChecker(
            final Config config,
            @Named("RollupConsistencyChecker") final ActorRef rollupConsistencyChecker,
            final PeriodicMetrics periodicMetrics) {
        final double queryCheckFraction = config.getDouble("rollup.consistency_check.read_fraction");

        return new QueryConsistencyTaskCreator(
                queryCheckFraction,
                rollupConsistencyChecker,
                periodicMetrics);
    }

    @Singleton
    @Named("HostProviderProps")
    @Provides
    @SuppressFBWarnings("UPM_UNCALLED_PRIVATE_METHOD") // Invoked reflectively by Guice
    private Props getHostProviderProps(final HostProviderFactory provider, final Environment environment, final Config config) {
        return provider.create(config.getConfig("hostProvider"), ConfigurationHelper.getType(environment, config, "hostProvider.type"));
    }

    @Provides
    @Singleton
    @SuppressFBWarnings("UPM_UNCALLED_PRIVATE_METHOD") // Invoked reflectively by Guice
    private MetricsFactory getMetricsFactory(final Config configuration, final ObjectMapper mapper) {
        if (configuration.hasPath("metrics.uri")) {
            // Legacy self-instrumentation configuration
            return new TsdMetricsFactory.Builder()
                    .setClusterName(configuration.getString("metrics.cluster"))
                    .setServiceName(configuration.getString("metrics.service"))
                    .setSinks(Collections.singletonList(
                            new ApacheHttpSink.Builder()
                                    .setUri(URI.create(configuration.getString("metrics.uri") + "/metrics/v3/application"))
                                    .build()
                    ))
                    .build();
        }

        // New sinks configuration
        final List<com.arpnetworking.metrics.Sink> monitoringSinks =
                createMonitoringSinks(configuration.getConfigList("metrics.sinks"), mapper);
        return new TsdMetricsFactory.Builder()
                .setClusterName(configuration.getString("metrics.cluster"))
                .setServiceName(configuration.getString("metrics.service"))
                .setSinks(monitoringSinks)
                .build();
    }

    @SuppressFBWarnings("REC_CATCH_EXCEPTION")
    static List<Sink> createMonitoringSinks(
            final List<? extends Config> monitoringSinksConfig,
            final ObjectMapper mapper) {
        // Until we implement the Commons Builder pattern in the metrics client
        // library we need to resort to a more brute-force deserialization
        // style. The benefit of this approach is that it will be forwards
        // compatible with the Commons Builder approach. The drawbacks are
        // the ugly way the configuration is deserialized.
        final List<com.arpnetworking.metrics.Sink> sinks = new ArrayList<>();
        for (final Config sinkConfig : monitoringSinksConfig) {
            try {
                if (sinkConfig.hasPath("class")) {
                    final String classNode = sinkConfig.getString("class");
                    final Class<?> builderClass = Class.forName(classNode + "$Builder");

                    final Object builder = mapper.convertValue(sinkConfig.root().unwrapped(), builderClass);
                    @SuppressWarnings("unchecked")
                    final com.arpnetworking.metrics.Sink sink =
                            (com.arpnetworking.metrics.Sink) builderClass.getMethod("build").invoke(builder);
                    sinks.add(sink);
                }
                // CHECKSTYLE.OFF: IllegalCatch - There are so many ways this hack can fail!
            } catch (final Exception e) {
                // CHECKSTYLE.ON: IllegalCatch
                throw new RuntimeException("Unable to create sink from: " + sinkConfig.toString(), e);
            }
        }
        return sinks;
    }

    @Provides
    @Singleton
    @SuppressFBWarnings("UPM_UNCALLED_PRIVATE_METHOD") // Invoked reflectively by Guice
    private Features getFeatures(final Config configuration) {
        return new DefaultFeatures(configuration);
    }

    @Provides
    @Singleton
    @SuppressFBWarnings(value = "UPM_UNCALLED_PRIVATE_METHOD", justification = "Invoked reflectively by Guice")
    private DevToolsFactory provideChromeDevToolsFactory(final Config config, final ObjectMapper mapper) {
        return new DefaultDevToolsFactory.Builder()
                .setConfig(config.getConfig("chrome"))
                .setObjectMapper(mapper)
                .build();
    }


    @Provides
    @SuppressFBWarnings("UPM_UNCALLED_PRIVATE_METHOD") // Invoked reflectively by Guice
    private CodecRegistry provideCodecRegistry() {
        final CodecRegistry registry = CodecRegistry.DEFAULT_INSTANCE;
        registry.register(InstantCodec.instance);
        return registry;
    }

    @Provides
    @Singleton
    @SuppressFBWarnings("UPM_UNCALLED_PRIVATE_METHOD") // Invoked reflectively by Guice
    private KairosDbClient provideKairosDbClient(
            final ActorSystem actorSystem,
            final ObjectMapper mapper,
            final Config configuration,
            final MetricsFactory metricsFactory
    ) {
        return new KairosDbClientImpl.Builder()
                .setActorSystem(actorSystem)
                .setMapper(mapper)
                .setUri(URI.create(configuration.getString("kairosdb.uri")))
                .setReadTimeout(ConfigurationHelper.getFiniteDuration(configuration, "kairosdb.timeout"))
                .setMetricsFactory(metricsFactory)
                .build();
    }

    @Provides
    @Singleton
    @SuppressFBWarnings("UPM_UNCALLED_PRIVATE_METHOD") // Invoked reflectively by Guice
    private QueryExecutorRegistry provideQueryExecutorRegistry(
            final Config configuration,
            final Injector injector,
            final Environment environment) {
        final ImmutableMap.Builder<MetricsQueryFormat, QueryExecutor> registryMapBuilder = ImmutableMap.builder();
        final Config executorsConfig = configuration.getConfig("query.executors");
        final Set<String> keys = executorsConfig.root().keySet();
        for (final String key: keys) {
            final Config subconfig = executorsConfig.getConfig(key);
            final QueryExecutor executor = ConfigurationHelper.toInstance(injector, environment, subconfig);

            final MetricsQueryFormat format = MetricsQueryFormat.valueOf(key);
            registryMapBuilder.put(format, executor);
        }
        return new QueryExecutorRegistry.Builder()
                .setExecutors(registryMapBuilder.build())
                .build();
    }

    //Note: This is essentially the same as Play's ObjectMapperModule, but uses the Commons ObjectMapperFactory
    //  instance as the base
    @Singleton
    @Provides
    @SuppressFBWarnings("UPM_UNCALLED_PRIVATE_METHOD") // Invoked reflectively by Guice
    private ObjectMapper provideObjectMapper(
            final ApplicationLifecycle lifecycle,
            final ActorSystem actorSystem) {
        final SimpleModule customModule = new SimpleModule();
        customModule.addDeserializer(
                com.arpnetworking.kairos.client.models.TimeUnit.class,
                EnumerationDeserializer.newInstance(
                        com.arpnetworking.kairos.client.models.TimeUnit.class,
                        EnumerationDeserializerStrategyUsingToUpperCase.newInstance()));
        customModule.addDeserializer(
                SamplingUnit.class,
                EnumerationDeserializer.newInstance(
                        SamplingUnit.class,
                        EnumerationDeserializerStrategyUsingToUpperCase.newInstance()));
        customModule.addDeserializer(
                Metric.Order.class,
                EnumerationDeserializer.newInstance(
                        Metric.Order.class,
                        EnumerationDeserializerStrategyUsingToUpperCase.newInstance()));
        final ObjectMapper objectMapper = ObjectMapperFactory.createInstance();
        objectMapper.registerModule(new PlayJsonModule(JsonParserSettings.apply()));
        objectMapper.registerModule(new AkkaModule(actorSystem));
        objectMapper.registerModule(customModule);

        Json.setObjectMapper(objectMapper);
        lifecycle.addStopHook(() -> {
            Json.setObjectMapper(null);
            return CompletableFuture.completedFuture(null);
        });

        return objectMapper;
    }

    @Provides
    @Singleton
    @SuppressFBWarnings("UPM_UNCALLED_PRIVATE_METHOD") // Invoked reflectively by Guice
    private Clock provideClock() {
        return Clock.systemUTC();
    }

    @Provides
    @SuppressFBWarnings("UPM_UNCALLED_PRIVATE_METHOD") // Invoked reflectively by Guice
    private JobMessageExtractor provideJobMessageExtractor(final JobRefSerializer serializer) {
        // This binding exists to avoid needing a generic token for Serializer<JobRef>
        return new JobMessageExtractor(serializer);
    }

    @Provides
    @Singleton
    @Named("job-execution-shard-region")
    @SuppressFBWarnings("UPM_UNCALLED_PRIVATE_METHOD") // Invoked reflectively by Guice
    private ActorRef provideJobExecutorShardRegion(
            final ActorSystem system,
            final Injector injector,
            final JobMessageExtractor extractor,
            final Clock clock,
            final PeriodicMetrics periodicMetrics,
            final JobRefSerializer refSerializer) {
        final ClusterSharding clusterSharding = ClusterSharding.get(system);
        return clusterSharding.start(
                "JobExecutor",
                JobExecutorActor.props(injector, clock, periodicMetrics, refSerializer),
                ClusterShardingSettings.create(system).withRememberEntities(true),
                extractor,
                new ParallelLeastShardAllocationStrategy(
                        100,
                        3,
                        Optional.of(system.actorSelection("/user/cluster-status"))),
                PoisonPill.getInstance());
    }

    @Provides
    @Singleton
    @SuppressFBWarnings("UPM_UNCALLED_PRIVATE_METHOD") // Invoked reflectively by Guice
    private PeriodicMetrics providePeriodicMetrics(final MetricsFactory metricsFactory, final ActorSystem actorSystem) {
        final TsdPeriodicMetrics periodicMetrics = new TsdPeriodicMetrics.Builder()
                .setMetricsFactory(metricsFactory)
                .setPollingExecutor(actorSystem.dispatcher())
                .build();
        final FiniteDuration delay = FiniteDuration.apply(1, TimeUnit.SECONDS);
        actorSystem.scheduler().schedule(delay, delay, periodicMetrics, actorSystem.dispatcher());
        return periodicMetrics;
    }

    @Provides
    @com.google.inject.Singleton
    @com.google.inject.name.Named("status")
    @SuppressFBWarnings("UPM_UNCALLED_PRIVATE_METHOD") // Invoked reflectively by Guice
    private ActorRef provideStatusActor(
            final ActorSystem system,
            final MetricsFactory metricsFactory) {
        final Cluster cluster = Cluster.get(system);
        final ActorRef clusterStatusCache = system.actorOf(ClusterStatusCacheActor.props(cluster, metricsFactory), "cluster-status");
        return system.actorOf(StatusActor.props(cluster, clusterStatusCache), "status");
    }

    @Provides
    @SuppressFBWarnings("UPM_UNCALLED_PRIVATE_METHOD") // Invoked reflectively by Guice
    private DatabaseAlertExecutionRepository provideDatabaseAlertExecutionRepository(
            final Config config,
            final PeriodicMetrics periodicMetrics,
            final ActorSystem actorSystem,
            @Named("metrics_portal") final EbeanServer portalServer,
            @Named("metrics_portal_ddl") final EbeanServer ddlServer
    ) {
        final Config partitionConfig = config.getObject("alertExecutionRepository.partitionManager").toConfig();

        final int maxLookAhead = partitionConfig.getInt("lookahead");
        final Duration offset = ConfigurationHelper.getFiniteDuration(partitionConfig, "offset");
        return new DatabaseAlertExecutionRepository(
            portalServer,
            ddlServer,
            actorSystem,
            periodicMetrics,
            java.time.Duration.ofSeconds(offset.toSeconds()),
            maxLookAhead
        );
    }

    @Singleton
    @Provides
    @SuppressFBWarnings("UPM_UNCALLED_PRIVATE_METHOD") // Invoked reflectively by Guice
    private KairosDbService provideKairosDbService(
            final KairosDbClient kairosDbClient,
            final MetricsQueryConfig metricsQueryConfig,
            final MetricsFactory metricsFactory,
            @Named("RollupReadQueryConsistencyChecker") final Consumer<MetricsQuery> rewrittenQueryConsumer,
            final Config configuration
    ) {
        final ImmutableSet<String> excludedTagNames = ImmutableSet.copyOf(
                configuration.getStringList("kairosdb.proxy.excludedTagNames"));

        final ImmutableSet<QueryOrigin> rollupEnabledOrigins =
                configuration.getStringList("kairosdb.proxy.rollups.enabledOrigins")
                    .stream()
                    .map(QueryOrigin::valueOf)
                    .collect(ImmutableSet.toImmutableSet());

        return new KairosDbServiceImpl.Builder()
                .setKairosDbClient(kairosDbClient)
                .setMetricsFactory(metricsFactory)
                .setExcludedTagNames(excludedTagNames)
                .setMetricsQueryConfig(metricsQueryConfig)
                .setRewrittenQueryConsumer(rewrittenQueryConsumer)
                .setRollupEnabledOrigins(EnumSet.copyOf(rollupEnabledOrigins))
                .build();
    }

    @Named("RollupGeneratorTagger")
    @Provides
    @SuppressFBWarnings("UPM_UNCALLED_PRIVATE_METHOD") // Invoked reflectively by Guice
    private Tagger provideRollupGeneratorTagger(
            final Injector injector,
            final Environment environment,
            final Config config
    ) {
        final Config taggerConfig = config.getConfig("rollup.generator.tagger");
        return ConfigurationHelper.toInstanceMapped(injector, environment, config);
    }

    private static final class MetricsPortalEbeanServerProvider implements Provider<EbeanServer> {
        @Inject
        MetricsPortalEbeanServerProvider(
                final Configuration configuration,
                final DynamicEvolutions dynamicEvolutions,
                final EbeanConfig ebeanConfig,
                final PlayInitializer flywayInitializer) {
            // Constructor arguments injected for dependency resolution only
            // e.g. requiring migrations to run
        }

        @Override
        public EbeanServer get() {
            return Ebean.getServer("metrics_portal");
        }
    }

    private static final class MetricsPortalDDLEbeanServerProvider implements Provider<EbeanServer> {
        @Inject
        MetricsPortalDDLEbeanServerProvider(
                final Configuration configuration,
                final DynamicEvolutions dynamicEvolutions,
                final EbeanConfig ebeanConfig,
                final PlayInitializer flywayInitializer) {
            // Constructor arguments injected for dependency resolution only
            // e.g. requiring migrations to run
        }

        @Override
        public EbeanServer get() {
            return Ebean.getServer("metrics_portal_ddl");
        }
    }

    private static final class OrganizationRepositoryProvider implements Provider<OrganizationRepository> {
        @Inject
        OrganizationRepositoryProvider(
                final Injector injector,
                final Environment environment,
                final Config configuration,
                final ApplicationLifecycle lifecycle) {
            _injector = injector;
            _environment = environment;
            _configuration = configuration;
            _lifecycle = lifecycle;
        }

        @Override
        public OrganizationRepository get() {
            final OrganizationRepository organizationRepository = _injector.getInstance(
                    ConfigurationHelper.<OrganizationRepository>getType(_environment, _configuration, "organizationRepository.type"));
            organizationRepository.open();
            _lifecycle.addStopHook(
                    () -> {
                        organizationRepository.close();
                        return CompletableFuture.completedFuture(null);
                    });
            return organizationRepository;
        }

        private final Injector _injector;
        private final Environment _environment;
        private final Config _configuration;
        private final ApplicationLifecycle _lifecycle;
    }

    private static final class HostRepositoryProvider implements Provider<HostRepository> {

        @Inject
        HostRepositoryProvider(
                final Injector injector,
                final Environment environment,
                final Config configuration,
                final ApplicationLifecycle lifecycle) {
            _injector = injector;
            _environment = environment;
            _configuration = configuration;
            _lifecycle = lifecycle;
        }

        @Override
        public HostRepository get() {
            final HostRepository hostRepository = _injector.getInstance(
                    ConfigurationHelper.<HostRepository>getType(_environment, _configuration, "hostRepository.type"));
            hostRepository.open();
            _lifecycle.addStopHook(
                    () -> {
                        hostRepository.close();
                        return CompletableFuture.completedFuture(null);
                    });
            return hostRepository;
        }

        private final Injector _injector;
        private final Environment _environment;
        private final Config _configuration;
        private final ApplicationLifecycle _lifecycle;
    }

    private static final class AlertRepositoryProvider implements Provider<AlertRepository> {

        @Inject
        AlertRepositoryProvider(
                final Injector injector,
                final Environment environment,
                final Config configuration,
                final ApplicationLifecycle lifecycle) {
            _injector = injector;
            _environment = environment;
            _configuration = configuration;
            _lifecycle = lifecycle;
        }

        @Override
        public AlertRepository get() {
            final Config config = _configuration.getConfig("alertRepository");
            final AlertRepository alertRepository = ConfigurationHelper.toInstance(_injector, _environment, config);
            alertRepository.open();
            _lifecycle.addStopHook(
                    () -> {
                        alertRepository.close();
                        return CompletableFuture.completedFuture(null);
                    });
            return alertRepository;
        }

        private final Injector _injector;
        private final Environment _environment;
        private final Config _configuration;
        private final ApplicationLifecycle _lifecycle;
    }

    private static final class AlertExecutionRepositoryProvider implements Provider<AlertExecutionRepository> {
        @Inject
        AlertExecutionRepositoryProvider(
                final Injector injector,
                final Environment environment,
                final Config configuration,
                final ApplicationLifecycle lifecycle
        ) {
            _injector = injector;
            _environment = environment;
            _configuration = configuration;
            _lifecycle = lifecycle;
        }

        @Override
        public AlertExecutionRepository get() {
            final AlertExecutionRepository executionRepository = _injector.getInstance(
                    ConfigurationHelper.<AlertExecutionRepository>getType(_environment, _configuration, "alertExecutionRepository.type"));
            executionRepository.open();
            _lifecycle.addStopHook(
                    () -> {
                        executionRepository.close();
                        return CompletableFuture.completedFuture(null);
                    });
            return executionRepository;
        }

        private final Injector _injector;
        private final Environment _environment;
        private final Config _configuration;
        private final ApplicationLifecycle _lifecycle;
    }

    private static final class ReportRepositoryProvider implements Provider<ReportRepository> {
        @Inject
        ReportRepositoryProvider(
                final Injector injector,
                final Environment environment,
                final Config configuration,
                final ApplicationLifecycle lifecycle) {
            _injector = injector;
            _environment = environment;
            _configuration = configuration;
            _lifecycle = lifecycle;
        }

        @Override
        public ReportRepository get() {
            final ReportRepository reportRepository = _injector.getInstance(
                    ConfigurationHelper.<ReportRepository>getType(_environment, _configuration, "reportRepository.type"));
            reportRepository.open();
            _lifecycle.addStopHook(
                    () -> {
                        reportRepository.close();
                        return CompletableFuture.completedFuture(null);
                    });
            return reportRepository;
        }

        private final Injector _injector;
        private final Environment _environment;
        private final Config _configuration;
        private final ApplicationLifecycle _lifecycle;
    }

    private static final class ReportExecutionRepositoryProvider implements Provider<ReportExecutionRepository> {
        @Inject
        ReportExecutionRepositoryProvider(
                final Injector injector,
                final Environment environment,
                final Config configuration,
                final ApplicationLifecycle lifecycle) {
            _injector = injector;
            _environment = environment;
            _configuration = configuration;
            _lifecycle = lifecycle;
        }

        @Override
        public ReportExecutionRepository get() {
            final ReportExecutionRepository executionRepository = _injector.getInstance(
                    ConfigurationHelper.<ReportExecutionRepository>getType(_environment, _configuration, "reportExecutionRepository.type"));
            executionRepository.open();
            _lifecycle.addStopHook(
                    () -> {
                        executionRepository.close();
                        return CompletableFuture.completedFuture(null);
                    });
            return executionRepository;
        }

        private final Injector _injector;
        private final Environment _environment;
        private final Config _configuration;
        private final ApplicationLifecycle _lifecycle;
    }

    private static final class HostProviderProvider implements Provider<ActorRef> {
        @Inject
        HostProviderProvider(
                final ActorSystem system,
                @Named("HostProviderProps") final Props hostProviderProps) {
            _system = system;
            _hostProviderProps = hostProviderProps;
        }

        @Override
        public ActorRef get() {
            final Cluster cluster = Cluster.get(_system);
            // Start a singleton instance of the scheduler on a "host_indexer" node in the cluster.
            if (cluster.selfRoles().contains(INDEXER_ROLE)) {
                final ActorRef managerRef = _system.actorOf(ClusterSingletonManager.props(
                        _hostProviderProps,
                        PoisonPill.getInstance(),
                        ClusterSingletonManagerSettings.create(_system).withRole(INDEXER_ROLE)),
                        "host-provider-scheduler");
                return _system.actorOf(ClusterSingletonProxy.props(
                        managerRef.path().toStringWithoutAddress(),
                        ClusterSingletonProxySettings.create(_system)
                ));
            }
            return null;
        }

        private final ActorSystem _system;
        private final Props _hostProviderProps;

        private static final String INDEXER_ROLE = "host_indexer";
    }

    private static final class ReportRepositoryJobCoordinatorProvider implements Provider<ActorRef> {
        @Inject
        ReportRepositoryJobCoordinatorProvider(
                final ActorSystem system,
                final Injector injector,
                final OrganizationRepository organizationRepository,
                @Named("job-execution-shard-region")
                final ActorRef executorRegion,
                final PeriodicMetrics periodicMetrics) {
            _system = system;
            _injector = injector;
            _organizationRepository = organizationRepository;
            _executorRegion = executorRegion;
            _periodicMetrics = periodicMetrics;
        }

        @Override
        public ActorRef get() {
            final Cluster cluster = Cluster.get(_system);
            // Start a singleton instance of the scheduler on a "host_indexer" node in the cluster.
            if (cluster.selfRoles().contains(ANTI_ENTROPY_ROLE)) {
                final ActorRef managerRef = _system.actorOf(ClusterSingletonManager.props(
                        JobCoordinator.props(_injector,
                                ReportRepository.class,
                                ReportExecutionRepository.class,
                                _organizationRepository,
                                _executorRegion,
                                _periodicMetrics),
                        PoisonPill.getInstance(),
                        ClusterSingletonManagerSettings.create(_system).withRole(ANTI_ENTROPY_ROLE)),
                        "ReportJobCoordinator");
                return _system.actorOf(ClusterSingletonProxy.props(
                        managerRef.path().toStringWithoutAddress(),
                        ClusterSingletonProxySettings.create(_system)
                ));
            }
            return null;
        }

        private final ActorSystem _system;
        private final Injector _injector;
        private final OrganizationRepository _organizationRepository;
        private final ActorRef _executorRegion;
        private final PeriodicMetrics _periodicMetrics;

        private static final String ANTI_ENTROPY_ROLE = "report_repository_anti_entropy";
    }

    private static final class AlertRepositoryJobCoordinatorProvider implements Provider<ActorRef> {
        @Inject
        AlertRepositoryJobCoordinatorProvider(
                final ActorSystem system,
                final Injector injector,
                final OrganizationRepository organizationRepository,
                @Named("job-execution-shard-region")
                final ActorRef executorRegion,
                final PeriodicMetrics periodicMetrics) {
            _system = system;
            _injector = injector;
            _organizationRepository = organizationRepository;
            _executorRegion = executorRegion;
            _periodicMetrics = periodicMetrics;
        }

        @Override
        public ActorRef get() {
            final Cluster cluster = Cluster.get(_system);
            if (cluster.selfRoles().contains(ANTI_ENTROPY_ROLE)) {
                final ActorRef managerRef = _system.actorOf(ClusterSingletonManager.props(
                        JobCoordinator.props(_injector,
                                AlertJobRepository.class,
                                AlertExecutionRepository.class,
                                _organizationRepository,
                                _executorRegion,
                                _periodicMetrics),
                        PoisonPill.getInstance(),
                        ClusterSingletonManagerSettings.create(_system).withRole(ANTI_ENTROPY_ROLE)),
                        "AlertJobCoordinator");
                return _system.actorOf(ClusterSingletonProxy.props(
                        managerRef.path().toStringWithoutAddress(),
                        ClusterSingletonProxySettings.create(_system)
                ));
            }
            return null;
        }

        private final ActorSystem _system;
        private final Injector _injector;
        private final OrganizationRepository _organizationRepository;
        private final ActorRef _executorRegion;
        private final PeriodicMetrics _periodicMetrics;

        private static final String ANTI_ENTROPY_ROLE = "alert_repository_anti_entropy";
    }

    private static final class RollupGeneratorProvider implements Provider<ActorRef> {
        @Inject
        RollupGeneratorProvider(
                final Injector injector,
                final ActorSystem system,
                final Config configuration,
                final Features features) {
            _injector = injector;
            _system = system;
            _configuration = configuration;
            _enabled = features.isRollupsEnabled();
        }

        @Override
        public ActorRef get() {
            final Cluster cluster = Cluster.get(_system);
            final int actorCount = _configuration.getInt("rollup.generator.count");
            if (_enabled && cluster.selfRoles().contains(RollupMetricsDiscoveryProvider.ROLLUP_METRICS_DISCOVERY_ROLE)) {
                for (int i = 0; i < actorCount; i++) {
                    _system.actorOf(GuiceActorCreator.props(_injector, RollupGenerator.class));
                }
            }
            return null;
        }

        private final Injector _injector;
        private final ActorSystem _system;
        private final Config _configuration;
        private final boolean _enabled;
    }

    private static final class RollupMetricsDiscoveryProvider implements Provider<ActorRef> {
        @Inject
        RollupMetricsDiscoveryProvider(
                final Injector injector,
                final ActorSystem system,
                final Features features) {
            _enabled = features.isRollupsEnabled();
            _injector = injector;
            _system = system;
        }

        @Override
        public ActorRef get() {
            final Cluster cluster = Cluster.get(_system);
            if (_enabled && cluster.selfRoles().contains(ROLLUP_METRICS_DISCOVERY_ROLE)) {
                final ActorRef manager = _system.actorOf(ClusterSingletonManager.props(
                        GuiceActorCreator.props(_injector, MetricsDiscovery.class),
                        PoisonPill.getInstance(),
                        ClusterSingletonManagerSettings.create(_system).withRole(ROLLUP_METRICS_DISCOVERY_ROLE)),
                        "rollup-metrics-discovery"
                );
                return _system.actorOf(ClusterSingletonProxy.props(
                        manager.path().toStringWithoutAddress(),
                        ClusterSingletonProxySettings.create(_system)));
            }
            return _system.actorOf(Props.create(NoopActor.class));
        }

        private final boolean _enabled;
        private final Injector _injector;
        private final ActorSystem _system;

        static final String ROLLUP_METRICS_DISCOVERY_ROLE = "rollup_metrics_discovery";
    }

    private static final class JvmMetricsCollectorProvider implements Provider<ActorRef> {
        @Inject
        JvmMetricsCollectorProvider(final Injector injector, final ActorSystem system) {
            _injector = injector;
            _system = system;
        }

        @Override
        public ActorRef get() {
            return _system.actorOf(GuiceActorCreator.props(_injector, JvmMetricsCollector.class));
        }

        private final Injector _injector;
        private final ActorSystem _system;
    }

    private static final class RollupManagerProvider implements Provider<ActorRef> {
        private final boolean _enabled;
        private final ActorSystem _system;
        private final PeriodicMetrics _periodicMetrics;
        private final MetricsFactory _metricsFactory;
        private final RollupPartitioner _partitioner;
        private final ActorRef _consistencyChecker;
        private final Config _config;
        static final String ROLLUP_MANAGER_ROLE = "rollup_manager";

        @Inject
        RollupManagerProvider(
                final ActorSystem system,
                final PeriodicMetrics periodicMetrics,
                final MetricsFactory metricsFactory,
                final RollupPartitioner partitioner,
                @Named("RollupConsistencyChecker") final ActorRef consistencyChecker,
                final Config config,
                final Features features
        ) {
            _enabled = features.isRollupsEnabled();
            _system = system;
            _periodicMetrics = periodicMetrics;
            _metricsFactory = metricsFactory;
            _partitioner = partitioner;
            _consistencyChecker = consistencyChecker;
            _config = config;
        }

        @Override
        public ActorRef get() {
            final Cluster cluster = Cluster.get(_system);
            if (_enabled && cluster.selfRoles().contains(ROLLUP_MANAGER_ROLE)) {
                final ActorRef manager = _system.actorOf(ClusterSingletonManager.props(
                        RollupManager.props(
                                _periodicMetrics,
                                _metricsFactory,
                                _partitioner,
                                _consistencyChecker,
                                _config.getDouble("rollup.manager.consistency_check_fraction_of_writes")
                        ),
                        PoisonPill.getInstance(),
                        ClusterSingletonManagerSettings.create(_system).withRole(ROLLUP_MANAGER_ROLE)),
                        "rollup-manager"
                );
                return _system.actorOf(ClusterSingletonProxy.props(
                        manager.path().toStringWithoutAddress(),
                        ClusterSingletonProxySettings.create(_system)));
            }
            return _system.actorOf(Props.create(NoopActor.class));
        }
    }

    private static final class RollupExecutorProvider implements Provider<ActorRef> {
        @Inject
        RollupExecutorProvider(
                final Injector injector,
                final ActorSystem system,
                final Config configuration,
                final Features features) {
            _injector = injector;
            _system = system;
            _configuration = configuration;
            _enabled = features.isRollupsEnabled();
        }

        @Override
        public ActorRef get() {
            final int actorCount = _configuration.getInt("rollup.executor.count");
            if (_enabled) {
                for (int i = 0; i < actorCount; i++) {
                    _system.actorOf(GuiceActorCreator.props(_injector, RollupExecutor.class));
                }
            }
            return null;
        }

        private final Injector _injector;
        private final ActorSystem _system;
        private final Config _configuration;
        private final boolean _enabled;
    }

    private static final class RollupConsistencyCheckerProvider implements Provider<ActorRef> {
        @Inject
        RollupConsistencyCheckerProvider(
                final ActorSystem system,
                final KairosDbClient kairosDbClient,
                final MetricsFactory metricsFactory,
                final PeriodicMetrics periodicMetrics,
                final Config configuration
        ) {
            _system = system;
            _kairosDbClient = kairosDbClient;
            _metricsFactory = metricsFactory;
            _periodicMetrics = periodicMetrics;
            _configuration = configuration;
        }

        @Override
        public ActorRef get() {
            final int maxConcurrentRequests = _configuration.getInt("rollup.consistency_checker.max_concurrent_requests");
            final int bufferSize = _configuration.getInt("rollup.consistency_checker.buffer_size");
            return _system.actorOf(ConsistencyChecker.props(
                        _kairosDbClient,
                        _metricsFactory,
                        _periodicMetrics,
                        maxConcurrentRequests,
                        bufferSize
            ));
        }

        private final ActorSystem _system;
        private final KairosDbClient _kairosDbClient;
        private final MetricsFactory _metricsFactory;
        private final PeriodicMetrics _periodicMetrics;
        private final Config _configuration;
    }

}
