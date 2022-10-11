/*
 * Copyright 2013 Google Inc. All Rights Reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.google.cloud.hadoop.fs.gcs;

import com.google.cloud.hadoop.gcsio.GoogleCloudStorageReadOptions;
import com.google.common.base.Preconditions;
import com.google.common.base.Stopwatch;
import com.google.common.flogger.GoogleLogger;
import com.google.common.flogger.LazyArgs;
import com.google.gson.Gson;
import java.io.IOException;
import java.net.URI;
import java.nio.ByteBuffer;
import java.nio.channels.ClosedChannelException;
import java.nio.channels.SeekableByteChannel;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import org.apache.hadoop.fs.FSInputStream;
import org.apache.hadoop.fs.FileSystem;

/** A seekable and positionable FSInputStream that provides read access to a file. */
class GoogleHadoopFSInputStream extends FSInputStream {

  private static final GoogleLogger logger = GoogleLogger.forEnclosingClass();
  static final String READ_METHOD = "gcsFSRead";
  static final String POSITIONAL_READ_METHOD = "gcsFSReadPositional";
  static final String SEEK_METHOD = "gcsFSSeek";
  static final String CLOSE_METHOD = "gcsFSClose";

  static final String DURATION_NS = "durationNs";
  static final String BYTES_READ = "bytesRead";
  static final String GCS_PATH = "gcsPath";

  private static final Gson gson = new Gson();

  private final boolean isTraceLoggingEnabled;

  // All store IO access goes through this.
  private final SeekableByteChannel channel;

  // Path of the file to read.
  private URI gcsPath;

  // Number of bytes read through this channel.
  private long totalBytesRead;

  // Statistics tracker provided by the parent GoogleHadoopFileSystemBase for recording
  // numbers of bytes read.
  private final FileSystem.Statistics statistics;

  // Used for single-byte reads.
  private final byte[] singleReadBuf = new byte[1];

  /**
   * Constructs an instance of GoogleHadoopFSInputStream object.
   *
   * @param ghfs Instance of GoogleHadoopFileSystemBase.
   * @param gcsPath Path of the file to read from.
   * @param statistics File system statistics object.
   * @throws IOException if an IO error occurs.
   */
  GoogleHadoopFSInputStream(
      GoogleHadoopFileSystemBase ghfs,
      URI gcsPath,
      GoogleCloudStorageReadOptions readOptions,
      FileSystem.Statistics statistics)
      throws IOException {
    logger.atFiner().log(
        "GoogleHadoopFSInputStream(gcsPath: %s, readOptions: %s)", gcsPath, readOptions);
    this.gcsPath = gcsPath;
    this.statistics = statistics;
    this.totalBytesRead = 0;
    this.isTraceLoggingEnabled = readOptions.isTraceLogEnabled();
    this.channel = ghfs.getGcsFs().open(gcsPath, readOptions);
  }

  /**
   * Reads a single byte from the underlying store.
   *
   * @return A single byte from the underlying store or -1 on EOF.
   * @throws IOException if an IO error occurs.
   */
  @Override
  public synchronized int read() throws IOException {
    // TODO(user): Wrap this in a while-loop if we ever introduce a non-blocking mode for the
    // underlying channel.
    int numRead = channel.read(ByteBuffer.wrap(singleReadBuf));
    if (numRead == -1) {
      return -1;
    }
    if (numRead != 1) {
      throw new IOException(
          String.format(
              "Somehow read %d bytes using single-byte buffer for path %s ending in position %d!",
              numRead, gcsPath, channel.position()));
    }
    byte b = singleReadBuf[0];

    totalBytesRead++;
    statistics.incrementBytesRead(1);
    statistics.incrementReadOps(1);
    return (b & 0xff);
  }

  /**
   * Reads up to length bytes from the underlying store and stores them starting at the specified
   * offset in the given buffer. Less than length bytes may be returned.
   *
   * @param buf The buffer into which data is returned.
   * @param offset The offset at which data is written.
   * @param length Maximum number of bytes to read.
   * @return Number of bytes read or -1 on EOF.
   * @throws IOException if an IO error occurs.
   */
  @Override
  public synchronized int read(byte[] buf, int offset, int length) throws IOException {
    Stopwatch stopwatch = Stopwatch.createStarted();
    Map<String, Object> apiTraces = new HashMap<>();
    apiTraces.put("offset", offset);
    apiTraces.put("length", length);
    Preconditions.checkNotNull(buf, "buf must not be null");
    if (offset < 0 || length < 0 || length > buf.length - offset) {
      throw new IndexOutOfBoundsException();
    }
    int numRead = channel.read(ByteBuffer.wrap(buf, offset, length));
    apiTraces.put(BYTES_READ, numRead);
    captureAPIDuration(apiTraces, stopwatch);
    captureAPITraces(READ_METHOD, apiTraces);

    if (numRead > 0) {
      // -1 means we actually read 0 bytes, but requested at least one byte.
      statistics.incrementBytesRead(numRead);
      statistics.incrementReadOps(1);
      totalBytesRead += numRead;
    }

    return numRead;
  }

  /**
   * Reads up to length bytes from the underlying store and stores them starting at the specified
   * offset in the given buffer. Less than length bytes may be returned. Reading starts at the given
   * position.
   *
   * @param position Data is read from the stream starting at this position.
   * @param buf The buffer into which data is returned.
   * @param offset The offset at which data is written.
   * @param length Maximum number of bytes to read.
   * @return Number of bytes read or -1 on EOF.
   * @throws IOException if an IO error occurs.
   */
  @Override
  public synchronized int read(long position, byte[] buf, int offset, int length)
      throws IOException {
    Stopwatch stopwatch = Stopwatch.createStarted();
    Map<String, Object> apiTraces = new HashMap<>();
    apiTraces.put("offset", offset);
    apiTraces.put("position", position);
    apiTraces.put("length", length);
    int result = super.read(position, buf, offset, length);
    apiTraces.put(BYTES_READ, result);
    captureAPIDuration(apiTraces, stopwatch);
    captureAPITraces(POSITIONAL_READ_METHOD, apiTraces);
    if (result > 0) {
      // -1 means we actually read 0 bytes, but requested at least one byte.
      statistics.incrementBytesRead(result);
      totalBytesRead += result;
    }
    return result;
  }

  /**
   * Gets the current position within the file being read.
   *
   * @return The current position within the file being read.
   * @throws IOException if an IO error occurs.
   */
  @Override
  public synchronized long getPos() throws IOException {
    long pos = channel.position();
    logger.atFiner().log("getPos(): %d", pos);
    return pos;
  }

  /**
   * Sets the current position within the file being read.
   *
   * @param pos The position to seek to.
   * @throws IOException if an IO error occurs or if the target position is invalid.
   */
  @Override
  public synchronized void seek(long pos) throws IOException {
    logger.atFiner().log("seek(%d)", pos);
    Stopwatch stopwatch = Stopwatch.createStarted();
    Map<String, Object> apiTraces = new HashMap<>();
    apiTraces.put("position", pos);
    try {
      channel.position(pos);
      captureAPIDuration(apiTraces, stopwatch);
      captureAPITraces(SEEK_METHOD, apiTraces);
    } catch (IllegalArgumentException e) {
      throw new IOException(e);
    }
  }

  /**
   * Seeks a different copy of the data. Not supported.
   *
   * @return true if a new source is found, false otherwise.
   */
  @Override
  public synchronized boolean seekToNewSource(long targetPos) throws IOException {
    return false;
  }

  /**
   * Closes the current stream.
   *
   * @throws IOException if an IO error occurs.
   */
  @Override
  public synchronized void close() throws IOException {
    logger.atFiner().log("close(): %s", gcsPath);
    Stopwatch stopwatch = Stopwatch.createStarted();
    Map<String, Object> apiTraces = new HashMap<>();
    if (channel != null) {
      logger.atFiner().log("Closing '%s' file with %d total bytes read", gcsPath, totalBytesRead);
      channel.close();
      captureAPIDuration(apiTraces, stopwatch);
      captureAPITraces(CLOSE_METHOD, apiTraces);
    }
  }

  /**
   * Indicates whether this stream supports the 'mark' functionality.
   *
   * @return false (functionality not supported).
   */
  @Override
  public boolean markSupported() {
    // HDFS does not support it either and most Hadoop tools do not expect it.
    return false;
  }

  @Override
  public int available() throws IOException {
    if (!channel.isOpen()) {
      throw new ClosedChannelException();
    }
    return super.available();
  }

  private void captureAPIDuration(Map<String, Object> apiTraces, Stopwatch stopwatch) {
    if (apiTraces == null) {
      apiTraces = new HashMap<>();
    }
    // Capturing time in nanos because majority of the reads will be done from cache
    apiTraces.put(DURATION_NS, stopwatch.elapsed(TimeUnit.NANOSECONDS));
  }

  private void captureAPITraces(String method, Map<String, Object> apiTraces) {
    if (isTraceLoggingEnabled) {
      Map<String, Object> jsonMap = new HashMap<>();
      jsonMap.put(String.format("%s_%s", method, GCS_PATH), gcsPath);
      if (apiTraces != null && !apiTraces.isEmpty()) {
        for (Map.Entry item : apiTraces.entrySet()) {
          jsonMap.put(String.format("%s_%s", method, item.getKey()), item.getValue());
        }
      }
      logger.atInfo().log("%s", LazyArgs.lazy(() -> gson.toJson(jsonMap)));
    }
  }
}
