/*
 * Copyright 2016 Google Inc. All Rights Reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *       http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.google.cloud.logging.spi.v2;

import com.google.api.core.ApiFunction;
import com.google.api.core.ApiFuture;
import com.google.api.core.ApiFutures;
import com.google.api.gax.core.BackgroundResource;
import com.google.api.gax.grpc.ChannelProvider;
import com.google.api.gax.grpc.GrpcApiException;
import com.google.api.gax.grpc.GrpcTransport;
import com.google.api.gax.grpc.GrpcTransportProvider;
import com.google.api.gax.rpc.ClientContext;
import com.google.api.gax.rpc.Transport;
import com.google.api.gax.rpc.UnaryCallSettings;
import com.google.api.gax.rpc.UnaryCallSettings.Builder;
import com.google.auth.Credentials;
import com.google.cloud.NoCredentials;
import com.google.cloud.grpc.GrpcTransportOptions;
import com.google.cloud.grpc.GrpcTransportOptions.ExecutorFactory;
import com.google.cloud.logging.LoggingException;
import com.google.cloud.logging.LoggingOptions;
import com.google.cloud.logging.v2.ConfigClient;
import com.google.cloud.logging.v2.ConfigSettings;
import com.google.cloud.logging.v2.LoggingClient;
import com.google.cloud.logging.v2.LoggingSettings;
import com.google.cloud.logging.v2.MetricsClient;
import com.google.cloud.logging.v2.MetricsSettings;
import com.google.logging.v2.CreateLogMetricRequest;
import com.google.logging.v2.CreateSinkRequest;
import com.google.logging.v2.DeleteLogMetricRequest;
import com.google.logging.v2.DeleteLogRequest;
import com.google.logging.v2.DeleteSinkRequest;
import com.google.logging.v2.GetLogMetricRequest;
import com.google.logging.v2.GetSinkRequest;
import com.google.logging.v2.ListLogEntriesRequest;
import com.google.logging.v2.ListLogEntriesResponse;
import com.google.logging.v2.ListLogMetricsRequest;
import com.google.logging.v2.ListLogMetricsResponse;
import com.google.logging.v2.ListMonitoredResourceDescriptorsRequest;
import com.google.logging.v2.ListMonitoredResourceDescriptorsResponse;
import com.google.logging.v2.ListSinksRequest;
import com.google.logging.v2.ListSinksResponse;
import com.google.logging.v2.LogMetric;
import com.google.logging.v2.LogSink;
import com.google.logging.v2.UpdateLogMetricRequest;
import com.google.logging.v2.UpdateSinkRequest;
import com.google.logging.v2.WriteLogEntriesRequest;
import com.google.logging.v2.WriteLogEntriesResponse;
import com.google.protobuf.Empty;
import io.grpc.ManagedChannel;
import io.grpc.ManagedChannelBuilder;
import io.grpc.Status.Code;
import java.io.IOException;
import java.util.Collections;
import java.util.EnumSet;
import java.util.Set;
import java.util.concurrent.ScheduledExecutorService;

public class GrpcLoggingRpc implements LoggingRpc {

  private final ConfigClient configClient;
  private final LoggingClient loggingClient;
  private final MetricsClient metricsClient;
  private final ScheduledExecutorService executor;
  private final ClientContext clientContext;
  private final ExecutorFactory<ScheduledExecutorService> executorFactory;

  private boolean closed;

  public GrpcLoggingRpc(final LoggingOptions options) throws IOException {
    GrpcTransportOptions transportOptions = (GrpcTransportOptions) options.getTransportOptions();
    executorFactory = transportOptions.getExecutorFactory();
    executor = executorFactory.get();
    try {
      // todo(mziccard): ChannelProvider should support null/absent credentials for testing
      if (options.getHost().contains("localhost")
          || NoCredentials.getInstance().equals(options.getCredentials())) {
        ManagedChannel managedChannel = ManagedChannelBuilder.forTarget(options.getHost())
            .usePlaintext(true)
            .executor(executor)
            .build();
        clientContext = ClientContext.newBuilder()
            .setCredentials(null)
            .setExecutor(executor)
            .setTransportContext(
                GrpcTransport.newBuilder().setChannel(managedChannel).build()).build();
      } else {
        Credentials credentials = GrpcTransportOptions.setUpCredentialsProvider(options)
            .getCredentials();
        ChannelProvider channelProvider = GrpcTransportOptions.setUpChannelProvider(
            LoggingSettings.defaultGrpcChannelProviderBuilder(), options);
        GrpcTransportProvider transportProviders = GrpcTransportProvider.newBuilder()
            .setChannelProvider(channelProvider).build();
        Transport transport;
        if (transportProviders.needsExecutor()) {
          transport = transportProviders.getTransport(executor);
        } else {
          transport = transportProviders.getTransport();
        }
        clientContext = ClientContext.newBuilder()
            .setCredentials(credentials)
            .setExecutor(executor)
            .setTransportContext(transport)
            .setBackgroundResources(transport.getBackgroundResources())
            .build();
      }
      ApiFunction<UnaryCallSettings.Builder, Void> retrySettingsSetter =
          new ApiFunction<Builder, Void>() {
        @Override
        public Void apply(UnaryCallSettings.Builder builder) {
          builder.setRetrySettings(options.getRetrySettings());
          return null;
        }
      };
      ConfigSettings.Builder confBuilder =
          ConfigSettings.defaultBuilder(clientContext).applyToAllUnaryMethods(retrySettingsSetter);
      LoggingSettings.Builder logBuilder =
          LoggingSettings.defaultBuilder(clientContext).applyToAllUnaryMethods(retrySettingsSetter);
      MetricsSettings.Builder metricsBuilder =
          MetricsSettings.defaultBuilder(clientContext).applyToAllUnaryMethods(retrySettingsSetter);
      configClient = ConfigClient.create(confBuilder.build());
      loggingClient = LoggingClient.create(logBuilder.build());
      metricsClient = MetricsClient.create(metricsBuilder.build());
    } catch (Exception ex) {
      throw new IOException(ex);
    }
  }

  private static <V> ApiFuture<V> translate(
      ApiFuture<V> from, final boolean idempotent, Code... returnNullOn) {
    final Set<Code> returnNullOnSet;
    if (returnNullOn.length > 0) {
      returnNullOnSet = EnumSet.of(returnNullOn[0], returnNullOn);
    } else {
      returnNullOnSet = Collections.<Code>emptySet();
    }
    return ApiFutures.catching(
        from,
        GrpcApiException.class,
        new ApiFunction<GrpcApiException, V>() {
          @Override
          public V apply(GrpcApiException exception) {
            if (returnNullOnSet.contains(exception.getStatusCode().getCode())) {
              return null;
            }
            throw new LoggingException(exception);
          }
        });
  }

  @Override
  public ApiFuture<LogSink> create(CreateSinkRequest request) {
    return translate(configClient.createSinkCallable().futureCall(request), true);
  }

  @Override
  public ApiFuture<LogSink> update(UpdateSinkRequest request) {
    return translate(configClient.updateSinkCallable().futureCall(request), true);
  }

  @Override
  public ApiFuture<LogSink> get(GetSinkRequest request) {
    return translate(configClient.getSinkCallable().futureCall(request), true, Code.NOT_FOUND);
  }

  @Override
  public ApiFuture<ListSinksResponse> list(ListSinksRequest request) {
    return translate(configClient.listSinksCallable().futureCall(request), true);
  }

  @Override
  public ApiFuture<Empty> delete(DeleteSinkRequest request) {
    return translate(configClient.deleteSinkCallable().futureCall(request), true, Code.NOT_FOUND);
  }

  @Override
  public ApiFuture<Empty> delete(DeleteLogRequest request) {
    return translate(loggingClient.deleteLogCallable().futureCall(request), true, Code.NOT_FOUND);
  }

  @Override
  public ApiFuture<WriteLogEntriesResponse> write(WriteLogEntriesRequest request) {
    return translate(loggingClient.writeLogEntriesCallable().futureCall(request), false);
  }

  @Override
  public ApiFuture<ListLogEntriesResponse> list(ListLogEntriesRequest request) {
    return translate(loggingClient.listLogEntriesCallable().futureCall(request), true);
  }

  @Override
  public ApiFuture<ListMonitoredResourceDescriptorsResponse> list(
      ListMonitoredResourceDescriptorsRequest request) {
    return translate(loggingClient.listMonitoredResourceDescriptorsCallable().futureCall(request),
        true);
  }

  @Override
  public ApiFuture<LogMetric> create(CreateLogMetricRequest request) {
    return translate(metricsClient.createLogMetricCallable().futureCall(request), true);
  }

  @Override
  public ApiFuture<LogMetric> update(UpdateLogMetricRequest request) {
    return translate(metricsClient.updateLogMetricCallable().futureCall(request), true);
  }

  @Override
  public ApiFuture<LogMetric> get(GetLogMetricRequest request) {
    return translate(
        metricsClient.getLogMetricCallable().futureCall(request), true, Code.NOT_FOUND);
  }

  @Override
  public ApiFuture<ListLogMetricsResponse> list(ListLogMetricsRequest request) {
    return translate(metricsClient.listLogMetricsCallable().futureCall(request), true);
  }

  @Override
  public ApiFuture<Empty> delete(DeleteLogMetricRequest request) {
    return translate(
        metricsClient.deleteLogMetricCallable().futureCall(request), true, Code.NOT_FOUND);
  }

  @Override
  public void close() throws Exception {
    if (closed) {
      return;
    }
    closed = true;
    configClient.close();
    loggingClient.close();
    metricsClient.close();
    for (BackgroundResource resource : clientContext.getBackgroundResources()) {
      resource.close();
    }
    executorFactory.release(executor);
  }
}
