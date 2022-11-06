/*
 * Copyright 2022 Google Inc. All Rights Reserved.
 *
 *  Licensed under the Apache License, Version 2.0 (the "License"); you may not use this file except
 * in compliance with the License. You may obtain a copy of the License at
 *
 *  http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software distributed under the
 * License is distributed on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either
 * express or implied. See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.google.cloud.hadoop.gcsio;

import com.google.api.client.http.HttpRequestInitializer;
import com.google.api.client.http.HttpTransport;
import com.google.cloud.hadoop.util.testing.FakeCredentials;
import java.io.IOException;

public class MockGoogleCloudStorageImplFactory {

  public static GoogleCloudStorageImpl mockedGcs(HttpTransport transport) throws IOException {
    return mockedGcs(
        GoogleCloudStorageOptions.builder()
            .setAppName("gcsio-unit-test")
            .setProjectId("google.com:foo-project")
            .build(),
        transport);
  }

  public static GoogleCloudStorageImpl mockedGcs(
      GoogleCloudStorageOptions options, HttpTransport transport) throws IOException {
    return mockedGcs(options, transport, /* httpRequestInitializer= */ null);
  }

  public static GoogleCloudStorageImpl mockedGcs(
      GoogleCloudStorageOptions options,
      HttpTransport transport,
      HttpRequestInitializer httpRequestInitializer)
      throws IOException {
    return new GoogleCloudStorageImpl(
        options,
        new FakeCredentials(),
        transport,
        httpRequestInitializer,
        /* downscopedAccessTokenFn= */ null);
  }
}
