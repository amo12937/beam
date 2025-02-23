/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.beam.fn.harness.logging;

import static org.apache.beam.vendor.guava.v26_0_jre.com.google.common.base.Throwables.getStackTraceAsString;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.contains;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.util.Collection;
import java.util.UUID;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.logging.Handler;
import java.util.logging.Level;
import java.util.logging.LogManager;
import java.util.logging.LogRecord;
import java.util.logging.Logger;
import java.util.logging.SimpleFormatter;
import org.apache.beam.model.fnexecution.v1.BeamFnApi;
import org.apache.beam.model.fnexecution.v1.BeamFnLoggingGrpc;
import org.apache.beam.model.pipeline.v1.Endpoints;
import org.apache.beam.sdk.fn.test.TestStreams;
import org.apache.beam.sdk.options.PipelineOptionsFactory;
import org.apache.beam.vendor.grpc.v1p54p0.com.google.protobuf.Struct;
import org.apache.beam.vendor.grpc.v1p54p0.com.google.protobuf.Timestamp;
import org.apache.beam.vendor.grpc.v1p54p0.com.google.protobuf.Value;
import org.apache.beam.vendor.grpc.v1p54p0.io.grpc.ManagedChannel;
import org.apache.beam.vendor.grpc.v1p54p0.io.grpc.Server;
import org.apache.beam.vendor.grpc.v1p54p0.io.grpc.Status;
import org.apache.beam.vendor.grpc.v1p54p0.io.grpc.inprocess.InProcessChannelBuilder;
import org.apache.beam.vendor.grpc.v1p54p0.io.grpc.inprocess.InProcessServerBuilder;
import org.apache.beam.vendor.grpc.v1p54p0.io.grpc.stub.CallStreamObserver;
import org.apache.beam.vendor.grpc.v1p54p0.io.grpc.stub.StreamObserver;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.ExpectedException;
import org.junit.rules.TestRule;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;
import org.slf4j.MDC;

/** Tests for {@link BeamFnLoggingClient}. */
@RunWith(JUnit4.class)
public class BeamFnLoggingClientTest {
  @Rule public TestRule restoreLogging = new RestoreBeamFnLoggingMDC();
  private static final LogRecord FILTERED_RECORD;
  private static final LogRecord TEST_RECORD;
  private static final LogRecord TEST_RECORD_WITH_EXCEPTION;

  static {
    FILTERED_RECORD = new LogRecord(Level.SEVERE, "FilteredMessage");

    TEST_RECORD = new LogRecord(Level.FINE, "Message");
    TEST_RECORD.setLoggerName("LoggerName");
    TEST_RECORD.setMillis(1234567890L);
    TEST_RECORD.setThreadID(12345);

    TEST_RECORD_WITH_EXCEPTION = new LogRecord(Level.WARNING, "MessageWithException");
    TEST_RECORD_WITH_EXCEPTION.setLoggerName("LoggerName");
    TEST_RECORD_WITH_EXCEPTION.setMillis(1234567890L);
    TEST_RECORD_WITH_EXCEPTION.setThreadID(12345);
    TEST_RECORD_WITH_EXCEPTION.setThrown(new RuntimeException("ExceptionMessage"));
  }

  private static final BeamFnApi.LogEntry TEST_ENTRY =
      BeamFnApi.LogEntry.newBuilder()
          .setInstructionId("instruction-1")
          .setSeverity(BeamFnApi.LogEntry.Severity.Enum.DEBUG)
          .setMessage("Message")
          .setThread("12345")
          .setTimestamp(Timestamp.newBuilder().setSeconds(1234567).setNanos(890000000).build())
          .setLogLocation("LoggerName")
          .build();
  private static final BeamFnApi.LogEntry TEST_ENTRY_WITH_CUSTOM_FORMATTER =
      BeamFnApi.LogEntry.newBuilder()
          .setInstructionId("instruction-1")
          .setSeverity(BeamFnApi.LogEntry.Severity.Enum.DEBUG)
          .setMessage("testMdcValue:Message")
          .setCustomData(
              Struct.newBuilder()
                  .putFields(
                      "testMdcKey", Value.newBuilder().setStringValue("testMdcValue").build()))
          .setThread("12345")
          .setTimestamp(Timestamp.newBuilder().setSeconds(1234567).setNanos(890000000).build())
          .setLogLocation("LoggerName")
          .build();
  private static final BeamFnApi.LogEntry TEST_ENTRY_WITH_EXCEPTION =
      BeamFnApi.LogEntry.newBuilder()
          .setInstructionId("instruction-1")
          .setSeverity(BeamFnApi.LogEntry.Severity.Enum.WARN)
          .setMessage("MessageWithException")
          .setTrace(getStackTraceAsString(TEST_RECORD_WITH_EXCEPTION.getThrown()))
          .setThread("12345")
          .setTimestamp(Timestamp.newBuilder().setSeconds(1234567).setNanos(890000000).build())
          .setLogLocation("LoggerName")
          .build();
  @Rule public ExpectedException thrown = ExpectedException.none();

  @Test
  public void testLogging() throws Exception {
    BeamFnLoggingMDC.setInstructionId("instruction-1");
    AtomicBoolean clientClosedStream = new AtomicBoolean();
    Collection<BeamFnApi.LogEntry> values = new ConcurrentLinkedQueue<>();
    AtomicReference<StreamObserver<BeamFnApi.LogControl>> outboundServerObserver =
        new AtomicReference<>();
    CallStreamObserver<BeamFnApi.LogEntry.List> inboundServerObserver =
        TestStreams.withOnNext(
                (BeamFnApi.LogEntry.List logEntries) ->
                    values.addAll(logEntries.getLogEntriesList()))
            .withOnCompleted(
                () -> {
                  // Remember that the client told us that this stream completed
                  clientClosedStream.set(true);
                  outboundServerObserver.get().onCompleted();
                })
            .build();

    Endpoints.ApiServiceDescriptor apiServiceDescriptor =
        Endpoints.ApiServiceDescriptor.newBuilder()
            .setUrl(this.getClass().getName() + "-" + UUID.randomUUID().toString())
            .build();
    Server server =
        InProcessServerBuilder.forName(apiServiceDescriptor.getUrl())
            .addService(
                new BeamFnLoggingGrpc.BeamFnLoggingImplBase() {
                  @Override
                  public StreamObserver<BeamFnApi.LogEntry.List> logging(
                      StreamObserver<BeamFnApi.LogControl> outboundObserver) {
                    outboundServerObserver.set(outboundObserver);
                    return inboundServerObserver;
                  }
                })
            .build();
    server.start();

    ManagedChannel channel = InProcessChannelBuilder.forName(apiServiceDescriptor.getUrl()).build();
    try {

      BeamFnLoggingClient client =
          new BeamFnLoggingClient(
              PipelineOptionsFactory.fromArgs(
                      new String[] {
                        "--defaultSdkHarnessLogLevel=OFF",
                        "--sdkHarnessLogLevelOverrides={\"ConfiguredLogger\": \"DEBUG\"}"
                      })
                  .create(),
              apiServiceDescriptor,
              (Endpoints.ApiServiceDescriptor descriptor) -> channel);

      // Keep a strong reference to the loggers in this block. Otherwise the call to client.close()
      // removes the only reference and the logger may get GC'd before the assertions (BEAM-4136).
      Logger rootLogger = LogManager.getLogManager().getLogger("");
      Logger configuredLogger = LogManager.getLogManager().getLogger("ConfiguredLogger");

      // Ensure that log levels were correctly set.
      assertEquals(Level.OFF, rootLogger.getLevel());
      assertEquals(Level.FINE, configuredLogger.getLevel());

      // Should be filtered because the default log level override is OFF
      rootLogger.log(FILTERED_RECORD);
      // Should not be filtered because the default log level override for ConfiguredLogger is DEBUG
      configuredLogger.log(TEST_RECORD);
      configuredLogger.log(TEST_RECORD_WITH_EXCEPTION);

      // Ensure that configuring a custom formatter on the logging handler will be honored.
      for (Handler handler : rootLogger.getHandlers()) {
        handler.setFormatter(
            new SimpleFormatter() {
              @Override
              public synchronized String formatMessage(LogRecord record) {
                return MDC.get("testMdcKey") + ":" + super.formatMessage(record);
              }
            });
      }
      MDC.put("testMdcKey", "testMdcValue");
      configuredLogger.log(TEST_RECORD);

      client.close();

      // Verify that after close, log levels are reset.
      assertEquals(Level.INFO, rootLogger.getLevel());
      assertNull(configuredLogger.getLevel());

      assertTrue(clientClosedStream.get());
      assertTrue(channel.isShutdown());
      assertThat(
          values,
          contains(TEST_ENTRY, TEST_ENTRY_WITH_EXCEPTION, TEST_ENTRY_WITH_CUSTOM_FORMATTER));
    } finally {
      server.shutdownNow();
    }
  }

  @Test
  public void testWhenServerFailsThatClientIsAbleToCleanup() throws Exception {
    BeamFnLoggingMDC.setInstructionId("instruction-1");
    Collection<BeamFnApi.LogEntry> values = new ConcurrentLinkedQueue<>();
    AtomicReference<StreamObserver<BeamFnApi.LogControl>> outboundServerObserver =
        new AtomicReference<>();
    CallStreamObserver<BeamFnApi.LogEntry.List> inboundServerObserver =
        TestStreams.withOnNext(
                (BeamFnApi.LogEntry.List logEntries) ->
                    values.addAll(logEntries.getLogEntriesList()))
            .build();

    Endpoints.ApiServiceDescriptor apiServiceDescriptor =
        Endpoints.ApiServiceDescriptor.newBuilder()
            .setUrl(this.getClass().getName() + "-" + UUID.randomUUID().toString())
            .build();
    Server server =
        InProcessServerBuilder.forName(apiServiceDescriptor.getUrl())
            .addService(
                new BeamFnLoggingGrpc.BeamFnLoggingImplBase() {
                  @Override
                  public StreamObserver<BeamFnApi.LogEntry.List> logging(
                      StreamObserver<BeamFnApi.LogControl> outboundObserver) {
                    outboundServerObserver.set(outboundObserver);
                    outboundObserver.onError(
                        Status.INTERNAL.withDescription("TEST ERROR").asException());
                    return inboundServerObserver;
                  }
                })
            .build();
    server.start();

    ManagedChannel channel = InProcessChannelBuilder.forName(apiServiceDescriptor.getUrl()).build();

    // Keep a strong reference to the loggers. Otherwise the call to client.close()
    // removes the only reference and the logger may get GC'd before the assertions (BEAM-4136).
    Logger rootLogger = null;
    Logger configuredLogger = null;

    try {
      BeamFnLoggingClient client =
          new BeamFnLoggingClient(
              PipelineOptionsFactory.fromArgs(
                      new String[] {
                        "--defaultSdkHarnessLogLevel=OFF",
                        "--sdkHarnessLogLevelOverrides={\"ConfiguredLogger\": \"DEBUG\"}"
                      })
                  .create(),
              apiServiceDescriptor,
              (Endpoints.ApiServiceDescriptor descriptor) -> channel);

      rootLogger = LogManager.getLogManager().getLogger("");
      configuredLogger = LogManager.getLogManager().getLogger("ConfiguredLogger");

      thrown.expectMessage("TEST ERROR");
      client.close();
    } finally {
      assertNotNull("rootLogger should be initialized before exception", rootLogger);
      assertNotNull("configuredLogger should be initialized before exception", rootLogger);

      // Verify that after close, log levels are reset.
      assertEquals(Level.INFO, rootLogger.getLevel());
      assertNull(configuredLogger.getLevel());

      assertTrue(channel.isShutdown());

      server.shutdownNow();
    }
  }

  @Test
  public void testWhenServerHangsUpEarlyThatClientIsAbleCleanup() throws Exception {
    BeamFnLoggingMDC.setInstructionId("instruction-1");
    Collection<BeamFnApi.LogEntry> values = new ConcurrentLinkedQueue<>();
    AtomicReference<StreamObserver<BeamFnApi.LogControl>> outboundServerObserver =
        new AtomicReference<>();
    CallStreamObserver<BeamFnApi.LogEntry.List> inboundServerObserver =
        TestStreams.withOnNext(
                (BeamFnApi.LogEntry.List logEntries) ->
                    values.addAll(logEntries.getLogEntriesList()))
            .build();

    Endpoints.ApiServiceDescriptor apiServiceDescriptor =
        Endpoints.ApiServiceDescriptor.newBuilder()
            .setUrl(this.getClass().getName() + "-" + UUID.randomUUID().toString())
            .build();
    Server server =
        InProcessServerBuilder.forName(apiServiceDescriptor.getUrl())
            .addService(
                new BeamFnLoggingGrpc.BeamFnLoggingImplBase() {
                  @Override
                  public StreamObserver<BeamFnApi.LogEntry.List> logging(
                      StreamObserver<BeamFnApi.LogControl> outboundObserver) {
                    outboundServerObserver.set(outboundObserver);
                    outboundObserver.onCompleted();
                    return inboundServerObserver;
                  }
                })
            .build();
    server.start();

    ManagedChannel channel = InProcessChannelBuilder.forName(apiServiceDescriptor.getUrl()).build();
    try {
      BeamFnLoggingClient client =
          new BeamFnLoggingClient(
              PipelineOptionsFactory.fromArgs(
                      new String[] {
                        "--defaultSdkHarnessLogLevel=OFF",
                        "--sdkHarnessLogLevelOverrides={\"ConfiguredLogger\": \"DEBUG\"}"
                      })
                  .create(),
              apiServiceDescriptor,
              (Endpoints.ApiServiceDescriptor descriptor) -> channel);

      // Keep a strong reference to the loggers in this block. Otherwise the call to client.close()
      // removes the only reference and the logger may get GC'd before the assertions (BEAM-4136).
      Logger rootLogger = LogManager.getLogManager().getLogger("");
      Logger configuredLogger = LogManager.getLogManager().getLogger("ConfiguredLogger");

      client.close();

      // Verify that after close, log levels are reset.
      assertEquals(Level.INFO, rootLogger.getLevel());
      assertNull(configuredLogger.getLevel());
    } finally {
      assertTrue(channel.isShutdown());

      server.shutdownNow();
    }
  }
}
