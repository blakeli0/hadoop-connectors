/*
 * Copyright 2015 Google Inc. All Rights Reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may
 * not use this file except in compliance with the License. You may obtain
 * a copy of the License at
 *
 *  http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.google.cloud.hadoop.gcsio;

import static com.google.common.base.Preconditions.checkNotNull;

import com.google.cloud.hadoop.gcsio.integration.GoogleCloudStorageTestHelper;
import com.google.cloud.hadoop.gcsio.testing.TestConfiguration;
import java.io.IOException;
import java.net.URI;
import java.nio.channels.SeekableByteChannel;
import java.nio.channels.WritableByteChannel;
import java.util.List;

public class GoogleCloudStorageFileSystemIntegrationHelper
    extends GoogleCloudStorageIntegrationHelper {

  public static GoogleCloudStorageFileSystem createGcsFs(String projectId) throws IOException {
    GoogleCloudStorageOptions gcsOptions =
        GoogleCloudStorageOptions.builder()
            .setAppName(GoogleCloudStorageTestHelper.APP_NAME)
            .setProjectId(projectId)
            .setCopyWithRewriteEnabled(true)
            .build();

    return new GoogleCloudStorageFileSystemImpl(
        GoogleCloudStorageTestHelper.getCredentials(),
        GoogleCloudStorageFileSystemOptions.builder()
            .setBucketDeleteEnabled(true)
            .setCloudStorageOptions(gcsOptions)
            .build());
  }

  public static GoogleCloudStorageFileSystemIntegrationHelper create() throws IOException {
    String projectId =
        checkNotNull(TestConfiguration.getInstance().getProjectId(), "projectId can not be null");
    GoogleCloudStorageFileSystem gcsFs = createGcsFs(projectId);
    return new GoogleCloudStorageFileSystemIntegrationHelper(gcsFs);
  }

  /** Opens the given object for writing. */
  @Override
  protected WritableByteChannel create(URI path, CreateFileOptions options) throws IOException {
    return gcsfs.create(path, options);
  }

  public final GoogleCloudStorageFileSystem gcsfs;

  public GoogleCloudStorageFileSystemIntegrationHelper(GoogleCloudStorageFileSystem gcsfs) {
    super(gcsfs.getGcs());
    this.gcsfs = checkNotNull(gcsfs);
  }

  /** Opens the given object for reading. */
  @Override
  protected SeekableByteChannel open(String bucketName, String objectName) throws IOException {
    URI path = getPath(bucketName, objectName);
    return gcsfs.open(path);
  }

  /** Opens the given object for reading. */
  @Override
  protected SeekableByteChannel open(URI path) throws IOException {
    return gcsfs.open(path);
  }

  /** Opens the given object for reading, with the specified read options. */
  @Override
  protected SeekableByteChannel open(
      String bucketName, String objectName, GoogleCloudStorageReadOptions readOptions)
      throws IOException {
    URI path = getPath(bucketName, objectName);
    return open(path, readOptions);
  }

  /** Opens the given object for reading, with the specified read options. */
  @Override
  protected SeekableByteChannel open(URI path, GoogleCloudStorageReadOptions readOptions)
      throws IOException {
    return gcsfs.open(path, readOptions);
  }

  /** Opens the given object for reading, with the specified read options. */
  @Override
  protected SeekableByteChannel open(FileInfo fileInfo, GoogleCloudStorageReadOptions readOptions)
      throws IOException {
    return gcsfs.open(fileInfo, readOptions);
  }

  /** Creates a directory. */
  @Override
  protected void mkdir(String bucketName, String objectName) throws IOException {
    URI path = getPath(bucketName, objectName);
    gcsfs.mkdir(path);
  }

  /** Creates a directory. */
  @Override
  protected void mkdir(String bucketName) throws IOException {
    URI path = getPath(bucketName, null);
    gcsfs.mkdir(path);
  }

  /** Deletes the given item. */
  @Override
  protected void delete(String bucketName) throws IOException {
    URI path = getPath(bucketName, null);
    gcsfs.delete(path, false);
  }

  /** Deletes the given object. */
  @Override
  protected void delete(String bucketName, String objectName) throws IOException {
    URI path = getPath(bucketName, objectName);
    gcsfs.delete(path, false);
  }

  /** Deletes the given path. */
  protected boolean delete(URI path, boolean recursive) throws IOException {
    gcsfs.delete(path, recursive);
    return true;
  }

  /** Deletes all objects from the given bucket. */
  @Override
  protected void clearBucket(String bucketName) throws IOException {
    URI path = getPath(bucketName, null);
    for (FileInfo fileInfo : gcsfs.listFileInfo(path)) {
      gcsfs.delete(fileInfo.getPath(), /* recursive= */ true);
    }
  }

  /** Renames src path to dst path. */
  protected boolean rename(URI src, URI dst) throws IOException {
    gcsfs.rename(src, dst);
    return true;
  }

  /** Creates the given directory. */
  protected boolean mkdirs(URI path) throws IOException {
    gcsfs.mkdirs(path);
    return true;
  }

  /** Creates the given directory. */
  protected boolean compose(List<URI> sources, URI destination, String contentType)
      throws IOException {
    gcsfs.compose(sources, destination, contentType);
    return true;
  }

  /** Indicates whether the given path exists. */
  protected boolean exists(URI path) throws IOException {
    return gcsfs.exists(path);
  }

  /** Indicates whether the given path is directory. */
  protected boolean isDirectory(URI path) throws IOException {
    return gcsfs.getFileInfo(path).isDirectory();
  }

  // -----------------------------------------------------------------
  // Misc helpers
  // -----------------------------------------------------------------

  /** Helper to construct a path. */
  protected URI getPath(String bucketName, String objectName) {
    return UriPaths.fromStringPathComponents(
        bucketName, objectName, /* allowEmptyObjectName= */ true);
  }

  public String getItemName(URI src) {
    return GoogleCloudStorageFileSystemImpl.getItemName(src);
  }
}
