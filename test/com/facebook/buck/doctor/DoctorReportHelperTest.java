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
import static org.junit.Assert.assertEquals;

import com.facebook.buck.cli.FakeBuckConfig;
import com.facebook.buck.doctor.config.DoctorConfig;
import com.facebook.buck.doctor.config.DoctorEndpointResponse;
import com.facebook.buck.rage.UserInputFixture;
import com.facebook.buck.testutil.TestConsole;
import com.facebook.buck.testutil.integration.ProjectWorkspace;
import com.facebook.buck.testutil.integration.TemporaryPaths;
import com.facebook.buck.testutil.integration.TestDataHelper;
import com.facebook.buck.util.ObjectMappers;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;

import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;

import java.util.Optional;

public class DoctorReportHelperTest {

  @Rule
  public TemporaryPaths tempFolder = new TemporaryPaths();

  private ProjectWorkspace workspace;
  private ObjectMapper objectMapper;

  @Before
  public void setUp() throws Exception {
    workspace = TestDataHelper.createProjectWorkspaceForScenario(this, "report", tempFolder);
    workspace.setUp();
    objectMapper = ObjectMappers.newDefaultInstance();
  }

  @Test
  public void testErrorMessage() throws Exception {
    TestConsole console = new TestConsole();
    DoctorConfig doctorConfig = DoctorConfig.of(
        FakeBuckConfig.builder()
            .setSections(ImmutableMap.of(
                    DoctorConfig.DOCTOR_SECTION,
                    ImmutableMap.of(DoctorConfig.URL_FIELD, "url"))).build());

    DoctorReportHelper helper = new DoctorReportHelper(
        workspace.asCell().getFilesystem(),
        (new UserInputFixture("0")).getUserInput(),
        console,
        objectMapper,
        doctorConfig);

    String errorMessage = "This is an error message.";
    DoctorEndpointResponse response = DoctorEndpointResponse.of(
        Optional.of(errorMessage),
        StepStatus.ERROR,
        StepStatus.ERROR,
        StepStatus.ERROR,
        ImmutableList.of("Suggestion no1", "Suggestion no2"));

    helper.presentResponse(response);
    assertEquals(
        console.getTextWrittenToStdOut(),
        "\n:: Doctor results\n" + errorMessage + "\n");
  }

  @Test
  public void testNoAvailableSuggestions() throws Exception {
    TestConsole console = new TestConsole();
    DoctorConfig doctorConfig = DoctorConfig.of(
        FakeBuckConfig.builder()
            .setSections(ImmutableMap.of(
                DoctorConfig.DOCTOR_SECTION,
                ImmutableMap.of(DoctorConfig.URL_FIELD, "url"))).build());

    DoctorReportHelper helper = new DoctorReportHelper(
        workspace.asCell().getFilesystem(),
        (new UserInputFixture("0")).getUserInput(),
        console,
        objectMapper,
        doctorConfig);

    DoctorEndpointResponse response = DoctorEndpointResponse.of(
        Optional.empty(),
        StepStatus.ERROR,
        StepStatus.ERROR,
        StepStatus.ERROR,
        ImmutableList.of());

    helper.presentResponse(response);
    assertEquals(
        console.getTextWrittenToStdOut(),
        "\n:: Doctor results\n" +
            "  [Error ] Environment\n" +
            "  [Error ] Parsing\n" +
            "  [Error ] Remote cache\n" +
            ":: No available suggestions right now.\n\n");
  }

}


