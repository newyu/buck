/*
 * Copyright 2016-present Facebook, Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may
 * not use this file except in compliance with the License. You may obtain
 * a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations
 * under the License.
 */

package com.facebook.buck.doctor;

import static com.facebook.buck.doctor.config.DoctorEndpointResponse.StepStatus;
import static org.hamcrest.junit.MatcherAssume.assumeThat;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertThat;
import static org.junit.Assert.assertTrue;

import com.facebook.buck.cli.BuckConfig;
import com.facebook.buck.cli.FakeBuckConfig;
import com.facebook.buck.doctor.config.DoctorConfig;
import com.facebook.buck.doctor.config.DoctorEndpointRequest;
import com.facebook.buck.doctor.config.DoctorEndpointResponse;
import com.facebook.buck.rage.BuildLogEntry;
import com.facebook.buck.rage.BuildLogHelper;
import com.facebook.buck.rage.UserInput;
import com.facebook.buck.rage.UserInputFixture;
import com.facebook.buck.testutil.TestConsole;
import com.facebook.buck.testutil.integration.HttpdForTests;
import com.facebook.buck.testutil.integration.ProjectWorkspace;
import com.facebook.buck.testutil.integration.TemporaryPaths;
import com.facebook.buck.testutil.integration.TestDataHelper;
import com.facebook.buck.util.BuckConstant;
import com.facebook.buck.util.ObjectMappers;
import com.facebook.buck.util.environment.Platform;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.common.base.Charsets;
import com.google.common.base.Joiner;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.io.ByteStreams;

import org.eclipse.jetty.server.Request;
import org.eclipse.jetty.server.handler.AbstractHandler;
import org.hamcrest.Matchers;
import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;

import java.io.DataOutputStream;
import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;

import javax.servlet.ServletException;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

public class DoctorCommandIntegrationTest {

  @Rule
  public TemporaryPaths tempFolder = new TemporaryPaths();

  private ProjectWorkspace workspace;
  private HttpdForTests httpd;

  private ObjectMapper objectMapper;
  private DoctorEndpointResponse doctorResponse;

  private final AtomicReference<String> requestMethod = new AtomicReference<>();
  private final AtomicReference<String> requestPath = new AtomicReference<>();
  private final AtomicReference<byte[]> requestBody = new AtomicReference<>();

  private static final String LOG_PATH =
      Joiner.on(File.separator).join(
          ImmutableList.of(
              BuckConstant.getBuckOutputDirectory(),
              "log",
              "2016-06-21_16h16m24s_buildcommand_ac8bd626-6137-4747-84dd-5d4f215c876c",
              BuckConstant.BUCK_LOG_FILE_NAME));


  @Before
  public void setUp() throws Exception {
    workspace = TestDataHelper.createProjectWorkspaceForScenario(this, "report", tempFolder);
    workspace.setUp();
    objectMapper = ObjectMappers.newDefaultInstance();

    doctorResponse = DoctorEndpointResponse.of(
          Optional.empty(),
          StepStatus.OK,
          StepStatus.WARNING,
          StepStatus.OK,
          ImmutableList.of("Suggestion no1", "Suggestion no2"));

    httpd = new HttpdForTests();
    httpd.addHandler(createHttpdHandler(
        "POST",
        "{\"buildId\":\"ac8bd626-6137-4747-84dd-5d4f215c876c\",\"logDirPath\":\"" +
            LOG_PATH + "\",\"machineReadableLog\""));
    httpd.start();
  }

  @After
  public void cleanUp() throws Exception {
    httpd.close();
  }

  @Test
  public void testEndpointUrl() throws Exception {
    DoctorReportHelper helper = createDoctorHelper(
        (new UserInputFixture("0")).getUserInput(),
        DoctorConfig.of(FakeBuckConfig.builder().build()));
    BuildLogHelper buildLogHelper = new BuildLogHelper(
        workspace.asCell().getFilesystem(),
        objectMapper);
    Optional<BuildLogEntry> entry =
        helper.promptForBuild(new ArrayList<>(buildLogHelper.getBuildLogs()));

    DoctorEndpointRequest request = helper.generateEndpointRequest(entry.get(), Optional.empty());
    DoctorEndpointResponse response = helper.uploadRequest(request);
    assertEquals(
        "Please define URL",
        response.getErrorMessage().get(),
        "Doctor endpoint URL is not set. Please set [doctor] endpoint_url on your .buckconfig");
  }

  @Test
  public void testPromptWithoutRageReport() throws Exception {
    assumeThat(Platform.detect(), Matchers.not(Matchers.is(Platform.WINDOWS)));

    DoctorReportHelper helper = createDoctorHelper(
        (new UserInputFixture("0")).getUserInput(),
        createDoctorConfig(httpd));

    BuildLogHelper buildLogHelper = new BuildLogHelper(
        workspace.asCell().getFilesystem(),
        objectMapper);
    Optional<BuildLogEntry> entry =
        helper.promptForBuild(new ArrayList<>(buildLogHelper.getBuildLogs()));

    DoctorEndpointRequest request = helper.generateEndpointRequest(entry.get(), Optional.empty());
    DoctorEndpointResponse response = helper.uploadRequest(request);
    helper.presentResponse(response);

    assertEquals(response, doctorResponse);
    assertEquals(
        ((TestConsole) helper.getConsole()).getTextWrittenToStdOut(),
        "\n:: Doctor results\n" +
            "  [OK ] Environment\n" +
            "  [OK ] Parsing\n" +
            "  [Warning ] Remote cache\n" +
            ":: Suggestions\n" +
            "- Suggestion no1\n" +
            "- Suggestion no2\n\n");
    }

  private AbstractHandler createHttpdHandler(
      final String expectedMethod,
      final String expectedBody) {
    return new AbstractHandler() {
      @Override
      public void handle(
          String s,
          Request request,
          HttpServletRequest httpRequest,
          HttpServletResponse httpResponse) throws IOException, ServletException {
        httpResponse.setStatus(200);
        if (request.getUri().getPath().equals("/status.php")) {
          return;
        }

        httpResponse.setContentType("application/json");
        httpResponse.setCharacterEncoding("utf-8");

        requestPath.set(request.getUri().getPath());
        requestMethod.set(request.getMethod());
        requestBody.set(ByteStreams.toByteArray(httpRequest.getInputStream()));

        assertTrue(requestMethod.get().equalsIgnoreCase(expectedMethod));
        assertThat(
            "Request should contain the uuid.",
            new String(requestBody.get(), Charsets.UTF_8),
            Matchers.containsString(expectedBody));

        try (DataOutputStream out =
                 new DataOutputStream(httpResponse.getOutputStream())) {
          objectMapper.writeValue(out, doctorResponse);
        }
      }
    };
  }

  private static DoctorConfig createDoctorConfig(HttpdForTests httpd) {
    String uri = "http://localhost:" + httpd.getRootUri().getPort();
        BuckConfig buckConfig = FakeBuckConfig.builder()
        .setSections(
          ImmutableMap.of(
              DoctorConfig.DOCTOR_SECTION, ImmutableMap.of(DoctorConfig.URL_FIELD, uri)))
        .build();
    return DoctorConfig.of(buckConfig);
  }

  private DoctorReportHelper createDoctorHelper(
      UserInput input,
      DoctorConfig doctorConfig
  ) throws Exception {
    return new DoctorReportHelper(
        workspace.asCell().getFilesystem(),
        input,
        new TestConsole(),
        objectMapper,
        doctorConfig);
  }
}

