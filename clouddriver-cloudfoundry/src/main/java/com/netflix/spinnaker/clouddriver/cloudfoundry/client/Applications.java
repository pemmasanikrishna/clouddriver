/*
 * Copyright 2018 Pivotal, Inc.
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

package com.netflix.spinnaker.clouddriver.cloudfoundry.client;

import static com.netflix.spinnaker.clouddriver.cloudfoundry.client.CloudFoundryClientUtils.*;
import static com.netflix.spinnaker.clouddriver.cloudfoundry.client.model.ErrorDescription.Code.NOT_AUTHORIZED;
import static java.util.Arrays.asList;
import static java.util.Collections.*;
import static java.util.Optional.ofNullable;
import static java.util.stream.Collectors.toList;
import static java.util.stream.Collectors.toSet;

import com.google.common.cache.CacheBuilder;
import com.google.common.cache.CacheLoader;
import com.google.common.cache.LoadingCache;
import com.netflix.frigga.Names;
import com.netflix.spinnaker.clouddriver.cloudfoundry.client.api.ApplicationService;
import com.netflix.spinnaker.clouddriver.cloudfoundry.client.model.v2.ApplicationEnv;
import com.netflix.spinnaker.clouddriver.cloudfoundry.client.model.v2.MapRoute;
import com.netflix.spinnaker.clouddriver.cloudfoundry.client.model.v2.Resource;
import com.netflix.spinnaker.clouddriver.cloudfoundry.client.model.v2.ServiceBinding;
import com.netflix.spinnaker.clouddriver.cloudfoundry.client.model.v3.*;
import com.netflix.spinnaker.clouddriver.cloudfoundry.client.model.v3.Package;
import com.netflix.spinnaker.clouddriver.cloudfoundry.client.model.v3.Process;
import com.netflix.spinnaker.clouddriver.cloudfoundry.config.CloudFoundryConfigurationProperties;
import com.netflix.spinnaker.clouddriver.cloudfoundry.model.*;
import com.netflix.spinnaker.clouddriver.helpers.AbstractServerGroupNameResolver;
import com.netflix.spinnaker.clouddriver.model.HealthState;
import java.io.File;
import java.io.InputStream;
import java.util.*;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ForkJoinPool;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import lombok.extern.slf4j.Slf4j;
import okhttp3.MediaType;
import okhttp3.MultipartBody;
import okhttp3.RequestBody;
import okhttp3.ResponseBody;
import org.apache.commons.lang3.StringUtils;

@Slf4j
public class Applications {
  private final String account;
  private final String appsManagerUri;
  private final String metricsUri;
  private final ApplicationService api;
  private final Spaces spaces;
  private final Processes processes;
  private final Integer resultsPerPage;
  private final boolean onlySpinnakerManaged;
  private final ForkJoinPool forkJoinPool;
  private final LoadingCache<String, CloudFoundryServerGroup> serverGroupCache;

  public Applications(
      String account,
      String appsManagerUri,
      String metricsUri,
      ApplicationService api,
      Spaces spaces,
      Processes processes,
      Integer resultsPerPage,
      boolean onlySpinnakerManaged,
      ForkJoinPool forkJoinPool,
      CloudFoundryConfigurationProperties.LocalCacheConfig localCacheConfig) {
    this.account = account;
    this.appsManagerUri = appsManagerUri;
    this.metricsUri = metricsUri;
    this.api = api;
    this.spaces = spaces;
    this.processes = processes;
    this.resultsPerPage = resultsPerPage;
    this.onlySpinnakerManaged = onlySpinnakerManaged;
    this.forkJoinPool = forkJoinPool;

    CacheBuilder<Object, Object> builder = CacheBuilder.newBuilder();
    if (localCacheConfig.getApplicationsAccessExpirySeconds() >= 0) {
      builder.expireAfterAccess(
          localCacheConfig.getApplicationsAccessExpirySeconds(), TimeUnit.SECONDS);
    }
    if (localCacheConfig.getApplicationsWriteExpirySeconds() >= 0) {
      builder.expireAfterWrite(
          localCacheConfig.getApplicationsWriteExpirySeconds(), TimeUnit.SECONDS);
    }

    this.serverGroupCache =
        builder.build(
            new CacheLoader<>() {
              @Override
              public CloudFoundryServerGroup load(@Nonnull String guid)
                  throws ResourceNotFoundException {
                return safelyCall(() -> api.findById(guid))
                    .map(Applications.this::map)
                    .flatMap(sg -> sg)
                    .orElseThrow(ResourceNotFoundException::new);
              }
            });
  }

  @Nullable
  public CloudFoundryServerGroup findById(String guid) {
    try {
      return serverGroupCache.get(guid);
    } catch (ExecutionException e) {
      if (e.getCause() instanceof ResourceNotFoundException) {
        return null;
      }
      throw new CloudFoundryApiException(e.getCause(), "Unable to find server group by id");
    }
  }

  public List<CloudFoundryApplication> all(List<String> spaceGuids) {
    log.debug("Listing all applications from account {}", this.account);

    String spaceGuidsQ =
        spaceGuids == null || spaceGuids.isEmpty() ? null : String.join(",", spaceGuids);

    List<Application> newCloudFoundryAppList =
        collectPages("applications", page -> api.all(page, resultsPerPage, null, spaceGuidsQ));

    log.debug(
        "Fetched {} total apps from foundation account {}",
        newCloudFoundryAppList.size(),
        this.account);

    List<String> availableAppIds =
        newCloudFoundryAppList.stream().map(Application::getGuid).collect(toList());

    long invalidatedServerGroups =
        serverGroupCache.asMap().keySet().parallelStream()
            .filter(appGuid -> !availableAppIds.contains(appGuid))
            .peek(appGuid -> log.trace("Evicting the following SG with id '{}'", appGuid))
            .peek(serverGroupCache::invalidate)
            .count();

    log.debug(
        "Evicted {} serverGroups from the cache that aren't on the '{}' foundation anymore",
        invalidatedServerGroups,
        this.account);

    // if the update time doesn't match then we need to update the cache
    // if the app is not found in the cache we need to process with `map` and update the cache
    try {
      forkJoinPool
          .submit(
              () ->
                  newCloudFoundryAppList.parallelStream()
                      .filter(
                          app -> {
                            CloudFoundryServerGroup cachedApp = findById(app.getGuid());
                            if (cachedApp != null) {
                              if (!cachedApp
                                  .getUpdatedTime()
                                  .equals(app.getUpdatedAt().toInstant().toEpochMilli())) {
                                log.trace(
                                    "App '{}' cached version is out of date on foundation '{}'",
                                    app.getName(),
                                    this.account);
                                return true;
                              } else {
                                return false;
                              }
                            } else {
                              log.trace(
                                  "App '{}' not found in cache for foundation '{}'",
                                  app.getName(),
                                  this.account);
                              return true;
                            }
                          })
                      .map(this::map)
                      .filter(Optional::isPresent)
                      .map(Optional::get)
                      .forEach(sg -> serverGroupCache.put(sg.getId(), sg)))
          .get();

      forkJoinPool
          .submit(
              () ->
                  // execute health check on instances, set number of available instances and health
                  // status
                  newCloudFoundryAppList.parallelStream()
                      .forEach(
                          a ->
                              serverGroupCache.put(
                                  a.getGuid(), checkHealthStatus(findById(a.getGuid()), a))))
          .get();
    } catch (Exception e) {
      throw new RuntimeException(e);
    }

    Map<String, Set<CloudFoundryServerGroup>> serverGroupsByClusters = new HashMap<>();
    Map<String, Set<String>> clustersByApps = new HashMap<>();

    for (CloudFoundryServerGroup serverGroup : serverGroupCache.asMap().values()) {
      Names names = Names.parseName(serverGroup.getName());

      if (onlySpinnakerManaged && names.getSequence() == null) {
        log.debug(
            "Skipping app '{}' from foundation '{}' because onlySpinnakerManaged is true and it has no version.",
            serverGroup.getName(),
            this.account);
        continue;
      }

      if (names.getCluster() == null) {
        log.debug(
            "Skipping app '{}' from foundation '{}' because the name isn't following the frigga naming schema.",
            serverGroup.getName(),
            this.account);
        continue;
      }
      serverGroupsByClusters
          .computeIfAbsent(names.getCluster(), clusterName -> new HashSet<>())
          .add(serverGroup);
      clustersByApps
          .computeIfAbsent(names.getApp(), appName -> new HashSet<>())
          .add(names.getCluster());
    }

    return clustersByApps.entrySet().stream()
        .map(
            clustersByApp ->
                CloudFoundryApplication.builder()
                    .name(clustersByApp.getKey())
                    .clusters(
                        clustersByApp.getValue().stream()
                            .map(
                                clusterName ->
                                    CloudFoundryCluster.builder()
                                        .accountName(account)
                                        .name(clusterName)
                                        .serverGroups(serverGroupsByClusters.get(clusterName))
                                        .build())
                            .collect(toSet()))
                    .build())
        .collect(toList());
  }

  @Nullable
  public CloudFoundryServerGroup findServerGroupByNameAndSpaceId(String name, String spaceId) {
    Optional<CloudFoundryServerGroup> result =
        safelyCall(() -> api.all(null, 1, singletonList(name), spaceId))
            .flatMap(
                page ->
                    page.getResources().stream()
                        .findFirst()
                        .map(this::map)
                        .orElseThrow(
                            () ->
                                new CloudFoundryApiException(
                                    "Not authorized error retrieving details for this Server Group")));
    result.ifPresent(sg -> serverGroupCache.put(sg.getId(), sg));
    return result.orElse(null);
  }

  @Nullable
  public String findServerGroupId(String name, String spaceId) {
    return serverGroupCache.asMap().values().stream()
        .filter(
            serverGroup ->
                serverGroup.getName().equalsIgnoreCase(name)
                    && serverGroup.getSpace().getId().equals(spaceId))
        .findFirst()
        .map(CloudFoundryServerGroup::getId)
        .orElseGet(
            () ->
                safelyCall(() -> api.all(null, 1, singletonList(name), spaceId))
                    .flatMap(
                        page ->
                            page.getResources().stream()
                                .findFirst()
                                .map(this::map)
                                .filter(Optional::isPresent)
                                .map(Optional::get)
                                .map(
                                    serverGroup -> {
                                      serverGroupCache.put(serverGroup.getId(), serverGroup);
                                      return serverGroup;
                                    })
                                .map(CloudFoundryServerGroup::getId))
                    .orElse(null));
  }

  private Optional<CloudFoundryServerGroup> map(Application application) {
    CloudFoundryServerGroup.State state =
        CloudFoundryServerGroup.State.valueOf(application.getState());

    CloudFoundrySpace space = spaces.findById(application.getLinks().get("space").getGuid());
    String appId = application.getGuid();

    ApplicationEnv applicationEnv;
    try {
      applicationEnv = safelyCall(() -> api.findApplicationEnvById(appId)).orElse(null);
    } catch (CloudFoundryApiException e) {
      // this happens when an account has access to a space but only has read only permissions
      // catching this here to prevent all() from completely failing and breaking caching
      // agents if one space in an account has permissions issues
      if (e.getErrorCode() == NOT_AUTHORIZED) {
        return Optional.empty();
      }

      // null is a valid value and is handled properly in this method
      applicationEnv = null;
    }

    Process process = processes.findProcessById(appId).orElse(null);

    CloudFoundryDroplet droplet = null;
    try {
      CloudFoundryPackage cfPackage =
          safelyCall(() -> api.findPackagesByAppId(appId))
              .flatMap(
                  packages ->
                      packages.getResources().stream()
                          .findFirst()
                          .map(
                              pkg ->
                                  CloudFoundryPackage.builder()
                                      .downloadUrl(
                                          pkg.getLinks().containsKey("download")
                                              ? pkg.getLinks().get("download").getHref()
                                              : null)
                                      .checksumType(
                                          pkg.getData().getChecksum() == null
                                              ? null
                                              : pkg.getData().getChecksum().getType())
                                      .checksum(
                                          pkg.getData().getChecksum() == null
                                              ? null
                                              : pkg.getData().getChecksum().getValue())
                                      .build()))
              .orElse(null);

      droplet =
          safelyCall(() -> api.findDropletByApplicationGuid(appId))
              .map(
                  apiDroplet ->
                      CloudFoundryDroplet.builder()
                          .id(apiDroplet.getGuid())
                          .name(application.getName() + "-droplet")
                          .stack(apiDroplet.getStack())
                          .buildpacks(
                              ofNullable(apiDroplet.getBuildpacks()).orElse(emptyList()).stream()
                                  .map(
                                      bp ->
                                          CloudFoundryBuildpack.builder()
                                              .name(bp.getName())
                                              .detectOutput(bp.getDetectOutput())
                                              .version(bp.getVersion())
                                              .buildpackName(bp.getBuildpackName())
                                              .build())
                                  .collect(toList()))
                          .space(space)
                          .sourcePackage(cfPackage)
                          .build())
              .orElse(null);
    } catch (Exception ex) {
      log.debug("Unable to retrieve droplet for application '" + application.getName() + "'");
    }

    List<CloudFoundryServiceInstance> cloudFoundryServices =
        applicationEnv == null
            ? emptyList()
            : applicationEnv.getSystemEnvJson().getVcapServices().entrySet().stream()
                .flatMap(
                    vcap ->
                        vcap.getValue().stream()
                            .map(
                                instance -> {
                                  CloudFoundryServiceInstance.CloudFoundryServiceInstanceBuilder
                                      cloudFoundryServiceInstanceBuilder =
                                          CloudFoundryServiceInstance.builder()
                                              .serviceInstanceName(vcap.getKey())
                                              .name(instance.getName())
                                              .plan(instance.getPlan())
                                              .tags(instance.getTags());
                                  if (instance.getLastOperation() != null
                                      && instance.getLastOperation().getState() != null) {
                                    cloudFoundryServiceInstanceBuilder
                                        .status(instance.getLastOperation().getState().toString())
                                        .lastOperationDescription(
                                            instance.getLastOperation().getDescription());
                                  }
                                  return cloudFoundryServiceInstanceBuilder.build();
                                }))
                .collect(toList());

    Map<String, Object> environmentVars =
        applicationEnv == null || applicationEnv.getEnvironmentJson() == null
            ? emptyMap()
            : applicationEnv.getEnvironmentJson();

    final CloudFoundryBuildInfo buildInfo = getBuildInfoFromEnvVars(environmentVars);
    final ArtifactInfo artifactInfo = getArtifactInfoFromEnvVars(environmentVars);
    final String pipelineId =
        getEnvironmentVar(environmentVars, ServerGroupMetaDataEnvVar.PipelineId);

    String healthCheckType = null;
    String healthCheckHttpEndpoint = null;
    if (process != null && process.getHealthCheck() != null) {
      final Process.HealthCheck healthCheck = process.getHealthCheck();
      healthCheckType = healthCheck.getType();
      if (healthCheck.getData() != null) {
        healthCheckHttpEndpoint = healthCheck.getData().getEndpoint();
      }
    }

    String serverGroupAppManagerUri = appsManagerUri;
    if (StringUtils.isNotEmpty(appsManagerUri)) {
      serverGroupAppManagerUri =
          Optional.ofNullable(space)
              .map(
                  s ->
                      appsManagerUri
                          + "/organizations/"
                          + s.getOrganization().getId()
                          + "/spaces/"
                          + s.getId()
                          + "/applications/"
                          + appId)
              .orElse("");
    }

    String serverGroupMetricsUri = metricsUri;
    if (StringUtils.isNotEmpty(metricsUri)) {
      serverGroupMetricsUri = metricsUri + "/apps/" + appId;
    }

    CloudFoundryServerGroup cloudFoundryServerGroup =
        CloudFoundryServerGroup.builder()
            .account(account)
            .appsManagerUri(serverGroupAppManagerUri)
            .metricsUri(serverGroupMetricsUri)
            .name(application.getName())
            .id(appId)
            .memory(process != null ? process.getMemoryInMb() : null)
            .instances(emptySet())
            .droplet(droplet)
            .diskQuota(process != null ? process.getDiskInMb() : null)
            .healthCheckType(healthCheckType)
            .healthCheckHttpEndpoint(healthCheckHttpEndpoint)
            .space(space)
            .createdTime(application.getCreatedAt().toInstant().toEpochMilli())
            .serviceInstances(cloudFoundryServices)
            .state(state)
            .env(environmentVars)
            .ciBuild(buildInfo)
            .appArtifact(artifactInfo)
            .pipelineId(pipelineId)
            .updatedTime(application.getUpdatedAt().toInstant().toEpochMilli())
            .build();

    return Optional.of(checkHealthStatus(cloudFoundryServerGroup, application));
  }

  private CloudFoundryServerGroup checkHealthStatus(
      CloudFoundryServerGroup cloudFoundryServerGroup, Application application) {
    CloudFoundryServerGroup.State state =
        CloudFoundryServerGroup.State.valueOf(application.getState());
    Set<CloudFoundryInstance> instances;
    switch (state) {
      case STARTED:
        try {
          instances =
              safelyCall(() -> api.instances(cloudFoundryServerGroup.getId()))
                  .orElse(emptyMap())
                  .entrySet()
                  .stream()
                  .map(
                      inst -> {
                        HealthState healthState = HealthState.Unknown;
                        switch (inst.getValue().getState()) {
                          case RUNNING:
                            healthState = HealthState.Up;
                            break;
                          case DOWN:
                          case CRASHED:
                            healthState = HealthState.Down;
                            break;
                          case STARTING:
                            healthState = HealthState.Starting;
                            break;
                        }
                        return CloudFoundryInstance.builder()
                            .appGuid(cloudFoundryServerGroup.getId())
                            .key(inst.getKey())
                            .healthState(healthState)
                            .details(inst.getValue().getDetails())
                            .launchTime(
                                System.currentTimeMillis() - (inst.getValue().getUptime() * 1000))
                            .zone(cloudFoundryServerGroup.getRegion())
                            .build();
                      })
                  .collect(toSet());

          log.trace(
              "Successfully retrieved "
                  + instances.size()
                  + " instances for application '"
                  + application.getName()
                  + "'");
        } catch (Exception ex) {
          log.debug("Unable to retrieve droplet for application '" + application.getName() + "'");
          instances = emptySet();
        }
        break;
      case STOPPED:
      default:
        instances = emptySet();
    }
    return cloudFoundryServerGroup.toBuilder().state(state).instances(instances).build();
  }

  private String getEnvironmentVar(
      Map<String, Object> environmentVars, ServerGroupMetaDataEnvVar var) {
    return Optional.ofNullable(environmentVars.get(var.envVarName))
        .map(Object::toString)
        .orElse(null);
  }

  private CloudFoundryBuildInfo getBuildInfoFromEnvVars(Map<String, Object> environmentVars) {
    return CloudFoundryBuildInfo.builder()
        .jobName(getEnvironmentVar(environmentVars, ServerGroupMetaDataEnvVar.JobName))
        .jobNumber(getEnvironmentVar(environmentVars, ServerGroupMetaDataEnvVar.JobNumber))
        .jobUrl(getEnvironmentVar(environmentVars, ServerGroupMetaDataEnvVar.JobUrl))
        .build();
  }

  private ArtifactInfo getArtifactInfoFromEnvVars(Map<String, Object> environmentVars) {
    return ArtifactInfo.builder()
        .name(getEnvironmentVar(environmentVars, ServerGroupMetaDataEnvVar.ArtifactName))
        .version(getEnvironmentVar(environmentVars, ServerGroupMetaDataEnvVar.ArtifactVersion))
        .url(getEnvironmentVar(environmentVars, ServerGroupMetaDataEnvVar.ArtifactUrl))
        .build();
  }

  public void mapRoute(String applicationGuid, String routeGuid) throws CloudFoundryApiException {
    safelyCall(() -> api.mapRoute(applicationGuid, routeGuid, new MapRoute()));
  }

  public void unmapRoute(String applicationGuid, String routeGuid) throws CloudFoundryApiException {
    safelyCall(() -> api.unmapRoute(applicationGuid, routeGuid));
  }

  public void startApplication(String applicationGuid) throws CloudFoundryApiException {
    safelyCall(() -> api.startApplication(applicationGuid, new StartApplication()));
  }

  public void stopApplication(String applicationGuid) throws CloudFoundryApiException {
    safelyCall(() -> api.stopApplication(applicationGuid, new StopApplication()));
  }

  public void deleteApplication(String applicationGuid) throws CloudFoundryApiException {
    safelyCall(() -> api.deleteApplication(applicationGuid));
  }

  public void deleteAppInstance(String guid, String index) throws CloudFoundryApiException {
    safelyCall(() -> api.deleteAppInstance(guid, index));
  }

  public CloudFoundryServerGroup createApplication(
      String appName,
      CloudFoundrySpace space,
      @Nullable Map<String, String> environmentVariables,
      Lifecycle lifecycle)
      throws CloudFoundryApiException {
    Map<String, ToOneRelationship> relationships = new HashMap<>();
    relationships.put("space", new ToOneRelationship(new Relationship(space.getId())));
    return safelyCall(
            () ->
                api.createApplication(
                    new CreateApplication(appName, relationships, environmentVariables, lifecycle)))
        .map(this::map)
        .flatMap(sg -> sg)
        .orElseThrow(
            () ->
                new CloudFoundryApiException(
                    "Cloud Foundry signaled that application creation succeeded but failed to provide a response."));
  }

  public String createPackage(CreatePackage createPackageRequest) throws CloudFoundryApiException {
    return safelyCall(() -> api.createPackage(createPackageRequest))
        .map(Package::getGuid)
        .orElseThrow(
            () ->
                new CloudFoundryApiException(
                    "Cloud Foundry signaled that package creation succeeded but failed to provide a response."));
  }

  @Nullable
  public String findCurrentPackageIdByAppId(String appGuid) throws CloudFoundryApiException {
    return safelyCall(() -> this.api.findDropletByApplicationGuid(appGuid))
        .map(
            droplet ->
                StringUtils.substringAfterLast(droplet.getLinks().get("package").getHref(), "/"))
        .orElse(null);
  }

  @Nonnull
  public InputStream downloadPackageBits(String packageGuid) throws CloudFoundryApiException {
    Optional<InputStream> optionalPackageInput =
        safelyCall(() -> api.downloadPackage(packageGuid)).map(ResponseBody::byteStream);
    return optionalPackageInput.orElseThrow(
        () -> new CloudFoundryApiException("Failed to retrieve input stream of package bits."));
  }

  public void uploadPackageBits(String packageGuid, File file) throws CloudFoundryApiException {
    MultipartBody.Part filePart =
        MultipartBody.Part.createFormData(
            "bits",
            file.getName(),
            RequestBody.create(MediaType.parse("multipart/form-data"), file));
    safelyCall(() -> api.uploadPackageBits(packageGuid, filePart))
        .map(Package::getGuid)
        .orElseThrow(
            () ->
                new CloudFoundryApiException(
                    "Cloud Foundry signaled that package upload succeeded but failed to provide a response."));
  }

  public String createBuild(String packageGuid) throws CloudFoundryApiException {
    return safelyCall(() -> api.createBuild(new CreateBuild(packageGuid)))
        .map(Build::getGuid)
        .orElseThrow(
            () ->
                new CloudFoundryApiException(
                    "Cloud Foundry signaled that build creation succeeded but failed to provide a response."));
  }

  public Boolean buildCompleted(String buildGuid) throws CloudFoundryApiException {
    switch (safelyCall(() -> api.getBuild(buildGuid))
        .map(Build::getState)
        .orElse(Build.State.FAILED)) {
      case FAILED:
        throw new CloudFoundryApiException(
            "Failed to build droplet or there are not enough resources available");
      case STAGED:
        return true;
      default:
        return false;
    }
  }

  public boolean packageUploadComplete(String packageGuid) throws CloudFoundryApiException {
    switch (safelyCall(() -> api.getPackage(packageGuid))
        .map(Package::getState)
        .orElse(Package.State.FAILED)) {
      case FAILED:
      case EXPIRED:
        throw new CloudFoundryApiException("Upload failed");
      case READY:
        return true;
      default:
        return false;
    }
  }

  public String findDropletGuidFromBuildId(String buildGuid) throws CloudFoundryApiException {
    return safelyCall(() -> api.getBuild(buildGuid))
        .map(Build::getDroplet)
        .map(Droplet::getGuid)
        .orElse(null);
  }

  public void setCurrentDroplet(String appGuid, String dropletGuid)
      throws CloudFoundryApiException {
    safelyCall(
        () -> api.setCurrentDroplet(appGuid, new ToOneRelationship(new Relationship(dropletGuid))));
  }

  public List<Resource<com.netflix.spinnaker.clouddriver.cloudfoundry.client.model.v2.Application>>
      getTakenSlots(String clusterName, String spaceId) {
    String finalName = buildFinalAsgName(clusterName);
    List<String> filter =
        asList("name<=" + finalName, "name>=" + clusterName, "space_guid:" + spaceId);
    return collectPageResources("applications", page -> api.listAppsFiltered(page, filter, 10))
        .stream()
        .filter(
            app -> {
              Names entityNames = Names.parseName(app.getEntity().getName());
              return clusterName.equals(entityNames.getCluster());
            })
        .collect(Collectors.toList());
  }

  private String buildFinalAsgName(String clusterName) {
    Names names = Names.parseName(clusterName);
    return AbstractServerGroupNameResolver.generateServerGroupName(
        names.getApp(), names.getStack(), names.getDetail(), 999, false);
  }

  public void restageApplication(String appGuid) {
    safelyCall(() -> api.restageApplication(appGuid, ""));
  }

  public ProcessStats.State getAppState(String guid) {
    return processes
        .getProcessState(guid)
        .orElseGet(
            () ->
                safelyCall(() -> api.findById(guid))
                    .filter(
                        application ->
                            CloudFoundryServerGroup.State.STARTED.equals(
                                CloudFoundryServerGroup.State.valueOf(application.getState())))
                    .map(appState -> ProcessStats.State.RUNNING)
                    .orElse(ProcessStats.State.DOWN));
  }

  public List<Resource<ServiceBinding>> getServiceBindingsByApp(String appGuid) {
    return collectPageResources("service bindings", pg -> api.getServiceBindings(appGuid));
  }
}
